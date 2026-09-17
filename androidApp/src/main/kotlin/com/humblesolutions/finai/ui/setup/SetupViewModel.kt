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
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupStep
import com.humblesolutions.finai.usecase.SetupWizard
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the financial setup wizard (PRD F1, ticket #17).
 *
 * The repositories are built fresh in [bind] and closed with the model — never
 * singletons (kmp-arch-v2). Which step may be left, and what the notice says,
 * are never decided here: [SetupWizard] answers both, and iOS reads the same
 * rules.
 *
 * **Continue does not wait.** The step slides the moment it is pressed and the
 * save runs behind it, shown only by a small loader, and only while a request is
 * really in flight: a draft the server already holds is not sent again. The last
 * step is the one exception — the gate clears on that save, so Complete waits.
 * Every save still carries the whole draft, so closing the app resumes where it
 * left off and a failed save keeps what was typed.
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
     * What the server holds, as far as this model knows — last loaded or last
     * saved. A draft equal to it is not sent again, which is what keeps moving
     * back and forth through the steps free of loaders.
     */
    private var lastSaved: FinancialSetup? = null

    /** The save loop, while one is running. */
    private var saver: Job? = null

    /**
     * Binds to [userId], and stays bound while that user is the one signed in.
     *
     * A `ViewModel` rather than something remembered by the screen, so that a
     * rotation recreates the composition and finds this again — with the figure
     * being typed still in it. That longevity is why the id is checked: this
     * model outlives a sign-out, so binding without asking who wants it would
     * hand the next person the previous one's draft.
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
        saver?.cancel()
        saver = null
        lastSaved = null
        boundTo = userId
        generation++
        _uiState.value = SetupUiState()
    }

    /**
     * No id to bind to. Nothing of the last user may stay on screen, and a
     * loader that never stops is not an answer, so this says so instead.
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
                        touched = false,
                    )
                }
                // What was just loaded is what the server holds, so walking
                // through the steps without changing anything sends nothing.
                lastSaved = payloadOf(_uiState.value)
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
     * Moves on at once and saves behind the move. The last step instead
     * finishes, which waits for its save: the gate clears on it.
     */
    fun continueStep(onFinished: () -> Unit) {
        val state = _uiState.value
        if (!state.canContinue) return
        if (state.step.isLast) return finish(onFinished)
        _uiState.update { it.copy(step = next(it.step), errorKey = null, touched = false) }
        persist()
    }

    /**
     * Past the last step without filling it in: an empty list is the record
     * (#29). Offered only once both mandatory figures are in, so it always goes
     * through.
     */
    fun skip(onFinished: () -> Unit) {
        val state = _uiState.value
        if (!state.canSkip) return
        _uiState.update {
            it.copy(draft = it.draft.copy(debts = emptyList(), investments = emptyList()), touched = false)
        }
        finish(onFinished)
    }

    /** The user swiped. Any step may be looked at; Continue is what stays gated. */
    fun goTo(step: SetupStep) = _uiState.update {
        if (it.step == step) it else it.copy(step = step, errorKey = null, touched = false)
    }

    /** Cancel: the wizard says setup is required, keeping what was typed. */
    fun cancel() = _uiState.update {
        it.copy(cancelled = true, editing = null, rows = emptyList(), errorKey = null)
    }

    /** Back from the required-setup screen, to step 1. */
    fun resume() = _uiState.update {
        it.copy(cancelled = false, step = SetupStep.INCOME, errorKey = null, touched = false)
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

    /** Keeps the rows and saves them behind the step, so a list survives the app closing. */
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
        persist()
    }

    /** Leaves the list as it was before it was opened. */
    fun discardRows() = _uiState.update { it.copy(editing = null, rows = emptyList(), errorKey = null) }

    // ── Saving ──────────────────────────────────────────────────────────

    /**
     * Finishes: waits for the draft on screen to be stored, then hands back to
     * onboarding, which reloads `/me` — the gate clears once income and expense
     * are in. A failed save stays here with its message and the figures intact.
     */
    private fun finish(onFinished: () -> Unit) {
        val started = generation
        _uiState.update { it.copy(busy = true, errorKey = null) }
        viewModelScope.launch {
            persist()?.join()
            if (started != generation) return@launch
            val stored = lastSaved != null && lastSaved == payloadOf(_uiState.value)
            _uiState.update { it.copy(busy = false) }
            if (stored) onFinished()
        }
    }

    /**
     * Sends the draft as it is now, unless the server already holds exactly
     * that. One loop at a time: a call while it runs returns the running loop,
     * which reads the draft afresh before stopping, so a change made mid-save
     * is sent right after rather than racing it.
     */
    private fun persist(): Job? {
        val repository = repository ?: return null
        saver?.takeIf { it.isActive }?.let { return it }
        val started = generation
        return viewModelScope.launch {
            try {
                while (true) {
                    val payload = payloadOf(_uiState.value)
                    if (payload == lastSaved) break
                    _uiState.update { it.copy(syncing = true, errorKey = null) }
                    repository.save(payload)
                    if (started != generation) return@launch
                    lastSaved = payload
                }
                _uiState.update { it.copy(syncing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // The draft is untouched: a failed save must never lose figures.
                _uiState.update { it.copy(syncing = false, errorKey = e.messageKey) }
            } catch (e: Exception) {
                if (started != generation) return@launch
                _uiState.update { it.copy(syncing = false, errorKey = Strings.error_unexpected) }
            }
        }.also { saver = it }
    }

    private fun payloadOf(state: SetupUiState): FinancialSetup =
        SetupWizard.payload(state.draft, state.currency, state.fractionDigits)

    private fun next(step: SetupStep): SetupStep = when (step) {
        SetupStep.INCOME -> SetupStep.EXPENSES
        SetupStep.EXPENSES, SetupStep.PORTFOLIO -> SetupStep.PORTFOLIO
    }
}

/** The two clients the wizard needs, built together for one signed-in user. */
internal class SetupRepositories(
    val setup: FinancialSetupRepository,
    val capabilities: CapabilitiesRepository,
)
