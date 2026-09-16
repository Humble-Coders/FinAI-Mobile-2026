package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorFinancialSetupRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupDraft
import com.humblesolutions.finai.usecase.SetupStep
import com.humblesolutions.finai.usecase.SetupWizard
import kotlinx.coroutines.CoroutineScope
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
class SetupViewModel(private val scope: CoroutineScope) {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private var repository: FinancialSetupRepository? = null

    fun bind(logging: Boolean) {
        if (repository != null) return
        val client = Supabase.clientOrNull() ?: return
        repository = KtorFinancialSetupRepository(
            baseUrl = ApiConfig.BASE_URL,
            tokens = SupabaseTokenSource(client),
            logging = logging,
        )
        load()
    }

    fun close() {
        repository?.close()
        repository = null
    }

    /** What is already saved decides where the wizard opens. */
    fun load() {
        val repository = repository ?: return
        _uiState.update { it.copy(loading = true, errorKey = null) }
        scope.launch {
            try {
                val saved = repository.get()
                _uiState.update {
                    it.copy(
                        loading = false,
                        currency = saved.currency,
                        draft = SetupWizard.draftFrom(saved),
                        step = SetupWizard.resumeAt(saved),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, errorKey = e.messageKey) }
            }
        }
    }

    fun onIncomeChange(value: String) = _uiState.update {
        it.copy(draft = it.draft.copy(income = value), errorKey = null)
    }

    fun onExpenseChange(value: String) = _uiState.update {
        it.copy(draft = it.draft.copy(monthlyExpense = value), errorKey = null)
    }

    /** Back a step, or nothing to do on the first one. */
    fun back() = _uiState.update {
        when (it.step) {
            SetupStep.INCOME -> it
            SetupStep.EXPENSES -> it.copy(step = SetupStep.INCOME, errorKey = null)
            SetupStep.PORTFOLIO -> it.copy(step = SetupStep.EXPENSES, errorKey = null)
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

    /** Past the optional part without filling it in: an empty list is the record (#29). */
    fun skip(onFinished: () -> Unit) {
        val state = _uiState.value
        if (!state.canSkip) return
        val cleared = when (state.step) {
            SetupStep.INCOME -> state.draft
            SetupStep.EXPENSES -> state.draft.copy(obligations = emptyList())
            SetupStep.PORTFOLIO -> state.draft.copy(debts = emptyList(), investments = emptyList())
        }
        _uiState.update { it.copy(draft = cleared) }
        // The mandatory pair is still required, so a skip only saves when the
        // step's own figure is answered; otherwise it just drops the extras.
        if (_uiState.value.block == null) save(onSaved = { advance(onFinished) })
    }

    private fun advance(onFinished: () -> Unit) {
        val state = _uiState.value
        when (state.step) {
            SetupStep.INCOME -> _uiState.update { it.copy(step = SetupStep.EXPENSES) }
            SetupStep.EXPENSES -> _uiState.update { it.copy(step = SetupStep.PORTFOLIO) }
            SetupStep.PORTFOLIO -> onFinished()
        }
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
        _uiState.update { it.copy(busy = true, errorKey = null) }
        scope.launch {
            try {
                val saved = repository.save(
                    SetupWizard.payload(state.draft, state.currency, state.fractionDigits),
                )
                // What came back is what is stored, so the screens show the
                // server's version rather than what was typed at it.
                _uiState.update {
                    it.copy(busy = false, currency = saved.currency, draft = SetupWizard.draftFrom(saved))
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // The draft is untouched: a failed save must never lose figures.
                _uiState.update { it.copy(busy = false, errorKey = e.messageKey) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, errorKey = Strings.error_unexpected) }
            }
        }
    }
}
