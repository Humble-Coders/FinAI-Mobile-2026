package com.humblesolutions.finai.ui.budget

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AiLine
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetCommitment
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.SpendingCheck
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.UnbudgetedSpend
import com.humblesolutions.finai.usecase.AiBudgetEdit
import com.humblesolutions.finai.usecase.BoardDonut
import com.humblesolutions.finai.usecase.BoardForecast
import com.humblesolutions.finai.usecase.BoardSoFar
import com.humblesolutions.finai.usecase.BudgetBlock
import com.humblesolutions.finai.usecase.BudgetBoard
import com.humblesolutions.finai.usecase.BudgetDraft
import com.humblesolutions.finai.usecase.BudgetEdit
import com.humblesolutions.finai.usecase.BudgetOverview
import com.humblesolutions.finai.usecase.CategoryIcon
import com.humblesolutions.finai.usecase.CategoryIcons
import com.humblesolutions.finai.usecase.CommitmentTick
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.NextStep
import com.humblesolutions.finai.usecase.OverviewCommitment
import com.humblesolutions.finai.usecase.OverviewRow
import com.humblesolutions.finai.usecase.OverviewSpend
import com.humblesolutions.finai.usecase.SpendingCheckWords
import com.humblesolutions.finai.usecase.SpendingShareRow
import com.humblesolutions.finai.usecase.TickBlock
import com.humblesolutions.finai.usecase.TickCandidate
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * A month's budget as the screen reads it (#47, PRD F4).
 *
 * Every amount below is formatted from a figure the server sent. Nothing
 * here adds or subtracts one: the ordering, the bar fractions and the
 * wording are this file's, the arithmetic is not.
 */
data class BudgetUiState(
    val month: String = DashboardMonths.wire(DashboardMonths.current()),
    val budget: Budget? = null,
    val categories: List<Category> = emptyList(),
    val locale: String = "en",

    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /**
     * A different month is being read. Raises the coin loader, unlike a
     * background refresh after an import: the figures on screen belong to the
     * month just left, and showing them under the new month's name would
     * mislabel them.
     */
    val switchingMonth: Boolean = false,
    /** The month's spending against income, and its last AI check; null until read, or without an income. */
    val spendingCheck: SpendingCheck? = null,
    val checkingSpending: Boolean = false,
    val spendingErrorKey: String? = null,
    /** The AI may not be asked yet: the policy is shown first. */
    val spendingPolicy: AiPolicy? = null,
    val spendingConsentTicked: Boolean = false,
    val spendingConsentBusy: Boolean = false,
    /** The commitment being ticked off by hand, by its setup id; null when the sheet is closed. */
    val tickingId: String? = null,
    /** The month's payments out, for matching; null while they load. */
    val tickRows: List<Transaction>? = null,
    val accounts: List<Account> = emptyList(),
    val payAmount: String = "",
    val payDate: LocalDate? = null,
    val payAccountId: String? = null,
    val tickBusy: Boolean = false,
    val tickErrorKey: String? = null,
    /** A typed-in payment the server already has: offered to tick off with instead. */
    val tickDuplicate: DuplicateMatch? = null,
    val loadFailed: Boolean = false,
    val errorKey: String? = null,

    // ── Editing one line ────────────────────────────────────────────────
    /** The line being edited; null when the sheet is closed. */
    val editing: BudgetLine? = null,
    /** True when [editing] is a category that had no line until now. */
    val editingIsNew: Boolean = false,
    val draft: BudgetDraft = BudgetDraft(),
    val saving: Boolean = false,
    val resetting: Boolean = false,
    val editErrorKey: String? = null,

    /** True while the category picker is open. */
    val picking: Boolean = false,
) {
    val currency: String get() = budget?.currency.orEmpty()

    private val digits: Int get() = Money.fractionDigits(currency)

    private fun text(key: String, vararg args: String): String = if (args.isEmpty()) {
        LocalizationRegistry.get(key, locale)
    } else {
        LocalizationRegistry.format(key, args.toList(), locale)
    }

    private fun money(amount: String): String = Money.format(amount, currency, locale)

    // ── What the screen shows ───────────────────────────────────────────

    /** True once the server has enough history to have generated anything. */
    val isReady: Boolean get() = budget?.isReady == true

    /**
     * Shown whenever the server says so — including over a budget that has
     * hand-set lines in it, which is the normal state for someone budgeting
     * by hand before their first full month (PRD F9).
     */
    val learning get() = budget?.learning

    /** The spending lines: over budget first, then closest to their allocation — Home's order too (see [BudgetEdit.ordered]). */
    val lines: List<BudgetLine>
        get() = budget?.let { BudgetEdit.ordered(it.lines, it.currency) }.orEmpty()

    /** The savings allocation, listed apart from the spending lines. */
    val savings: BudgetLine? get() = budget?.savings

    /** The debt allocation, listed last. */
    val debt: BudgetLine? get() = budget?.debt

    /**
     * True when there is nothing at all to show but the person could still
     * add a line by hand. Distinct from learning, which has its own card.
     */
    val showsEmpty: Boolean
        get() = !loading && !loadFailed && budget != null && lines.isEmpty() && savings == null && debt == null

    val monthLabel: String
        get() = DashboardMonths.parse(month)?.let {
            text(Strings.dashboard_month_display, Dates.monthShort(it, locale), it.year.toString())
        } ?: month

    /**
     * "Spent $380 of $1,400" across the whole month — everything spent,
     * including categories with no line, against what the lines allocate.
     */
    val totalsLabel: String
        get() = budget?.let { text(Strings.budget_spent_of, money(it.spentTotal), money(it.totalAllocated)) }.orEmpty()

    /** How far through the whole budget the month's spending is — a bar, not a figure. */
    val totalFraction: Float
        get() {
            val b = budget ?: return 0f
            if (!Money.isPositive(b.totalAllocated, digits)) return 0f
            val spent = Money.normalize(b.spentTotal, digits)?.toDoubleOrNull() ?: return 0f
            val allocated = Money.normalize(b.totalAllocated, digits)?.toDoubleOrNull() ?: return 0f
            if (allocated <= 0.0) return 0f
            return (spent / allocated).coerceIn(0.0, 1.0).toFloat()
        }

    val expectedIncomeLabel: String get() = budget?.let { money(it.expectedIncome) }.orEmpty()

    // ── The month above the lines (BudgetOverview) ──────────────────────

    /** A month still running shows its plan; one that has ended shows what happened. */
    val showsPlan: Boolean get() = budget?.let(BudgetOverview::showsPlan) ?: true

    val soFarTitle: String get() = budget?.let { BudgetOverview.soFarTitle(it, locale) }.orEmpty()

    /** Income, spent and what is left; null from a server that predates them. */
    val soFar: List<OverviewRow>? get() = budget?.let { BudgetOverview.soFar(it, locale) }

    val commitments: List<OverviewCommitment> get() = budget?.let { BudgetOverview.commitments(it, locale) }.orEmpty()

    /** What is still to come this month; null once it has ended. */
    val stillExpected: List<OverviewRow>? get() = budget?.let { BudgetOverview.stillExpected(it, locale) }

    /** "Put it toward your goals or into savings", or what went short. */
    val suggestion: String? get() = budget?.let { BudgetOverview.suggestion(it, locale) }

    val suggestsUsing: Boolean get() = budget?.let(BudgetOverview::suggestsUsing) ?: false

    /** A month that has ended, category by category. */
    val spentRows: List<OverviewSpend> get() = budget?.let { BudgetOverview.spent(it, locale) }.orEmpty()

    fun iconOf(spend: OverviewSpend): CategoryIcon = CategoryIcons.forSlug(spend.slug)

    // ── The AI's suggestions (ai_budget) ────────────────────────────────

    /** "AI suggests $430.00", when the AI's number for the line differs from it. */
    fun aiLabel(line: BudgetLine): String? = AiBudgetEdit.suggestionLabel(line, currency, locale)

    /** The month's AI review advice, shown while the month runs. */
    val aiAdvice: String? get() = budget?.takeIf { showsPlan }?.aiAdvice

    /** Categories the AI suggests a line for that have none yet. */
    val aiExtras: List<AiLine> get() = budget?.takeIf { showsPlan }?.aiExtra.orEmpty()

    fun amountOf(extra: AiLine): String = money(extra.amount)

    /** "AI suggests $60.00" for a category spent in with no line. */
    fun aiLabel(spend: UnbudgetedSpend): String? = spend.aiSuggested?.let { text(Strings.ai_budget_suggests, money(it)) }

    // ── How you're spending, one-offs, this month only ──────────────────

    // ── The board ───────────────────────────────────────────────────────

    val boardSoFar: BoardSoFar? get() = budget?.let { BudgetBoard.soFar(it, locale) }

    val boardForecast: BoardForecast? get() = budget?.let { BudgetBoard.forecast(it, locale) }

    val boardDonut: BoardDonut? get() = budget?.let { BudgetBoard.donut(it, locale) }

    fun nextSteps(planAvailable: Boolean): List<NextStep> = budget?.let { BudgetBoard.nextSteps(it, planAvailable, locale) }.orEmpty()

    fun allottedOf(line: BudgetLine): String = budget?.let { BudgetBoard.allotted(line, it, locale) }.orEmpty()

    fun colorIndexOf(line: BudgetLine): Int = budget?.let { BudgetBoard.colorIndexOf(line, it) } ?: (BudgetBoard.PALETTE_SIZE - 1)

    fun shareOfSpending(index: Int): String = budget?.let { BudgetBoard.shareOfSpending(index, it, locale) }.orEmpty()

    // ── Ticking a commitment off ────────────────────────────────────────

    val ticking: BudgetCommitment? get() = tickingId?.let { id -> budget?.commitments?.firstOrNull { it.id == id } }

    val tickTitle: String get() = ticking?.let { CommitmentTick.title(it, locale) }.orEmpty()

    val tickSubtitle: String get() = ticking?.let { c -> budget?.let { CommitmentTick.subtitle(c, it, locale) } }.orEmpty()

    val tickCandidates: List<TickCandidate>
        get() {
            val commitment = ticking ?: return emptyList()
            val budget = budget ?: return emptyList()
            return CommitmentTick.candidates(tickRows.orEmpty(), commitment, budget, locale)
        }

    val payBlock: TickBlock? get() = budget?.let { CommitmentTick.blockingReason(payAmount, payDate, payAccountId, it) }

    val canPay: Boolean get() = !tickBusy && payBlock == null

    /** Whether a date may be picked: only days in the budget's month. */
    fun payDateAllowed(date: LocalDate): Boolean = budget?.let { CommitmentTick.inMonth(date, it) } ?: false

    /** The AI card's words: the spending check's headline, else the monthly review's. */
    val insightHeadline: String? get() = spendingCheck?.headline ?: aiAdvice

    val insightDetail: String? get() = spendingCheck?.tips?.firstOrNull()?.takeIf { spendingCheck?.headline != null }

    /** Whether the AI card is drawn: there is a check to open, or a review to read. */
    val showsInsight: Boolean get() = spendingCheck != null || aiAdvice != null

    val spendingRows: List<SpendingShareRow> get() = spendingCheck?.let { SpendingCheckWords.rows(it, locale) }.orEmpty()

    /** The AI's tip for a category spent in this month, shown under its line. */
    fun tipFor(categoryId: String): String? = SpendingCheckWords.tipFor(spendingCheck, categoryId, locale)

    fun oneOffOf(line: BudgetLine): String? = SpendingCheckWords.oneOff(line.oneOff, currency, locale)

    fun oneOffOf(spend: UnbudgetedSpend): String? = SpendingCheckWords.oneOff(spend.oneOff, currency, locale)

    fun thisMonthOnlyOf(line: BudgetLine): String? = SpendingCheckWords.thisMonthOnly(line, locale)

    /** Whether the editor asks "this month only, or every month". */
    val asksHowLong: Boolean get() = SpendingCheckWords.asksHowLong(editing)

    /** "Ask AI for a budget", until this month has a review. */
    val canAskAi: Boolean get() = budget != null && showsPlan && budget.aiKind != "review"

    /** Spending in categories with no line, largest first. */
    val unbudgeted: List<UnbudgetedSpend> get() = budget?.unbudgeted.orEmpty()

    /** "$2,357.37 spent this month in categories with no line…", or null. */
    val unbudgetedLabel: String? get() = budget?.let { BudgetEdit.unbudgetedLine(it, locale) }

    fun spentOf(spend: UnbudgetedSpend): String = money(spend.spent)

    fun iconOf(spend: UnbudgetedSpend): CategoryIcon = CategoryIcons.forSlug(spend.slug)

    /** "Up to $1,562.00 is left after your other lines.", shown until a savings target is set. */
    val savingsRoomLabel: String? get() = budget?.takeIf { it.savings == null }?.let { BudgetOverview.savingsRoom(it, locale) }

    /** Whether "Set a savings target" can be offered: there is no target yet, and the server names the category. */
    val canSetSavingsTarget: Boolean get() = budget?.let { it.savings == null && it.savingsCategoryId != null } ?: false

    /** "Savings target $900", or null when no target is set. */
    val savingsLabel: String? get() = savings?.let { text(Strings.budget_set_aside, money(it.allocated)) }

    /** "Your budget is $200 more than your income", or null when it is not. */
    val shortfallLabel: String? get() = budget?.shortfall?.let { text(Strings.budget_shortfall, money(it)) }

    /** "12 transactions aren't filed yet…", or null when everything is filed. */
    val unfiledLabel: String?
        get() {
            val b = budget ?: return null
            if (!b.hasUncategorised) return null
            return if (b.uncategorisedCount == 1) {
                text(Strings.budget_unfiled_one)
            } else {
                text(Strings.budget_unfiled, b.uncategorisedCount.toString())
            }
        }

    // ── One line ────────────────────────────────────────────────────────

    fun nameOf(line: BudgetLine): String = line.name

    /** "$380 of $440". */
    fun amountsOf(line: BudgetLine): String = text(Strings.budget_line_amounts, money(line.spent), money(line.allocated))

    fun fractionOf(line: BudgetLine): Float = line.fraction(digits).toFloat()

    fun allocatedOf(line: BudgetLine): String = money(line.allocated)

    /** Rows waiting in the review queue: the bell's dot. */
    val unfiledCount: Int get() = budget?.uncategorisedCount ?: 0

    fun isOver(line: BudgetLine): Boolean = line.isOver(digits)

    /** "Over by $40", or null when the line is within its allocation. */
    fun overLabel(line: BudgetLine): String? = line.overBy(digits)?.let { text(Strings.budget_over_by, money(it)) }

    /** "Suggested $440" under a line the person set, or null. */
    fun suggestionLabel(line: BudgetLine): String? = if (line.differsFromSuggestion(digits)) text(Strings.budget_suggested, money(line.suggested)) else null

    fun iconOf(line: BudgetLine): CategoryIcon = CategoryIcons.forSlug(line.slug)

    /**
     * One line as a sentence, so a screen reader hears it once — and hears
     * "over by" in words. Colour is never the only thing saying it.
     */
    fun descriptionOf(line: BudgetLine): String {
        val over = line.overBy(digits)
        return if (over == null) {
            text(Strings.budget_line_a11y, nameOf(line), money(line.spent), money(line.allocated))
        } else {
            text(Strings.budget_line_a11y_over, nameOf(line), money(line.spent), money(line.allocated), money(over))
        }
    }

    // ── The editor ──────────────────────────────────────────────────────

    /**
     * Why Save is disabled, or null when it is not.
     *
     * The one shared function, read here for the button and the notice and
     * by the view model for the refusal (CLAUDE.md → Blocking reasons).
     */
    val editBlock: BudgetBlock?
        get() {
            val line = editing ?: return null
            return if (editingIsNew) {
                BudgetEdit.blockingReasonForNew(draft, currency)
            } else {
                BudgetEdit.blockingReason(line, draft, currency)
            }
        }

    /** "Nothing has changed" is not worth saying before the person has typed. */
    val editNotice: BudgetBlock? get() = editBlock?.takeIf { it != BudgetBlock.NOTHING_CHANGED }

    val canSave: Boolean get() = !saving && !resetting && editBlock == null

    /**
     * "Use suggestion ($440)", shown only for a line the person has
     * overridden — or "Remove target" on the savings target, which has no
     * suggestion to go back to.
     */
    val useSuggestionLabel: String?
        get() {
            val line = editing ?: return null
            if (editingIsNew || !line.isUserSet) return null
            if (line.slug == SAVINGS_SLUG) return text(Strings.budget_remove_target)
            return text(Strings.budget_edit_use_suggestion, money(line.suggested))
        }

    val currencySymbol: String get() = Money.symbol(currency)

    // ── The picker ──────────────────────────────────────────────────────

    /**
     * The categories a line can still be added for: everything the household
     * can see, less `income` and `transfers`, less the ones already on the
     * budget. Sorted with the household's own first, as the correction
     * picker does.
     */
    val pickable: List<Category>
        get() {
            val taken = budget?.allLines?.map { it.categoryId }?.toSet().orEmpty()
            return categories
                .filter { BudgetEdit.isBudgetable(it.slug) && it.id !in taken }
                .sortedWith(compareBy({ it.isSystem }, { it.name.lowercase() }))
        }

    fun iconOf(category: Category): CategoryIcon = CategoryIcons.forSlug(category.slug)
}

private const val SAVINGS_SLUG = "savings"
