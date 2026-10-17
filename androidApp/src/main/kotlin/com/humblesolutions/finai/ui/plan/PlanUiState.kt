package com.humblesolutions.finai.ui.plan

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.DebtsPage
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.usecase.BalanceBlock
import com.humblesolutions.finai.usecase.ChoiceRow
import com.humblesolutions.finai.usecase.DebtBlock
import com.humblesolutions.finai.usecase.DebtDraft
import com.humblesolutions.finai.usecase.DebtEdit
import com.humblesolutions.finai.usecase.DebtRow
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.PlanChoiceBlock
import com.humblesolutions.finai.usecase.PlanChoicesDraft
import com.humblesolutions.finai.usecase.SplitRow
import com.humblesolutions.finai.usecase.YourPlan
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** Which of the plan's two screens is showing. */
enum class PlanPage { PLAN, DEBTS }

/**
 * Your plan and Your debts as the screens read them (#76).
 *
 * Every figure is the server's; every sentence is [YourPlan]'s or
 * [DebtEdit]'s, so this file only decides what is shown and whether a button
 * is on.
 */
data class PlanUiState(
    val page: PlanPage = PlanPage.PLAN,
    val plan: Plan? = null,
    /** The debts' own details — source, promotion, link — beside the plan's figures for them. */
    val debts: DebtsPage? = null,
    /** The household's accounts, for linking a debt to its card or loan. */
    val accounts: List<Account> = emptyList(),
    val disclaimer: Terms? = null,
    val currency: String = "",
    val locale: String = "en",
    val today: LocalDate = ManualEntry.today(),

    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadFailed: Boolean = false,
    val errorKey: String? = null,
    /** Why an action that is not a sheet's was refused — the strategy switch. */
    val noticeKey: String? = null,

    // ── The choices ─────────────────────────────────────────────────────
    val choosing: Boolean = false,
    val choicesDraft: PlanChoicesDraft = PlanChoicesDraft(),
    val savingChoices: Boolean = false,
    val choicesErrorKey: String? = null,
    val switchingStrategy: Boolean = false,

    // ── A debt's details ────────────────────────────────────────────────
    val editingDebtId: String? = null,
    val debtDraft: DebtDraft = DebtDraft(),
    val savingDebt: Boolean = false,
    val debtErrorKey: String? = null,

    // ── Update balance ──────────────────────────────────────────────────
    val balanceDebtId: String? = null,
    val balanceAmount: String = "",
    val savingBalance: Boolean = false,
    val balanceErrorKey: String? = null,
) {
    /** The currency the figures are in: the plan's, else the capabilities'. */
    val moneyCurrency: String get() = plan?.currency?.takeIf(String::isNotBlank) ?: currency

    val words: YourPlan.Words get() = YourPlan.Words(currency = moneyCurrency, locale = locale)

    val currencySymbol: String get() = Money.symbol(moneyCurrency)

    val amountPlaceholder: String get() = Money.normalize("0", Money.fractionDigits(moneyCurrency)).orEmpty()

    // ── Your plan ───────────────────────────────────────────────────────

    val headline: String? get() = plan?.let { YourPlan.headline(it, words) }

    val showsSplit: Boolean get() = plan?.let { YourPlan.hasSplit(it, moneyCurrency) } == true

    val splitRows: List<SplitRow> get() = plan?.let { YourPlan.splitRows(it, words) }.orEmpty()

    val choiceRows: List<ChoiceRow> get() = plan?.let { YourPlan.choiceRows(it.preferences, words) }.orEmpty()

    /** The region's disclaimer under the plan, when it has one. */
    val disclaimerText: String? get() = disclaimer?.body?.takeIf { it.isNotBlank() }

    // ── Your debts ──────────────────────────────────────────────────────

    val debtRows: List<DebtRow> get() = plan?.let { YourPlan.debtRows(it, words) }.orEmpty()

    val showsNoDebts: Boolean get() = plan != null && plan.debts.isEmpty()

    val debtFreeLine: String? get() = plan?.let { YourPlan.debtFreeLine(it, words) }

    val totalInterestLine: String? get() = plan?.let { YourPlan.totalInterestLine(it, words) }

    val interestSavedLine: String? get() = plan?.let { YourPlan.interestSavedLine(it, words) }

    val comparisonLine: String? get() = plan?.let { YourPlan.comparisonLine(it, words) }

    fun record(debtId: String?): DebtRecord? = debtId?.let { id -> debts?.debts?.firstOrNull { it.id == id } }

    fun sourceLineOf(debtId: String): String? = record(debtId)?.let { DebtEdit.sourceLine(it, locale) }

    fun staleLineOf(debtId: String): String? = record(debtId)?.let { DebtEdit.staleLine(it, locale) }

    fun promotionLineOf(debtId: String): String? = record(debtId)?.let { DebtEdit.promotionLine(it, locale) }

    fun linkedLineOf(debtId: String): String? = record(debtId)?.let { DebtEdit.linkedAccountName(it, accounts) }

    val linkableAccounts: List<Account> get() = DebtEdit.linkable(accounts)

    // ── The choices ─────────────────────────────────────────────────────

    val choicesBlock: PlanChoiceBlock?
        get() = plan?.let { YourPlan.blockingReason(it.preferences, choicesDraft, moneyCurrency) }

    /** "Nothing has changed" is not worth saying before anything is touched. */
    val choicesNotice: PlanChoiceBlock? get() = choicesBlock?.takeIf { it != PlanChoiceBlock.NOTHING_CHANGED }

    val canSaveChoices: Boolean get() = !savingChoices && plan != null && choicesBlock == null

    val goalNote: String? get() = plan?.let { YourPlan.bufferGoalNote(it.preferences, choicesDraft, locale) }

    // ── A debt's details ────────────────────────────────────────────────

    val editingDebt: DebtRecord? get() = record(editingDebtId)

    val debtBlock: DebtBlock? get() = editingDebt?.let { DebtEdit.blockingReason(it, debtDraft, moneyCurrency) }

    val debtNotice: DebtBlock? get() = debtBlock?.takeIf { it != DebtBlock.NOTHING_CHANGED }

    val canSaveDebt: Boolean get() = !savingDebt && editingDebt != null && debtBlock == null

    // ── Update balance ──────────────────────────────────────────────────

    val balanceDebt: DebtRecord? get() = record(balanceDebtId)

    val balanceBlock: BalanceBlock? get() = balanceDebt?.let { DebtEdit.blockingReasonForBalance(it, balanceAmount, moneyCurrency) }

    /** Blank and unchanged are not worth calling out before anything is typed. */
    val balanceNotice: BalanceBlock? get() = balanceBlock?.takeIf { it != BalanceBlock.NO_BALANCE && it != BalanceBlock.UNCHANGED }

    val canSaveBalance: Boolean get() = !savingBalance && balanceDebt != null && balanceBlock == null
}
