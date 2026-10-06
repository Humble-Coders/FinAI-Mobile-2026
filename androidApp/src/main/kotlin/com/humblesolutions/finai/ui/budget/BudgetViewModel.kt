package com.humblesolutions.finai.ui.budget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorBudgetRepository
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.usecase.BudgetDraft
import com.humblesolutions.finai.usecase.BudgetEdit
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * A month's budget, and the edits to it (#47, PRD F4).
 *
 * **A write answers with the whole budget, so nothing is stitched.** `PUT`
 * and `DELETE` both return the recomputed month, and this model puts that
 * straight into state rather than patching the line it sent and re-reading
 * afterwards. The totals and every other suggestion can move on one edit;
 * a patched copy would briefly show a combination that never existed.
 */
class BudgetViewModel(
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restored())
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()

    private var repositories: BudgetRepositories? = null

    /**
     * Whose budget is on screen, kept in saved state.
     *
     * A plain field comes back null after Android reclaims the process, which
     * makes the first [bind] look like a different person signing in — and
     * the reset that follows would throw away the draft [restored] had just
     * brought back.
     */
    private var boundTo: String?
        get() = saved[KEY_OWNER]
        set(value) {
            saved[KEY_OWNER] = value
        }

    /** Rises on every bind and month change, so a late answer for a month the
     *  person has left cannot land on the one they are looking at. */
    private var generation = 0

    private var listening = false

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            BudgetRepositories(
                budgets = KtorBudgetRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> BudgetRepositories?) {
        if (userId.isBlank()) return
        // Only a different person clears what is on screen. Coming back from
        // a reclaimed process is the same person, and their half-typed amount
        // is the one thing here they cannot get back by reloading.
        if (userId != boundTo) {
            repositories?.close()
            repositories = null
            generation++
            boundTo = userId
            _uiState.value = BudgetUiState()
            store(_uiState.value)
        }
        if (repositories != null) return
        repositories = build() ?: return
        listenForChanges()
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /**
     * Re-read when an import or a correction moves the ledger: every line's
     * `spent` is a figure about transactions, so it goes stale behind them.
     *
     * [BudgetChanged] is deliberately **not** collected here. This screen is
     * the only thing that emits it, and it already has the budget that write
     * returned.
     */
    private fun listenForChanges() {
        if (listening) return
        listening = true
        viewModelScope.launch {
            LedgerChanged.events.collect { load(refresh = true) }
        }
    }

    /** Show a different month. The budget for it is read fresh. */
    fun showMonth(month: String) {
        if (month == _uiState.value.month) return
        generation++
        update { it.copy(month = month, refreshing = true, errorKey = null, loadFailed = false) }
        load(refresh = true)
    }

    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = generation
        val month = _uiState.value.month
        update {
            if (refresh) it.copy(refreshing = true) else it.copy(loading = true, loadFailed = false, errorKey = null)
        }
        viewModelScope.launch {
            // Capabilities decide whether the tab exists at all. A failure to
            // read them leaves it as it was rather than hiding a tab the
            // person was using because one call did not land.
            val capabilities = try {
                repos.capabilities.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                null
            }
            val categories = try {
                repos.categories.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Only the picker needs these. Losing them costs "Add a
                // category", not the budget.
                emptyList<Category>()
            }
            val result = runCatching { repos.budgets.get(month) }
            if (started != generation) return@launch
            result
                .onSuccess { budget -> settle(budget, categories, capabilities) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    failed(error, categories, capabilities)
                }
        }
    }

    private fun settle(budget: Budget, categories: List<Category>, capabilities: Capabilities?) {
        update {
            it.copy(
                budget = budget,
                // An empty list here is a failed categories call, not a
                // household with no categories — keep the ones we have, as
                // `failed` does. Losing them costs "Add a category".
                categories = categories.ifEmpty { it.categories },
                locale = capabilities?.locale?.takeIf(String::isNotBlank) ?: it.locale,
                loading = false,
                refreshing = false,
                loadFailed = false,
                errorKey = null,
            )
        }
    }

    private fun failed(error: Throwable, categories: List<Category>, capabilities: Capabilities?) {
        val api = error as? ApiException
        update {
            it.copy(
                categories = categories.ifEmpty { it.categories },
                locale = capabilities?.locale?.takeIf(String::isNotBlank) ?: it.locale,
                loading = false,
                refreshing = false,
                // A refresh that fails keeps the figures it had rather than
                // zeroing them (CLAUDE.md → Data & caching).
                loadFailed = it.budget == null,
                errorKey = api?.messageKey,
            )
        }
    }

    // ── Editing one line ────────────────────────────────────────────────

    /** Open the editor on an existing line. */
    fun edit(categoryId: String) {
        val line = _uiState.value.budget?.allLines?.firstOrNull { it.categoryId == categoryId } ?: return
        update {
            it.copy(editing = line, editingIsNew = false, draft = BudgetEdit.draftOf(line), editErrorKey = null)
        }
    }

    /** Open the picker, to add a line for a category that has none. */
    fun addCategory() = update { it.copy(picking = true) }

    fun cancelPicking() = update { it.copy(picking = false) }

    /**
     * A category chosen from the picker becomes an editor on a line that
     * does not exist yet. Its suggestion is zero — there is no history
     * behind it — so there is nothing to offer putting it back to.
     */
    fun pickCategory(categoryId: String) {
        val category = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        if (!BudgetEdit.isBudgetable(category.slug)) return
        update {
            it.copy(
                picking = false,
                editing = BudgetLine(categoryId = category.id, slug = category.slug, name = category.name),
                editingIsNew = true,
                draft = BudgetDraft(),
                editErrorKey = null,
            )
        }
    }

    fun onAmountChange(amount: String) = update { it.copy(draft = BudgetDraft(amount), editErrorKey = null) }

    fun cancelEdit() = update {
        it.copy(editing = null, editingIsNew = false, draft = BudgetDraft(), editErrorKey = null)
    }

    /**
     * Send the line.
     *
     * The same [BudgetEdit] call the button reads decides this too, so a
     * draft the screen would not let through cannot arrive here by another
     * path. A failure keeps the sheet open with what was typed: the person's
     * input is the thing hardest to get back.
     */
    fun save() {
        val repos = repositories ?: return
        val state = _uiState.value
        val line = state.editing ?: return
        if (state.editBlock != null || state.saving) return
        val amount = state.draft.amount
        update { it.copy(saving = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                val budget = repos.budgets.setLine(state.month, line.categoryId, amount)
                update {
                    it.copy(budget = budget, saving = false, editing = null, editingIsNew = false, draft = BudgetDraft())
                }
                BudgetChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(saving = false, editErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * Put the line back to the server's current suggestion.
     *
     * A hand-added line with nothing behind it is removed instead — that is
     * the server's doing, and the budget it answers with simply no longer
     * has the line in it.
     */
    fun useSuggestion() {
        val repos = repositories ?: return
        val state = _uiState.value
        val line = state.editing ?: return
        if (state.resetting || state.editingIsNew) return
        update { it.copy(resetting = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                val budget = repos.budgets.resetLine(state.month, line.categoryId)
                update {
                    it.copy(budget = budget, resetting = false, editing = null, editingIsNew = false, draft = BudgetDraft())
                }
                BudgetChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(resetting = false, editErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * The month being looked at and an amount half-typed, brought back after
     * Android reclaimed the process (#47 UI standards, as manual entry does).
     *
     * A `ViewModel` alone survives rotation but not process death, and losing
     * a figure someone was part-way through typing is the one thing on this
     * screen they cannot get back by reloading.
     *
     * Only the draft is kept, never the budget: those figures are the
     * server's and are re-read on bind, and a stale copy restored from disk
     * is exactly the kind of number this screen must not invent.
     */
    private fun restored(): BudgetUiState {
        val editing: BudgetLine? = saved.get<String>(KEY_EDITING_ID)?.let { id ->
            BudgetLine(
                categoryId = id,
                slug = saved[KEY_EDITING_SLUG] ?: "",
                name = saved[KEY_EDITING_NAME] ?: "",
                suggested = saved[KEY_EDITING_SUGGESTED] ?: "0",
                allocated = saved[KEY_EDITING_ALLOCATED] ?: "0",
                isUserSet = saved[KEY_EDITING_USER_SET] ?: false,
                spent = saved[KEY_EDITING_SPENT] ?: "0",
            )
        }
        return BudgetUiState(
            month = saved[KEY_MONTH] ?: DashboardMonths.wire(DashboardMonths.current()),
            editing = editing,
            editingIsNew = saved[KEY_EDITING_IS_NEW] ?: false,
            draft = BudgetDraft(saved[KEY_DRAFT_AMOUNT] ?: ""),
        )
    }

    private fun store(state: BudgetUiState) {
        saved[KEY_MONTH] = state.month
        saved[KEY_DRAFT_AMOUNT] = state.draft.amount
        saved[KEY_EDITING_IS_NEW] = state.editingIsNew
        saved[KEY_EDITING_ID] = state.editing?.categoryId
        saved[KEY_EDITING_SLUG] = state.editing?.slug
        saved[KEY_EDITING_NAME] = state.editing?.name
        saved[KEY_EDITING_SUGGESTED] = state.editing?.suggested
        saved[KEY_EDITING_ALLOCATED] = state.editing?.allocated
        saved[KEY_EDITING_USER_SET] = state.editing?.isUserSet
        saved[KEY_EDITING_SPENT] = state.editing?.spent
    }

    private fun update(block: (BudgetUiState) -> BudgetUiState) {
        _uiState.update(block)
        store(_uiState.value)
    }

    internal companion object {
        private const val KEY_OWNER = "budget.owner"
        private const val KEY_MONTH = "budget.month"
        private const val KEY_DRAFT_AMOUNT = "budget.draft.amount"
        private const val KEY_EDITING_IS_NEW = "budget.editing.isNew"
        private const val KEY_EDITING_ID = "budget.editing.id"
        private const val KEY_EDITING_SLUG = "budget.editing.slug"
        private const val KEY_EDITING_NAME = "budget.editing.name"
        private const val KEY_EDITING_SUGGESTED = "budget.editing.suggested"
        private const val KEY_EDITING_ALLOCATED = "budget.editing.allocated"
        private const val KEY_EDITING_USER_SET = "budget.editing.userSet"
        private const val KEY_EDITING_SPENT = "budget.editing.spent"
    }
}

/** What this screen talks to. Closed together when the model is cleared. */
internal class BudgetRepositories(
    val budgets: BudgetRepository,
    val categories: CategoriesRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        budgets.close()
        categories.close()
        capabilities.close()
    }
}
