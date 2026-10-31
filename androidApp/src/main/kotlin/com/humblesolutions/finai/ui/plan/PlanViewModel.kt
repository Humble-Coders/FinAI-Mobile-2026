package com.humblesolutions.finai.ui.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorAccountsRepository
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorDebtsRepository
import com.humblesolutions.finai.data.KtorPlanRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.PlanCategory
import com.humblesolutions.finai.model.PlanRow
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.DebtsRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.DebtDraft
import com.humblesolutions.finai.usecase.DebtEdit
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.PlanWeightsDraft
import com.humblesolutions.finai.usecase.RowEditDraft
import com.humblesolutions.finai.usecase.SplitRow
import com.humblesolutions.finai.usecase.YourPlan
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.GoalsChanged
import com.humblesolutions.finai.util.PlanChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Your plan and Your debts, and every change to them (#76).
 *
 * One model for both screens: both read the same plan. After every write the
 * plan is read again — a change to one debt or one choice moves every figure,
 * and those are the server's to work out — except a row of the split set by
 * hand, whose answer is the whole plan already.
 *
 * What is being typed survives the process being reclaimed, through
 * [SavedStateHandle], with the owner kept there too so the [bind] that
 * follows a restore recognises the same person (as `GoalsViewModel` does).
 */
class PlanViewModel(
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restored())
    val uiState: StateFlow<PlanUiState> = _uiState.asStateFlow()

    private var repositories: PlanRepositories? = null

    private var boundTo: String?
        get() = saved[KEY_OWNER]
        set(value) {
            saved[KEY_OWNER] = value
        }

    /** Rises on every bind and every read, so only the latest answer lands. */
    private var generation = 0

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            PlanRepositories(
                plan = KtorPlanRepository(ApiConfig.BASE_URL, tokens, logging),
                debts = KtorDebtsRepository(ApiConfig.BASE_URL, tokens, logging),
                accounts = KtorAccountsRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> PlanRepositories?) {
        if (userId.isBlank()) return
        if (userId != boundTo) {
            repositories?.close()
            repositories = null
            generation++
            boundTo = userId
            _uiState.value = PlanUiState(page = _uiState.value.page)
            store(_uiState.value)
        }
        // Back on the plan with this person's clients: read it again under
        // what is shown — a budget or a goal may have moved it meanwhile.
        if (repositories != null) {
            if (_uiState.value.plan != null) load(refresh = true)
            return
        }
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /** Which screen opens first: Your debts when reached from Home's Debts screen. */
    fun showPage(page: PlanPage) = update { it.copy(page = page) }

    /**
     * The plan, the debts' details and the accounts, together. The accounts
     * only name a link; without them a debt shows no link name, nothing worse.
     */
    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = ++generation
        update { if (refresh) it.copy(refreshing = true) else it.copy(loading = true, loadFailed = false, errorKey = null) }
        viewModelScope.launch {
            try {
                val (capabilities, plan, debts, accounts) = coroutineScope {
                    val capabilities = async { quietly { repos.capabilities.fetch() } }
                    val plan = async { repos.plan.plan(null) }
                    val debts = async { repos.debts.list() }
                    val accounts = async { quietly { repos.accounts.list() } }
                    Quad(capabilities.await(), plan.await(), debts.await(), accounts.await())
                }
                if (started != generation) return@launch
                update {
                    it.copy(
                        plan = plan,
                        debts = debts,
                        accounts = accounts ?: it.accounts,
                        currency = capabilities?.currency?.takeIf(String::isNotBlank) ?: debts.currency.ifBlank { it.currency },
                        locale = capabilities?.locale?.takeIf(String::isNotBlank) ?: it.locale,
                        today = ManualEntry.today(),
                        loading = false,
                        refreshing = false,
                        loadFailed = false,
                        errorKey = null,
                    )
                }
                loadDisclaimer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // A refresh that fails keeps what it had (CLAUDE.md → Data & caching).
                        loadFailed = it.plan == null,
                        errorKey = e.messageKey,
                    )
                }
            }
        }
    }

    /** The disclaimer, once; failing quietly leaves the plan's own guidance line. */
    private fun loadDisclaimer() {
        val repos = repositories ?: return
        if (_uiState.value.disclaimer != null) return
        viewModelScope.launch {
            val terms = quietly { repos.plan.disclaimer() } ?: return@launch
            update { it.copy(disclaimer = terms) }
        }
    }

    /** After a write: read everything again, and tell Home and Goals. */
    private fun settled(goalsMoved: Boolean) {
        PlanChanged.announce()
        if (goalsMoved) GoalsChanged.announce()
        load(refresh = true)
    }

    // ── A row of the split, set by hand ─────────────────────────────────

    fun startRowEdit(row: SplitRow) = update {
        it.copy(rowDraft = YourPlan.editDraftOf(row), askingApply = false, rowErrorKey = null)
    }

    fun onRowAmount(amount: String) = update { state ->
        state.copy(rowDraft = state.rowDraft?.copy(amount = amount), rowErrorKey = null)
    }

    fun cancelRowEdit() = update { it.copy(rowDraft = null, askingApply = false, rowErrorKey = null) }

    /** Save: first ask whether the amount should also go where it belongs. */
    fun requestRowSave() {
        if (!_uiState.value.canSaveRow) return
        update { it.copy(askingApply = true) }
    }

    /** The alert put away without an answer: back to the amount. */
    fun dismissApply() = update { it.copy(askingApply = false) }

    /**
     * Sends the row; [apply] also puts it where it belongs — a goal's monthly
     * amount, the Budget's Debt line or its savings target. The answer is the
     * whole plan, rebalanced, so it replaces what is shown without a read.
     */
    fun saveRow(apply: Boolean) {
        val repos = repositories ?: return
        val state = _uiState.value
        val draft = state.rowDraft ?: return
        if (!state.canSaveRow) return
        update { it.copy(askingApply = false, savingRow = true, rowErrorKey = null) }
        viewModelScope.launch {
            try {
                val plan = repos.plan.editSplit(YourPlan.toEdit(draft, apply, state.moneyCurrency))
                // A read still in flight would put the plan from before back.
                ++generation
                update { it.copy(plan = plan, savingRow = false, rowDraft = null, refreshing = false) }
                PlanChanged.announce()
                if (apply) {
                    when (draft.row) {
                        PlanRow.EMERGENCY_FUND, PlanRow.GOAL -> GoalsChanged.announce()
                        PlanRow.DEBT, PlanRow.SAVE_OR_INVEST -> BudgetChanged.announce()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(savingRow = false, rowErrorKey = e.messageKey) }
            }
        }
    }

    // ── The choices ─────────────────────────────────────────────────────

    fun startChoices() {
        val plan = _uiState.value.plan ?: return
        update { it.copy(choosing = true, weightsDraft = YourPlan.weightsDraftOf(plan.preferences), choicesErrorKey = null) }
    }

    fun moveChoice(category: PlanCategory, up: Boolean) = onWeightsDraft { YourPlan.moved(it, category, up) }

    fun stepChoice(category: PlanCategory, up: Boolean) = onWeightsDraft { YourPlan.stepped(it, category, up) }

    fun onChoicePercent(category: PlanCategory, text: String) = onWeightsDraft { YourPlan.withPercent(it, category, text) }

    private fun onWeightsDraft(change: (PlanWeightsDraft) -> PlanWeightsDraft) = update {
        it.copy(weightsDraft = change(it.weightsDraft), choicesErrorKey = null)
    }

    fun cancelChoices() = update { it.copy(choosing = false, weightsDraft = PlanWeightsDraft(), choicesErrorKey = null) }

    /** Keeps the order and shares; the server works the split out again from them. */
    fun saveChoices() {
        val repos = repositories ?: return
        val state = _uiState.value
        val plan = state.plan ?: return
        if (!state.canSaveChoices) return
        update { it.copy(savingChoices = true, choicesErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.plan.savePreferences(YourPlan.toSave(state.weightsDraft, plan.preferences.strategy))
                update { it.copy(savingChoices = false, choosing = false, weightsDraft = PlanWeightsDraft()) }
                // Goals' suggested amounts follow the plan through PlanChanged.
                settled(goalsMoved = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(savingChoices = false, choicesErrorKey = e.messageKey) }
            }
        }
    }

    /** The Debts screen's switch: only the strategy changes, the choices are kept. */
    fun chooseStrategy(strategy: PlanStrategy) {
        val repos = repositories ?: return
        val state = _uiState.value
        val plan = state.plan ?: return
        if (state.switchingStrategy || plan.preferences.strategy == strategy) return
        update { it.copy(switchingStrategy = true, noticeKey = null) }
        viewModelScope.launch {
            try {
                repos.plan.savePreferences(YourPlan.strategyOnly(strategy))
                update { it.copy(switchingStrategy = false) }
                settled(goalsMoved = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(switchingStrategy = false, noticeKey = e.messageKey) }
            }
        }
    }

    // ── A debt's details ────────────────────────────────────────────────

    fun editDebt(debtId: String) {
        val debt = _uiState.value.record(debtId) ?: return
        update { it.copy(editingDebtId = debt.id, debtDraft = DebtEdit.draftOf(debt), debtErrorKey = null, today = ManualEntry.today()) }
    }

    fun onDebtDraft(draft: DebtDraft) = update { it.copy(debtDraft = draft, debtErrorKey = null) }

    fun cancelDebt() = update { it.copy(editingDebtId = null, debtDraft = DebtDraft(), debtErrorKey = null) }

    fun saveDebt() {
        val repos = repositories ?: return
        val state = _uiState.value
        val debt = state.editingDebt ?: return
        if (!state.canSaveDebt) return
        update { it.copy(savingDebt = true, debtErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.debts.update(debt.id, DebtEdit.changes(debt, state.debtDraft, state.moneyCurrency))
                update { it.copy(savingDebt = false, editingDebtId = null, debtDraft = DebtDraft()) }
                settled(goalsMoved = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(savingDebt = false, debtErrorKey = e.messageKey) }
            }
        }
    }

    // ── Update balance ──────────────────────────────────────────────────

    fun startBalance(debtId: String) = update { it.copy(balanceDebtId = debtId, balanceAmount = "", balanceErrorKey = null) }

    fun onBalanceAmount(amount: String) = update { it.copy(balanceAmount = amount, balanceErrorKey = null) }

    fun cancelBalance() = update { it.copy(balanceDebtId = null, balanceAmount = "", balanceErrorKey = null) }

    fun saveBalance() {
        val repos = repositories ?: return
        val state = _uiState.value
        val debt = state.balanceDebt ?: return
        if (!state.canSaveBalance) return
        update { it.copy(savingBalance = true, balanceErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.debts.update(debt.id, DebtEdit.balanceChange(state.balanceAmount, state.moneyCurrency))
                update { it.copy(savingBalance = false, balanceDebtId = null, balanceAmount = "") }
                settled(goalsMoved = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(savingBalance = false, balanceErrorKey = e.messageKey) }
            }
        }
    }

    // ── Saved state ─────────────────────────────────────────────────────

    /** The page and whatever was being typed — never the plan, which is the server's and read again. */
    private fun restored(): PlanUiState = PlanUiState(
        page = saved.get<String>(KEY_PAGE)?.let { runCatching { PlanPage.valueOf(it) }.getOrNull() } ?: PlanPage.PLAN,
        rowDraft = restoredRow(),
        askingApply = saved[KEY_ROW_ASKING] ?: false,
        choosing = saved[KEY_CHOOSING] ?: false,
        weightsDraft = restoredWeights(),
        editingDebtId = saved[KEY_DEBT_ID],
        debtDraft = DebtDraft(
            name = saved[KEY_DEBT_NAME] ?: "",
            kind = saved.get<String>(KEY_DEBT_KIND)?.let(DebtKind::fromWire)?.takeIf { it != DebtKind.UNKNOWN },
            minimumPayment = saved[KEY_DEBT_MINIMUM] ?: "",
            interestRatePercent = saved[KEY_DEBT_RATE] ?: "",
            promoRatePercent = saved[KEY_DEBT_PROMO_RATE] ?: "",
            promoEndsOn = saved[KEY_DEBT_PROMO_ENDS],
            accountId = saved[KEY_DEBT_ACCOUNT],
        ),
        balanceDebtId = saved[KEY_BALANCE_ID],
        balanceAmount = saved[KEY_BALANCE_AMOUNT] ?: "",
    )

    private fun restoredRow(): RowEditDraft? {
        val row = saved.get<String>(KEY_ROW)?.let { wire -> PlanRow.entries.firstOrNull { it.wire == wire } } ?: return null
        return RowEditDraft(
            row = row,
            goalId = saved[KEY_ROW_GOAL],
            label = saved[KEY_ROW_LABEL] ?: "",
            amount = saved[KEY_ROW_AMOUNT] ?: "",
            original = saved[KEY_ROW_ORIGINAL] ?: "",
        )
    }

    private fun restoredWeights(): PlanWeightsDraft {
        val categories = saved.get<ArrayList<String>>(KEY_WEIGHT_CATEGORIES).orEmpty()
            .map(PlanCategory::fromWire)
            .filter { it != PlanCategory.UNKNOWN }
        val percents = saved.get<ArrayList<String>>(KEY_WEIGHT_PERCENTS).orEmpty()
        return PlanWeightsDraft(
            categories = categories,
            percents = categories.mapIndexed { index, category -> category to percents.getOrElse(index) { "" } }.toMap(),
        )
    }

    private fun store(state: PlanUiState) {
        saved[KEY_PAGE] = state.page.name
        saved[KEY_ROW] = state.rowDraft?.row?.wire
        saved[KEY_ROW_GOAL] = state.rowDraft?.goalId
        saved[KEY_ROW_LABEL] = state.rowDraft?.label
        saved[KEY_ROW_AMOUNT] = state.rowDraft?.amount
        saved[KEY_ROW_ORIGINAL] = state.rowDraft?.original
        saved[KEY_ROW_ASKING] = state.askingApply
        saved[KEY_CHOOSING] = state.choosing
        val categories = state.weightsDraft.categories
        saved[KEY_WEIGHT_CATEGORIES] = ArrayList(categories.map { it.wire })
        saved[KEY_WEIGHT_PERCENTS] = ArrayList(categories.map { state.weightsDraft.percents[it].orEmpty() })
        saved[KEY_DEBT_ID] = state.editingDebtId
        saved[KEY_DEBT_NAME] = state.debtDraft.name
        saved[KEY_DEBT_KIND] = state.debtDraft.kind?.wire
        saved[KEY_DEBT_MINIMUM] = state.debtDraft.minimumPayment
        saved[KEY_DEBT_RATE] = state.debtDraft.interestRatePercent
        saved[KEY_DEBT_PROMO_RATE] = state.debtDraft.promoRatePercent
        saved[KEY_DEBT_PROMO_ENDS] = state.debtDraft.promoEndsOn
        saved[KEY_DEBT_ACCOUNT] = state.debtDraft.accountId
        saved[KEY_BALANCE_ID] = state.balanceDebtId
        saved[KEY_BALANCE_AMOUNT] = state.balanceAmount
    }

    private fun update(block: (PlanUiState) -> PlanUiState) {
        _uiState.update(block)
        store(_uiState.value)
    }

    private suspend fun <T> quietly(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    internal companion object {
        private const val KEY_OWNER = "plan.owner"
        private const val KEY_PAGE = "plan.page"
        private const val KEY_ROW = "plan.row.row"
        private const val KEY_ROW_GOAL = "plan.row.goal"
        private const val KEY_ROW_LABEL = "plan.row.label"
        private const val KEY_ROW_AMOUNT = "plan.row.amount"
        private const val KEY_ROW_ORIGINAL = "plan.row.original"
        private const val KEY_ROW_ASKING = "plan.row.asking"
        private const val KEY_CHOOSING = "plan.choices.open"
        private const val KEY_WEIGHT_CATEGORIES = "plan.choices.categories"
        private const val KEY_WEIGHT_PERCENTS = "plan.choices.percents"
        private const val KEY_DEBT_ID = "plan.debt.id"
        private const val KEY_DEBT_NAME = "plan.debt.name"
        private const val KEY_DEBT_KIND = "plan.debt.kind"
        private const val KEY_DEBT_MINIMUM = "plan.debt.minimum"
        private const val KEY_DEBT_RATE = "plan.debt.rate"
        private const val KEY_DEBT_PROMO_RATE = "plan.debt.promoRate"
        private const val KEY_DEBT_PROMO_ENDS = "plan.debt.promoEnds"
        private const val KEY_DEBT_ACCOUNT = "plan.debt.account"
        private const val KEY_BALANCE_ID = "plan.balance.id"
        private const val KEY_BALANCE_AMOUNT = "plan.balance.amount"
    }
}

/** What these screens talk to. Closed together when the model is cleared. */
internal class PlanRepositories(
    val plan: PlanRepository,
    val debts: DebtsRepository,
    val accounts: AccountsRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        plan.close()
        debts.close()
        accounts.close()
        capabilities.close()
    }
}
