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
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.DebtsRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.DebtDraft
import com.humblesolutions.finai.usecase.DebtEdit
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.PlanChoicesDraft
import com.humblesolutions.finai.usecase.YourPlan
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
 * One model for both screens: the strategy switch on Your debts may need the
 * choices editor of Your plan (see [chooseStrategy]), and both read the same
 * plan. After every write the plan is read again — a change to one debt or
 * one choice moves every figure, and those are the server's to work out.
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

    // ── The choices ─────────────────────────────────────────────────────

    fun startChoices() {
        val plan = _uiState.value.plan ?: return
        update { it.copy(choosing = true, choicesDraft = YourPlan.draftOf(plan.preferences), choicesErrorKey = null) }
    }

    fun onChoicesDraft(draft: PlanChoicesDraft) = update { it.copy(choicesDraft = draft, choicesErrorKey = null) }

    fun cancelChoices() = update { it.copy(choosing = false, choicesDraft = PlanChoicesDraft(), choicesErrorKey = null) }

    fun saveChoices() {
        val repos = repositories ?: return
        val state = _uiState.value
        if (!state.canSaveChoices) return
        update { it.copy(savingChoices = true, choicesErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.plan.savePreferences(YourPlan.toSave(state.choicesDraft, state.moneyCurrency))
                update { it.copy(savingChoices = false, choosing = false, choicesDraft = PlanChoicesDraft()) }
                // A buffer is a goal: it may have just been made or changed.
                settled(goalsMoved = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(savingChoices = false, choicesErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * The Debts screen's switch. Once the choices are saved, only the
     * strategy changes. Before that, the first save would also keep the
     * suggested buffer as a goal — so the editor opens with the strategy
     * chosen, and the person confirms the rest there (ticket #76: the goal is
     * made only when they confirm).
     */
    fun chooseStrategy(strategy: PlanStrategy) {
        val repos = repositories ?: return
        val state = _uiState.value
        val plan = state.plan ?: return
        if (state.switchingStrategy || plan.preferences.strategy == strategy) return
        val choices = YourPlan.strategyOnly(plan.preferences, strategy)
        if (choices == null) {
            update { it.copy(choosing = true, choicesDraft = YourPlan.draftOf(plan.preferences).copy(strategy = strategy), choicesErrorKey = null) }
            return
        }
        update { it.copy(switchingStrategy = true, noticeKey = null) }
        viewModelScope.launch {
            try {
                repos.plan.savePreferences(choices)
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
        choosing = saved[KEY_CHOOSING] ?: false,
        choicesDraft = PlanChoicesDraft(
            hasBuffer = saved[KEY_HAS_BUFFER] ?: true,
            buffer = saved[KEY_BUFFER] ?: "",
            goalSharePercent = saved[KEY_GOAL_SHARE] ?: "",
            rateLinePercent = saved[KEY_RATE_LINE] ?: "",
            strategy = PlanStrategy.fromWire(saved[KEY_STRATEGY]),
        ),
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

    private fun store(state: PlanUiState) {
        saved[KEY_PAGE] = state.page.name
        saved[KEY_CHOOSING] = state.choosing
        saved[KEY_HAS_BUFFER] = state.choicesDraft.hasBuffer
        saved[KEY_BUFFER] = state.choicesDraft.buffer
        saved[KEY_GOAL_SHARE] = state.choicesDraft.goalSharePercent
        saved[KEY_RATE_LINE] = state.choicesDraft.rateLinePercent
        saved[KEY_STRATEGY] = state.choicesDraft.strategy.wire
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
        private const val KEY_CHOOSING = "plan.choices.open"
        private const val KEY_HAS_BUFFER = "plan.choices.hasBuffer"
        private const val KEY_BUFFER = "plan.choices.buffer"
        private const val KEY_GOAL_SHARE = "plan.choices.goalShare"
        private const val KEY_RATE_LINE = "plan.choices.rateLine"
        private const val KEY_STRATEGY = "plan.choices.strategy"
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
