package com.humblesolutions.finai.ui.plan

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.DebtsPage
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.usecase.ApplyQuestion
import com.humblesolutions.finai.usecase.BalanceBlock
import com.humblesolutions.finai.usecase.DebtBlock
import com.humblesolutions.finai.usecase.DebtDraft
import com.humblesolutions.finai.usecase.DebtEdit
import com.humblesolutions.finai.usecase.DebtRow
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.PlanChoiceBlock
import com.humblesolutions.finai.usecase.PlanWeightsDraft
import com.humblesolutions.finai.usecase.RowEditBlock
import com.humblesolutions.finai.usecase.RowEditDraft
import com.humblesolutions.finai.usecase.SplitNote
import com.humblesolutions.finai.usecase.SplitRow
import com.humblesolutions.finai.usecase.SurplusCard
import com.humblesolutions.finai.usecase.WeightRow
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

    // ── A row of the split, set by hand ─────────────────────────────────
    /** The row being changed; null while the editor is closed. */
    val rowDraft: RowEditDraft? = null,
    /** Save was tapped: the alert asks whether to put the amount where it belongs too. */
    val askingApply: Boolean = false,
    val savingRow: Boolean = false,
    val rowErrorKey: String? = null,

    // ── The choices ─────────────────────────────────────────────────────
    val choosing: Boolean = false,
    val weightsDraft: PlanWeightsDraft = PlanWeightsDraft(),
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

    val surplus: SurplusCard? get() = plan?.let { YourPlan.surplusCard(it, words) }

    val showsSplit: Boolean get() = plan?.let { YourPlan.hasSplit(it, moneyCurrency) } == true

    val splitRows: List<SplitRow> get() = plan?.let { YourPlan.splitRows(it, words) }.orEmpty()

    val splitNote: SplitNote? get() = plan?.let { YourPlan.splitNote(it, words) }

    val weightRows: List<WeightRow> get() = plan?.let { YourPlan.weightRows(it.preferences, words) }.orEmpty()

    /** "Add an emergency fund" (title, body), while the household has none. */
    val emergencySuggestion: Pair<String, String>? get() = plan?.let { YourPlan.emergencySuggestion(it.preferences, words) }

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

    // ── A row of the split, set by hand ─────────────────────────────────

    val rowBlock: RowEditBlock? get() = plan?.let { p -> rowDraft?.let { YourPlan.editBlockingReason(p, it, moneyCurrency) } }

    /** "That's the amount already there" is not worth saying before anything is typed. */
    val rowNotice: String?
        get() = rowBlock?.takeIf { it != RowEditBlock.UNCHANGED }?.let { block ->
            plan?.let { p -> rowDraft?.let { YourPlan.editBlockText(block, p, it, words) } }
        }

    val rowLimitLine: String? get() = plan?.let { p -> rowDraft?.let { YourPlan.editLimitLine(p, it, words) } }

    val rowRebalanceLine: String? get() = plan?.let { YourPlan.editRebalanceLine(it, words) }

    val canSaveRow: Boolean get() = !savingRow && rowDraft != null && rowBlock == null

    val applyQuestion: ApplyQuestion? get() = rowDraft?.takeIf { askingApply }?.let { YourPlan.applyQuestion(it, words) }

    // ── The choices ─────────────────────────────────────────────────────

    val choicesBlock: PlanChoiceBlock?
        get() = plan?.let { YourPlan.blockingReason(it.preferences, weightsDraft) }

    /** "Nothing has changed" is not worth saying before anything is touched. */
    val choicesNotice: String?
        get() = choicesBlock?.takeIf { it != PlanChoiceBlock.NOTHING_CHANGED }?.let { YourPlan.blockText(it, weightsDraft, locale) }

    /** "Total: 95%", or null while a share is not a percentage. */
    val choicesTotalLine: String? get() = YourPlan.totalLine(weightsDraft, words)

    val canSaveChoices: Boolean get() = !savingChoices && plan != null && choicesBlock == null

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
