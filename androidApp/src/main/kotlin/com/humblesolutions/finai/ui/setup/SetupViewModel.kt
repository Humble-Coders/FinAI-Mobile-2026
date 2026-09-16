package com.humblesolutions.finai.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorFinancialSetupRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupDraft
import com.humblesolutions.finai.usecase.SetupStep
import com.humblesolutions.finai.usecase.SetupWizard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the financial setup wizard (PRD F1, ticket #17).
 *
 * The repository is built fresh in [bind] and closed in [close] — never a
 * singleton (kmp-arch-v2). Which step to show and whether it may be left are
 * never decided here: [SetupWizard] answers both, and iOS reads the same rules.
 *
 * **Every step saves.** The call replaces what the wizard owns, so each save
 * carries the whole draft; closing the app mid-way then resumes where it left
 * off, and a failed save keeps what was typed.
 */
class SetupViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private var repository: FinancialSetupRepository? = null
    private var capabilities: CapabilitiesRepository? = null

    /** Whose wizard this is holding. Null until the first bind. */
    private var boundTo: String? = null

    /**
     * Bumped every time the wizard is handed to someone new. A request records
     * the value it set out under, and its reply is applied only if that is still
     * the value — so a slow answer for the last user can never land in the next
     * one's wizard (kmp-arch-v2 → guard async results against staleness).
     */
    private var generation = 0

    /**
     * Binds to [userId], and stays bound while that user is the one signed in.
     *
     * A `ViewModel` rather than something remembered by the screen, so that a
     * rotation recreates the composition and finds this again — with the figure
     * that was being typed still in it (UI standards → preserve state across a
     * configuration change). The guard matters for exactly that reason: the
     * second call comes from the recreated screen, and reloading there would
     * overwrite what the user had typed with what the server last stored.
     *
     * That longevity is why the id is checked, rather than merely whether a
     * repository exists. This model outlives a sign-out, so binding without
     * asking who wants it would hand the next person to sign in the previous
     * one's draft — and write their figures to the new account on the first
     * Continue (kmp-arch-v2 → `bind`).
     */
    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            SetupRepositories(
                setup = KtorFinancialSetupRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    /**
     * The whole of [bind], with where the repositories come from left open, so
     * a test drives exactly the path the app does rather than a piece of it.
     */
    internal fun bind(userId: String, build: () -> SetupRepositories?) {
        if (userId.isBlank()) return refuseUnknownUser()
        if (userId == boundTo) return
        releaseTo(userId)
        val built = build() ?: return
        repository = built.setup
        capabilities = built.capabilities
        load()
    }

    /**
     * Drops the clients that carried the last user's token, and everything they
     * typed, so nothing crosses from one account into the next — including any
     * reply still on its way for them.
     */
    private fun releaseTo(userId: String?) {
        closeClients()
        boundTo = userId
        generation++
        _uiState.value = SetupUiState()
    }

    /**
     * No id to bind to. Nothing of the last user may stay on screen, and a
     * spinner that never stops is not an answer, so this says so instead.
     */
    private fun refuseUnknownUser() {
        releaseTo(null)
        _uiState.value = SetupUiState(loading = false, errorKey = Strings.error_unexpected)
    }

    override fun onCleared() = closeClients()

    private fun closeClients() {
        repository?.close()
        repository = null
        capabilities?.close()
        capabilities = null
    }

    /** What is already saved decides where the wizard opens. */
    fun load() {
        val repository = repository ?: return
        val capabilities = capabilities
        val started = generation
        _uiState.update { it.copy(loading = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val saved = repository.get()
                val resume = SetupWizard.resumeAt(saved)
                // The currency and the language figures are written in come
                // from capabilities (ticket #17). The setup response names a
                // currency too, which stands in when capabilities cannot be
                // had: what this screen must not do is guess from the device.
                val payload = capabilitiesOrNull(capabilities)
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        currency = payload?.currency?.ifBlank { null } ?: saved.currency,
                        locale = payload?.locale.orEmpty(),
                        draft = SetupWizard.draftFrom(saved),
                        step = resume,
                        reached = resume,
                        touched = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(loading = false, errorKey = e.messageKey) }
            }
        }
    }

    /**
     * How the wizard is asked for capabilities: it decorates the screen, so a
     * failure to fetch it is not a failure of the screen.
     */
    private suspend fun capabilitiesOrNull(capabilities: CapabilitiesRepository?): Capabilities? = try {
        capabilities?.fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }

    fun onIncomeChange(value: String) = _uiState.update {
        it.copy(draft = it.draft.copy(income = value), errorKey = null, touched = true)
    }

    fun onExpenseChange(value: String) = _uiState.update {
        it.copy(draft = it.draft.copy(monthlyExpense = value), errorKey = null, touched = true)
    }

    /** Back a step, or nothing to do on the first one. */
    fun back() = _uiState.update {
        when (it.step) {
            SetupStep.INCOME -> it
            SetupStep.EXPENSES -> it.copy(step = SetupStep.INCOME, errorKey = null, touched = false)
            SetupStep.PORTFOLIO -> it.copy(step = SetupStep.EXPENSES, errorKey = null, touched = false)
        }
    }

    /**
     * Saves and moves on. The last step hands back to onboarding, which reloads
     * `/me` — the gate clears once income and expense are stored.
     */
    fun continueStep(onFinished: () -> Unit) {
        val state = _uiState.value
        if (!state.canContinue) return
        save(onSaved = { advance(onFinished) })
    }

    /**
     * Past the last step without filling it in: an empty list is the record (#29).
     *
     * Only a step that is optional in full offers this, so there is nothing
     * left here that could refuse — dropping the lists drops the only thing on
     * the step that can block. A Skip the user can see always goes through.
     */
    fun skip(onFinished: () -> Unit) {
        val state = _uiState.value
        if (!state.canSkip) return
        val cleared = when (state.step) {
            SetupStep.PORTFOLIO -> state.draft.copy(debts = emptyList(), investments = emptyList())
            // Mandatory: [SetupUiState.canSkip] never lets one of these here.
            SetupStep.INCOME, SetupStep.EXPENSES -> return
        }
        _uiState.update { it.copy(draft = cleared, touched = false) }
        save(onSaved = { advance(onFinished) })
    }

    private fun advance(onFinished: () -> Unit) {
        val next = when (_uiState.value.step) {
            SetupStep.INCOME -> SetupStep.EXPENSES
            SetupStep.EXPENSES -> SetupStep.PORTFOLIO
            SetupStep.PORTFOLIO -> return onFinished()
        }
        _uiState.update { it.copy(step = next, reached = maxOf(it.reached, next), touched = false) }
    }

    /**
     * The user swiped. Only as far as the wizard has already been: a step whose
     * figure is still missing is not reachable by sliding past it.
     */
    fun goTo(step: SetupStep) = _uiState.update {
        if (step.ordinal > it.reached.ordinal) it else it.copy(step = step, errorKey = null, touched = false)
    }

    // ── The itemised lists ──────────────────────────────────────────────

    fun openList(list: ItemList) = _uiState.update {
        it.copy(editing = list, rows = it.itemsOf(list).ifEmpty { listOf(ItemDraft()) }, errorKey = null)
    }

    fun onRowChange(index: Int, row: ItemDraft) = _uiState.update {
        it.copy(rows = it.rows.mapIndexed { i, existing -> if (i == index) row else existing }, errorKey = null)
    }

    fun addRow() = _uiState.update { it.copy(rows = it.rows + ItemDraft()) }

    fun removeRow(index: Int) = _uiState.update {
        val remaining = it.rows.filterIndexed { i, _ -> i != index }
        it.copy(rows = remaining)
    }

    /** Keeps the rows and saves them, so a list survives the app closing. */
    fun keepRows() {
        val state = _uiState.value
        val list = state.editing ?: return
        if (!state.canKeepRows) return
        val kept = state.rows.filterNot { it.name.isBlank() && it.amount.isBlank() }
        val draft = when (list) {
            ItemList.OBLIGATIONS -> state.draft.copy(obligations = kept)
            ItemList.DEBTS -> state.draft.copy(debts = kept)
            ItemList.INVESTMENTS -> state.draft.copy(investments = kept)
        }
        _uiState.update { it.copy(draft = draft, editing = null, rows = emptyList()) }
        save(onSaved = {})
    }

    /** Leaves the list as it was before it was opened. */
    fun discardRows() = _uiState.update { it.copy(editing = null, rows = emptyList(), errorKey = null) }

    private fun save(onSaved: () -> Unit) {
        val repository = repository ?: return
        val state = _uiState.value
        val started = generation
        _uiState.update { it.copy(busy = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val saved = repository.save(
                    SetupWizard.payload(state.draft, state.currency, state.fractionDigits),
                )
                // Someone else's wizard now: neither their figures nor a step
                // forward belong on it.
                if (started != generation) return@launch
                // What came back is what is stored, so the screens show the
                // server's version rather than what was typed at it.
                _uiState.update {
                    it.copy(busy = false, currency = saved.currency, draft = SetupWizard.draftFrom(saved))
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // The draft is untouched: a failed save must never lose figures.
                _uiState.update { it.copy(busy = false, errorKey = e.messageKey) }
            } catch (e: Exception) {
                if (started != generation) return@launch
                _uiState.update { it.copy(busy = false, errorKey = Strings.error_unexpected) }
            }
        }
    }
}

/** The two clients the wizard needs, built together for one signed-in user. */
internal class SetupRepositories(
    val setup: FinancialSetupRepository,
    val capabilities: CapabilitiesRepository,
)
