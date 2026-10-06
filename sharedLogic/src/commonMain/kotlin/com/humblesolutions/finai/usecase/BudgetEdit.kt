package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.MoneyInput

/** A budget line as the person is editing it. */
data class BudgetDraft(
    val amount: String = "",
)

/** Why a budget line cannot be saved yet. */
enum class BudgetBlock(val messageKey: String) {
    NO_AMOUNT(Strings.budget_block_no_amount),
    AMOUNT_NOT_MONEY(Strings.budget_block_amount_invalid),
    AMOUNT_NEGATIVE(Strings.budget_block_amount_negative),
    AMOUNT_TOO_PRECISE(Strings.budget_block_amount_too_precise),
    NOTHING_CHANGED(Strings.budget_block_unchanged),
}

/**
 * How Home and the Budget tab each order the same lines.
 *
 * Both put what is over budget first — that is the thing someone opened the
 * screen to find — and differ only in what they do with the rest. Kept as one
 * fold with a parameter rather than two functions, so the shared half cannot
 * drift between the two screens.
 */
enum class BudgetOrder {
    /** The Budget tab (#47): everything, biggest spend first. */
    BY_SPENT,

    /** Home (#46): the few lines closest to their allocation. */
    BY_NEAREST_ALLOCATION,
}

/**
 * Editing a budget line, and folding a budget into what a screen shows
 * (PRD F4).
 *
 * **Nothing here computes money.** The server owns every figure on this
 * screen — the totals, the suggestions, the shortfall — and the one
 * subtraction in the model is of two figures it sent. What lives here is the
 * decision of whether a draft may be saved, and the ordering and the month
 * list, which are the things Android and iOS could otherwise disagree about
 * (CLAUDE.md → Who computes what).
 */
object BudgetEdit {

    /** The categories that can never hold a line; the server refuses them too. */
    val NOT_BUDGETABLE = setOf("income", "transfers")

    fun draftOf(line: BudgetLine): BudgetDraft = BudgetDraft(amount = line.allocated)

    /**
     * Why [draft] cannot replace [original]'s allocation, or null when it can.
     *
     * The one function behind the disabled Save button, the inline notice and
     * the use case's refusal (CLAUDE.md → Blocking reasons). Zero is allowed:
     * budgeting nothing for a category is a real decision, and the server
     * accepts it.
     */
    fun blockingReason(original: BudgetLine, draft: BudgetDraft, currency: String): BudgetBlock? {
        valid(draft, currency)?.let { return it }
        val digits = Money.fractionDigits(currency)
        // Compared normalised: "500" and "500.00" are the same allocation.
        if (Money.normalize(draft.amount, digits) == Money.normalize(original.allocated, digits)) {
            return BudgetBlock.NOTHING_CHANGED
        }
        return null
    }

    /**
     * Why [draft] cannot become a line for a category that has none.
     *
     * There is nothing to be unchanged from, so [BudgetBlock.NOTHING_CHANGED]
     * cannot apply — every other reason does.
     */
    fun blockingReasonForNew(draft: BudgetDraft, currency: String): BudgetBlock? = valid(draft, currency)

    /**
     * The reasons shared by setting a line and adding one. The amount checks
     * themselves are [MoneyInput]'s, shared with goals, so the subtle one —
     * too precise versus not a number — is written once.
     */
    private fun valid(draft: BudgetDraft, currency: String): BudgetBlock? = when (MoneyInput.problem(draft.amount, currency)) {
        MoneyInput.Problem.BLANK -> BudgetBlock.NO_AMOUNT
        MoneyInput.Problem.NEGATIVE -> BudgetBlock.AMOUNT_NEGATIVE
        MoneyInput.Problem.NOT_MONEY -> BudgetBlock.AMOUNT_NOT_MONEY
        MoneyInput.Problem.TOO_PRECISE -> BudgetBlock.AMOUNT_TOO_PRECISE
        null -> null
    }

    /** True when [slug] is a category a line can be set for. */
    fun isBudgetable(slug: String): Boolean = slug !in NOT_BUDGETABLE

    /**
     * [lines] ordered for a screen: over budget first, then by [order].
     *
     * Over-budget lines lead because they are the only ones asking for a
     * decision. Within each group the order is total, so two phones showing
     * the same budget show it in the same order — ties break on name, then
     * on category id, rather than on whatever order the server happened to
     * send.
     */
    fun ordered(
        lines: List<BudgetLine>,
        currency: String,
        order: BudgetOrder = BudgetOrder.BY_SPENT,
    ): List<BudgetLine> {
        val digits = Money.fractionDigits(currency)
        return lines.sortedWith(
            compareByDescending<BudgetLine> { it.isOver(digits) }
                .thenByDescending { rank(it, digits, order) }
                .thenBy { it.name }
                .thenBy { it.categoryId },
        )
    }

    /**
     * How urgent a line is within its group, larger first.
     *
     * A ratio for [BudgetOrder.BY_NEAREST_ALLOCATION] — Home wants the lines
     * closest to their limit whatever their size, so a $90-of-$100 line beats
     * a $400-of-$2000 one. A spend for [BudgetOrder.BY_SPENT] — the Budget tab
     * lists everything, and there the biggest numbers are what the eye wants
     * first. Both are sort keys, never shown.
     */
    private fun rank(line: BudgetLine, digits: Int, order: BudgetOrder): Double = when (order) {
        BudgetOrder.BY_NEAREST_ALLOCATION -> line.fraction(digits)
        BudgetOrder.BY_SPENT -> Money.normalize(line.spent, digits)?.toDoubleOrNull() ?: 0.0
    }

    /**
     * The first [count] lines Home shows, already ordered.
     *
     * Home has room for a few; the Budget tab has room for all of them. Both
     * read [ordered], so the ones Home shows are always the top of the list
     * the tab shows.
     */
    fun forHome(budget: Budget, count: Int = 4): List<BudgetLine> = ordered(budget.lines, budget.currency, BudgetOrder.BY_NEAREST_ALLOCATION).take(count)

    /**
     * The months the selector offers, newest first, as `YYYY-MM`.
     *
     * Starts at [current] because the server refuses a month that has not
     * begun ([com.humblesolutions.finai.model.ApiException.MonthInFuture]),
     * and runs back [count] months. Earlier months keep the budget they had,
     * so they are worth being able to reach.
     *
     * The walk is [DashboardMonths]'s, not a second one: the dashboard's
     * month selector and this one disagreeing about what last December was
     * called is the kind of bug nobody finds until December.
     */
    fun months(current: String, count: Int = 12): List<String> {
        val first = DashboardMonths.parse(current) ?: return listOf(current)
        var month = first
        val out = mutableListOf<String>()
        repeat(count.coerceAtLeast(1)) {
            out += DashboardMonths.wire(month)
            month = DashboardMonths.previous(month)
        }
        return out
    }

    /**
     * The `YYYY-MM` for a budget's [Budget.month], which is a full date.
     *
     * The endpoint takes a month and answers with its 1st, so going back for
     * the same month — after a save, or on retry — means dropping the day.
     */
    fun monthKeyOf(budget: Budget): String = DashboardMonths.parse(budget.month)?.let { DashboardMonths.wire(it) } ?: budget.month.take(7)
}
