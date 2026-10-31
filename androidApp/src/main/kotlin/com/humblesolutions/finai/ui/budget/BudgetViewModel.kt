package com.humblesolutions.finai.ui.budget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorAccountsRepository
import com.humblesolutions.finai.data.KtorAiConsentRepository
import com.humblesolutions.finai.data.KtorBudgetRepository
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.AiBudgetEdit
import com.humblesolutions.finai.usecase.BudgetDraft
import com.humblesolutions.finai.usecase.BudgetEdit
import com.humblesolutions.finai.usecase.BudgetOverview
import com.humblesolutions.finai.usecase.CommitmentTick
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.SpendingCheckWords
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.LedgerChanged
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.PlanChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
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
                consent = KtorAiConsentRepository(ApiConfig.BASE_URL, tokens, logging),
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                accounts = KtorAccountsRepository(ApiConfig.BASE_URL, tokens, logging),
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
     *
     * [PlanChanged] is: since the plan redesign (2026-10-31) the plan writes
     * this budget — a split row applied to the Debt line or the savings
     * target, and the allocation diagnostic's monthly saving.
     */
    private fun listenForChanges() {
        if (listening) return
        listening = true
        viewModelScope.launch {
            LedgerChanged.events.collect { load(refresh = true) }
        }
        viewModelScope.launch {
            PlanChanged.events.collect { load(refresh = true) }
        }
    }

    /** Show a different month. The budget for it is read fresh. */
    fun showMonth(month: String) {
        if (month == _uiState.value.month) return
        generation++
        update {
            it.copy(month = month, refreshing = true, switchingMonth = true, errorKey = null, loadFailed = false, spendingCheck = null, spendingErrorKey = null)
        }
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

    /** This month's AI review, asked for once per screen when the budget says one is due. */
    private var reviewAsked: String? = null

    private fun reviewIfDue(budget: Budget) {
        val repos = repositories ?: return
        val month = _uiState.value.month
        if (!AiBudgetEdit.shouldReview(budget) || reviewAsked == month) return
        reviewAsked = month
        val started = generation
        viewModelScope.launch {
            // Quietly: a review that cannot be made leaves the budget as it was.
            val reviewed = try {
                repos.budgets.review(month)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                null
            } ?: return@launch
            if (started != generation) return@launch
            update { it.copy(budget = reviewed, editing = freshEditing(it, reviewed)) }
        }
    }

    /** "Use" on a line's AI suggestion: the line is set to it, by hand. */
    fun useAiSuggestion(categoryId: String) {
        val repos = repositories ?: return
        val state = _uiState.value
        val line = state.budget?.allLines?.firstOrNull { it.categoryId == categoryId } ?: return
        val amount = line.aiSuggested ?: return
        if (state.saving) return
        update { it.copy(saving = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val budget = repos.budgets.setLine(state.month, categoryId, amount)
                update { it.copy(budget = budget, saving = false) }
                BudgetChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(saving = false, errorKey = e.messageKey) }
            }
        }
    }

    /** "Add" on a category the AI suggests: the editor on a new line, started at the AI's amount. */
    fun addFromAi(categoryId: String) {
        val extra = _uiState.value.budget?.aiExtra?.firstOrNull { it.categoryId == categoryId } ?: return
        update {
            it.copy(
                picking = false,
                editing = BudgetLine(categoryId = extra.categoryId, slug = extra.slug, name = extra.name),
                editingIsNew = true,
                draft = BudgetDraft(extra.amount),
                editErrorKey = null,
            )
        }
    }

    private fun settle(budget: Budget, categories: List<Category>, capabilities: Capabilities?) {
        update {
            it.copy(
                budget = budget,
                editing = freshEditing(it, budget),
                // An empty list here is a failed categories call, not a
                // household with no categories — keep the ones we have, as
                // `failed` does. Losing them costs "Add a category".
                categories = categories.ifEmpty { it.categories },
                locale = capabilities?.locale?.takeIf(String::isNotBlank) ?: it.locale,
                loading = false,
                refreshing = false,
                switchingMonth = false,
                loadFailed = false,
                errorKey = null,
            )
        }
        reviewIfDue(budget)
        loadSpending()
    }

    // ── How you're spending ─────────────────────────────────────────────

    /**
     * The month's spending against income, and its last AI check. Quietly: a
     * household with no income from setup has nothing to check, and the card
     * simply is not drawn.
     */
    private fun loadSpending() {
        val repos = repositories ?: return
        val started = generation
        val month = _uiState.value.month
        viewModelScope.launch {
            val check = try {
                repos.budgets.spendingCheck(month)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                null
            }
            if (started != generation) return@launch
            update { it.copy(spendingCheck = check) }
        }
    }

    /** "Ask AI how to spend" / "Check again". */
    fun askSpending() {
        val repos = repositories ?: return
        val state = _uiState.value
        val check = state.spendingCheck ?: return
        if (state.checkingSpending || !SpendingCheckWords.canAsk(check)) return
        val started = generation
        update { it.copy(checkingSpending = true, spendingErrorKey = null) }
        viewModelScope.launch {
            try {
                val answer = repos.budgets.runSpendingCheck(state.month)
                if (started != generation) return@launch
                update { it.copy(checkingSpending = false, spendingCheck = answer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.ConsentRequired) {
                update { it.copy(checkingSpending = false) }
                askForConsent()
            } catch (e: ApiException) {
                update { it.copy(checkingSpending = false, spendingErrorKey = e.messageKey) }
            }
        }
    }

    private suspend fun askForConsent() {
        val consent = repositories?.consent ?: return
        try {
            val policy = consent.policy()
            update { it.copy(spendingPolicy = policy, spendingConsentTicked = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            update { it.copy(spendingErrorKey = e.messageKey) }
        }
    }

    fun onSpendingConsentTicked(ticked: Boolean) = update { it.copy(spendingConsentTicked = ticked) }

    /** Records consent to the policy shown, then asks. */
    fun agreeSpending() {
        val consent = repositories?.consent ?: return
        val policy = _uiState.value.spendingPolicy ?: return
        if (!_uiState.value.spendingConsentTicked) return
        update { it.copy(spendingConsentBusy = true, spendingErrorKey = null) }
        viewModelScope.launch {
            try {
                consent.consent(policy.version)
                update { it.copy(spendingConsentBusy = false, spendingPolicy = null, spendingConsentTicked = false) }
                askSpending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.AiPolicyChanged) {
                update { it.copy(spendingConsentBusy = false) }
                askForConsent()
            } catch (e: ApiException) {
                update { it.copy(spendingConsentBusy = false, spendingErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * The line being edited, as the budget just read describes it.
     *
     * An editor restored after the process was reclaimed — or open while an
     * import moved the figures — holds the line as it was when it opened, so
     * "Use suggestion" would offer an old suggestion and "nothing changed"
     * would compare against an old allocation. The line is swapped for its
     * current self; what the person typed is left alone.
     *
     * A line added by hand has nothing on the server to refresh from yet, and
     * one removed elsewhere is kept as it was — saving it recreates it, which
     * `PUT` is allowed to do.
     */
    private fun freshEditing(state: BudgetUiState, budget: Budget): BudgetLine? {
        val held = state.editing ?: return null
        if (state.editingIsNew) return held
        return budget.allLines.firstOrNull { it.categoryId == held.categoryId } ?: held
    }

    private fun failed(error: Throwable, categories: List<Category>, capabilities: Capabilities?) {
        val api = error as? ApiException
        update {
            it.copy(
                categories = categories.ifEmpty { it.categories },
                locale = capabilities?.locale?.takeIf(String::isNotBlank) ?: it.locale,
                loading = false,
                refreshing = false,
                switchingMonth = false,
                // A refresh that fails keeps the figures it had rather than
                // zeroing them (CLAUDE.md → Data & caching).
                loadFailed = it.budget == null,
                errorKey = api?.messageKey,
            )
        }
    }

    // ── Ticking a commitment off ────────────────────────────────────────

    /**
     * The sheet for one commitment: the month's payments out to match it
     * with, and a form to type the payment in. Both read once, as it opens.
     */
    fun openTick(commitmentId: String) {
        val repos = repositories ?: return
        val state = _uiState.value
        val budget = state.budget ?: return
        val commitment = budget.commitments.firstOrNull { it.id == commitmentId } ?: return
        update {
            it.copy(
                tickingId = commitmentId,
                tickRows = null,
                payAmount = Money.normalize(commitment.expected, budget.fractionDigits).orEmpty(),
                payDate = CommitmentTick.defaultDate(commitment, budget, DashboardMonths.today()),
                payAccountId = it.accounts.singleOrNull()?.id,
                tickBusy = false,
                tickErrorKey = null,
                tickDuplicate = null,
            )
        }
        val month = state.month
        viewModelScope.launch {
            val rows = mutableListOf<Transaction>()
            try {
                var cursor: String? = null
                var pages = 0
                do {
                    val page = repos.transactions?.list(month = month, cursor = cursor) ?: break
                    rows += page.rows
                    cursor = page.nextCursor
                    pages++
                } while (cursor != null && pages < TICK_PAGES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(tickErrorKey = e.messageKey) }
            }
            val accounts = try {
                repos.accounts?.list().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                emptyList()
            }
            update {
                if (it.tickingId != commitmentId) return@update it
                it.copy(
                    tickRows = rows,
                    accounts = accounts.ifEmpty { it.accounts },
                    payAccountId = it.payAccountId ?: accounts.singleOrNull()?.id,
                )
            }
        }
    }

    fun closeTick() = update { if (it.tickBusy) it else it.copy(tickingId = null, tickErrorKey = null, tickDuplicate = null) }

    fun onPayAmount(amount: String) = update { it.copy(payAmount = amount, tickErrorKey = null, tickDuplicate = null) }

    fun onPayDate(date: LocalDate) = update { it.copy(payDate = date, tickErrorKey = null, tickDuplicate = null) }

    fun onPayAccount(id: String) = update { it.copy(payAccountId = id, tickErrorKey = null, tickDuplicate = null) }

    /** Tick off with a payment already recorded: nothing new is counted. */
    fun tickWith(transactionId: String) = tick { repos, month, id -> repos.budgets.tickOff(month, id, transactionId) }

    /** Tick off with the payment typed in: recorded once, then named as what paid it. */
    fun addPayment(allowDuplicate: Boolean = false) {
        val state = _uiState.value
        val budget = state.budget ?: return
        val payment = CommitmentTick.payment(state.payAmount, state.payDate, state.payAccountId, budget, allowDuplicate) ?: return
        tick(newPayment = true) { repos, month, id -> repos.budgets.tickOffWithNew(month, id, payment) }
    }

    /** "Use that payment": the one the server said was already there. */
    fun useDuplicate() {
        val id = _uiState.value.tickDuplicate?.id ?: return
        tickWith(id)
    }

    fun untick() = tick { repos, month, id -> repos.budgets.untick(month, id) }

    private fun tick(newPayment: Boolean = false, call: suspend (BudgetRepositories, String, String) -> Budget) {
        val repos = repositories ?: return
        val state = _uiState.value
        val id = state.tickingId ?: return
        if (state.tickBusy) return
        update { it.copy(tickBusy = true, tickErrorKey = null, tickDuplicate = null) }
        viewModelScope.launch {
            try {
                val budget = call(repos, state.month, id)
                update { it.copy(budget = budget, tickBusy = false, tickingId = null) }
                // A payment typed in is a new transaction: Home's figures move.
                if (newPayment) LedgerChanged.announce() else BudgetChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.DuplicateTransaction) {
                update { it.copy(tickBusy = false, tickDuplicate = e.match, tickErrorKey = e.messageKey) }
            } catch (e: ApiException) {
                update { it.copy(tickBusy = false, tickErrorKey = e.messageKey) }
            }
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

    /**
     * "Add to budget" on a category spent in with no line: an editor on a new
     * line, started at what was spent this month — the person's to change
     * before saving.
     */
    fun addFromUnbudgeted(categoryId: String) {
        val state = _uiState.value
        val spend = state.unbudgeted.firstOrNull { it.categoryId == categoryId } ?: return
        if (!BudgetEdit.isBudgetable(spend.slug)) return
        update {
            it.copy(
                picking = false,
                editing = BudgetEdit.lineFor(spend),
                editingIsNew = true,
                // The AI's amount when it suggested one; otherwise what was spent.
                draft = spend.aiSuggested?.let { amount -> BudgetDraft(amount) } ?: BudgetEdit.draftFor(spend, state.currency),
                editErrorKey = null,
            )
        }
    }

    /** "Set a savings target": an editor on a new savings line, its amount the person's to type. */
    fun setSavingsTarget() {
        val budget = _uiState.value.budget ?: return
        if (budget.savings != null) return
        val line = BudgetOverview.savingsTargetToSet(budget, _uiState.value.locale) ?: return
        update { it.copy(picking = false, editing = line, editingIsNew = true, draft = BudgetDraft(), editErrorKey = null) }
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
    fun save(thisMonthOnly: Boolean = false) {
        val repos = repositories ?: return
        val state = _uiState.value
        val line = state.editing ?: return
        if (state.editBlock != null || state.saving) return
        val amount = state.draft.amount
        update { it.copy(saving = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                val budget = repos.budgets.setLine(state.month, line.categoryId, amount, thisMonthOnly)
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
        /** Pages of the month's payments read for matching: enough for any month's bills. */
        private const val TICK_PAGES = 4
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
    /** For the spending check's consent; null where a test does not need it. */
    val consent: AiConsentRepository? = null,
    /** The month's payments, to tick a commitment off with; null where a test does not need it. */
    val transactions: TransactionsRepository? = null,
    val accounts: AccountsRepository? = null,
) {
    fun close() {
        budgets.close()
        categories.close()
        capabilities.close()
        consent?.close()
        transactions?.close()
        accounts?.close()
    }
}
