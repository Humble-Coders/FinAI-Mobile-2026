package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.util.Money

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

    /**
     * A scale wide enough that anything a person types by hand fits inside
     * it, used to tell "too many decimal places" apart from "not a number".
     *
     * [Money.normalize] refuses both the same way, with a null — it does not
     * truncate, it declines — so `"500.999"` and `"five hundred"` are
     * indistinguishable at the currency's own scale. Reading the draft again
     * at this scale separates them: a figure that is money here and not
     * there had too much precision, and deserves to be told so rather than
     * being called nonsense.
     */
    private const val WIDE_SCALE = 9

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
     * The reasons shared by setting a line and adding one, in the order the
     * person would notice them.
     *
     * Negative is checked on the raw text, before [Money.normalize], because
     * normalize treats a minus sign as "not money at all" and would report it
     * as such — true, but useless next to a field where the person has
     * plainly just typed a minus.
     */
    private fun valid(draft: BudgetDraft, currency: String): BudgetBlock? {
        if (draft.amount.isBlank()) return BudgetBlock.NO_AMOUNT
        if (draft.amount.trim().startsWith("-")) return BudgetBlock.AMOUNT_NEGATIVE
        val digits = Money.fractionDigits(currency)
        if (Money.normalize(draft.amount, digits) != null) return null
        return if (isTooPrecise(draft.amount)) BudgetBlock.AMOUNT_TOO_PRECISE else BudgetBlock.AMOUNT_NOT_MONEY
    }

    /**
     * Whether [raw] is a real figure that simply has too many decimal places
     * for its currency — as opposed to not being a figure at all.
     *
     * Only asked once the currency's own scale has already refused it.
     * Decided by [Money] rather than by counting characters: whether the dot
     * in `"1.200"` is a decimal point or a thousands separator is exactly
     * the judgement [Money.normalize] already makes, and a second opinion
     * here would eventually disagree with it.
     *
     * A currency with no cents is covered by the same question: `"60000.5"`
     * yen is money at a wider scale and not at zero, so it is too precise.
     */
    private fun isTooPrecise(raw: String): Boolean = Money.normalize(raw, WIDE_SCALE) != null

    /** True when [slug] is a category a line can be set for. */
    fun isBudgetable(slug: String): Boolean = slug !in NOT_BUDGETABLE

    /**
     * [lines] in the order every screen shows them: over budget first, then
     * the ones closest to their allocation (PO decision, #50).
     *
     * One order for Home and the Budget tab alike, so the four lines Home
     * shows are the top four of the list "See budget" opens, and the same
     * budget never appears in two orders. Earlier this took the secondary
     * sort as a parameter because #46 and #47 specified different ones.
     *
     * "Closest" is how much of the allocation is used, not how many dollars
     * are left: a $90-of-$100 line is nearer its limit than a
     * $400-of-$2,000 one. Over-budget lines are ranked the same way, so the
     * one furthest over leads — with only four places on Home, the worst
     * overspends must be the ones that show, not whichever come first
     * alphabetically.
     *
     * The order is total, so two phones show one budget the same way: ties
     * break on name, then category id, never on the order the server sent.
     */
    fun ordered(lines: List<BudgetLine>, currency: String): List<BudgetLine> {
        val digits = Money.fractionDigits(currency)
        return lines.sortedWith(
            compareByDescending<BudgetLine> { it.isOver(digits) }
                .thenByDescending { pressure(it, digits) }
                .thenBy { it.name }
                .thenBy { it.categoryId },
        )
    }

    /**
     * How much of a line's allocation is used, as a sort key: 0.9 for $90 of
     * $100, 1.2 for $120 of $100.
     *
     * Unlike [BudgetLine.fraction] this is not capped at 1 — that cap is
     * right for a bar, which cannot be longer than its track, and wrong for
     * a ranking, where it would make every overspend tie. Spending against
     * an allocation of zero is as far over as a line can be. The doubles are
     * a ratio for sorting, never shown; [BudgetLine.isOver] decides the
     * groups on the decimal strings themselves.
     */
    private fun pressure(line: BudgetLine, digits: Int): Double {
        val spent = Money.normalize(line.spent, digits)?.toDoubleOrNull() ?: return 0.0
        val allocated = Money.normalize(line.allocated, digits)?.toDoubleOrNull() ?: return 0.0
        return when {
            allocated > 0.0 -> spent / allocated
            spent > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
    }

    /**
     * The first [count] lines Home shows: the top of [ordered], which is the
     * same list the Budget tab shows in full.
     */
    fun forHome(budget: Budget, count: Int = 4): List<BudgetLine> = ordered(budget.lines, budget.currency).take(count)

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
