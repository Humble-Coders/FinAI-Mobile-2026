package com.humblesolutions.finai.ui.budget

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.usecase.BudgetBlock
import com.humblesolutions.finai.usecase.BudgetDraft
import com.humblesolutions.finai.usecase.BudgetEdit
import com.humblesolutions.finai.usecase.CategoryIcon
import com.humblesolutions.finai.usecase.CategoryIcons
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

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
    val loadFailed: Boolean = false,
    val errorKey: String? = null,

    /** False when capabilities do not enable `auto_budget`; the tab is then not drawn. */
    val available: Boolean = true,

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

    /** The spending lines, over budget first (see [BudgetEdit.ordered]). */
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

    /** The months the selector offers, newest first. */
    val months: List<String> get() = BudgetEdit.months(month)

    fun monthOption(key: String): String = DashboardMonths.parse(key)?.let {
        text(Strings.dashboard_month_display, Dates.monthShort(it, locale), it.year.toString())
    } ?: key

    /** "Spent $380 of $1,400" across the whole month. */
    val totalsLabel: String
        get() = budget?.let { text(Strings.budget_spent_of, money(it.totalSpent), money(it.totalAllocated)) }.orEmpty()

    /** How far through the whole budget the month's spending is — a bar, not a figure. */
    val totalFraction: Float
        get() {
            val b = budget ?: return 0f
            if (!Money.isPositive(b.totalAllocated, digits)) return 0f
            val spent = Money.normalize(b.totalSpent, digits)?.toDoubleOrNull() ?: return 0f
            val allocated = Money.normalize(b.totalAllocated, digits)?.toDoubleOrNull() ?: return 0f
            if (allocated <= 0.0) return 0f
            return (spent / allocated).coerceIn(0.0, 1.0).toFloat()
        }

    val expectedIncomeLabel: String get() = budget?.let { money(it.expectedIncome) }.orEmpty()

    /** "Set aside $900", or null when there is no savings line. */
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

    /** "Use suggestion ($440)", shown only for a line the person has overridden. */
    val useSuggestionLabel: String?
        get() {
            val line = editing ?: return null
            if (editingIsNew || !line.isUserSet) return null
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
