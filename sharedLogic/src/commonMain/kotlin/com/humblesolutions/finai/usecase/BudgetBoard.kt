package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

/** One figure on a card, with which way it moves the money: +1 in, -1 out, 0 neither. */
data class BoardFigure(
    val label: String,
    val amount: String,
    val sign: Int = 0,
)

/** "This month so far": the month as the statements tell it, and what is left of it now. */
data class BoardSoFar(
    val title: String,
    val subtitle: String,
    /** "In progress" while the month runs, "Final" once it has ended. */
    val badge: String,
    val running: Boolean,
    val income: BoardFigure,
    val spent: BoardFigure,
    val saved: BoardFigure,
    val leftLabel: String,
    val left: String,
    val leftCaption: String,
    val leftNegative: Boolean,
)

/** "Expected this month": what is still to come, where the month lands, and how much of income that is. */
data class BoardForecast(
    val title: String,
    val subtitle: String,
    val rows: List<BoardFigure>,
    val leftLabel: String,
    val left: String,
    val surplus: Boolean,
    val statusLabel: String,
    /** What the month lands on as a whole percentage of income; may be negative. */
    val percentLeft: Int,
    /** The ring's fill, 0 to 1. */
    val fraction: Float,
    val gaugeLabel: String,
    val gaugeCaption: String,
)

/** One slice of the categories' donut, and its row in the legend. */
data class DonutSlice(
    val label: String,
    val percent: Int,
    /** The slice's share of the ring, 0 to 1. */
    val fraction: Float,
    /** Which of the board's colours it is drawn in: 0 to [BudgetBoard.PALETTE_SIZE] - 1. */
    val colorIndex: Int,
)

/** The categories' donut: what is in the middle, and the slices. */
data class BoardDonut(
    val title: String,
    val centre: String,
    val centreCaption: String,
    val totalsLabel: String,
    val totals: String,
    val slices: List<DonutSlice>,
)

/** What a next step does when tapped. Each app maps it to its own navigation. */
enum class NextStepAction {
    REVIEW,
    IMPORT,
    PLAN,
    SAVINGS_TARGET,
    ADD_UNBUDGETED,
}

/** One recommended next step, from the month's own figures. */
data class NextStep(
    val action: NextStepAction,
    val title: String,
    val detail: String,
    /** The money-left step, drawn as the one to do. */
    val emphasized: Boolean = false,
    /** For [NextStepAction.ADD_UNBUDGETED]: the category to give a line. */
    val categoryId: String? = null,
)

/**
 * The Budget screen's cards, worded and worked out once for both apps: the
 * month so far, what is still expected, the categories' donut, and the next
 * steps. Every amount is the server's; this formats and compares them. The
 * percentages are ratios of the server's amounts, for drawing — never money.
 */
object BudgetBoard {

    const val PALETTE_SIZE = 5

    /** How many categories the legend names before folding the rest into "Other". */
    private const val LEGEND_NAMED = 4

    private const val MAX_STEPS = 3

    // ── This month so far ───────────────────────────────────────────────

    fun soFar(budget: Budget, locale: String = "en"): BoardSoFar? {
        val income = budget.monthIncome ?: return null
        val spending = budget.monthSpending ?: return null
        val left = budget.monthLeft ?: return null
        val ended = budget.monthEnded
        fun money(amount: String) = Money.format(amount, budget.currency, locale)
        val digits = budget.fractionDigits
        return BoardSoFar(
            title = BudgetOverview.soFarTitle(budget, locale),
            subtitle = text(Strings.board_so_far_subtitle, locale),
            badge = text(if (ended) Strings.board_final else Strings.board_in_progress, locale),
            running = !ended,
            income = BoardFigure(text(if (ended) Strings.budget_income else Strings.budget_income_received, locale), money(income), 1),
            spent = BoardFigure(text(Strings.budget_spent, locale), money(spending), -1),
            saved = BoardFigure(text(Strings.budget_saved, locale), money(budget.monthSaved ?: "0"), 0),
            leftLabel = text(if (ended) Strings.budget_left_over else Strings.budget_left_now, locale),
            left = money(left),
            leftCaption = text(if (ended) Strings.board_left_caption_ended else Strings.board_left_caption_running, locale),
            leftNegative = Money.signOf(left, digits) < 0,
        )
    }

    // ── Expected this month ─────────────────────────────────────────────

    fun forecast(budget: Budget, locale: String = "en"): BoardForecast? {
        if (budget.monthEnded) return null
        val income = budget.incomeExpected ?: return null
        val due = budget.commitmentsDue ?: return null
        val rest = budget.budgetRest ?: return null
        val left = budget.projectedLeft ?: return null
        fun money(amount: String) = Money.format(amount, budget.currency, locale)
        val digits = budget.fractionDigits
        val savingsDue = budget.savingsDue?.takeIf { budget.savings != null && Money.isPositive(it, digits) }
        val rows = listOfNotNull(
            BoardFigure(text(Strings.board_expected_income, locale), money(income), 1),
            BoardFigure(text(Strings.board_commitments_due, locale), money(due), -1),
            BoardFigure(text(Strings.budget_rest_of_budget, locale), money(rest), -1),
            savingsDue?.let { BoardFigure(text(Strings.budget_savings_still_due, locale), money(it), -1) },
        )
        val surplus = Money.signOf(left, digits) >= 0
        val percent = percentOf(left, budget.expectedIncome)
        val magnitude = Money.magnitudeOf(left, digits) ?: left
        return BoardForecast(
            title = text(Strings.board_expected_title, locale),
            subtitle = format(Strings.board_expected_subtitle, locale, monthName(budget, locale)),
            rows = rows,
            leftLabel = text(Strings.budget_left_at_month_end, locale),
            left = money(if (surplus) left else magnitude),
            surplus = surplus,
            statusLabel = text(if (surplus) Strings.board_surplus else Strings.board_short, locale),
            percentLeft = percent,
            fraction = (percent / 100f).coerceIn(0f, 1f),
            gaugeLabel = text(Strings.board_gauge_label, locale),
            gaugeCaption = text(Strings.board_gauge_caption, locale),
        )
    }

    // ── Categories ──────────────────────────────────────────────────────

    /**
     * The donut over a running month's lines — every line, savings and debt
     * included, as shares of what is budgeted — or over an ended month's
     * spending, as shares of what was spent. Null when there is nothing to draw.
     */
    fun donut(budget: Budget, locale: String = "en"): BoardDonut? {
        fun money(amount: String) = Money.format(amount, budget.currency, locale)
        return if (budget.monthEnded) {
            val spends = budget.spendingByCategory
            if (spends.isEmpty()) return null
            val total = spentTotal(budget)
            BoardDonut(
                title = text(Strings.budget_where_it_went, locale),
                centre = Money.compact(total, budget.currency, locale),
                centreCaption = text(Strings.board_donut_spent, locale),
                totalsLabel = text(Strings.board_spent_total, locale),
                totals = money(total),
                slices = slices(spends.map { (it.name ?: text(Strings.budget_not_filed, locale)) to it.spent }, total, locale),
            )
        } else {
            val lines = budget.allLines.filter { Money.isPositive(it.allocated, budget.fractionDigits) }
            if (lines.isEmpty()) return null
            BoardDonut(
                title = text(Strings.board_categories_title, locale),
                centre = Money.compact(budget.totalAllocated, budget.currency, locale),
                centreCaption = text(Strings.board_donut_budgeted, locale),
                totalsLabel = text(Strings.board_spent_of_total, locale),
                totals = format(Strings.board_spent_of, locale, money(budget.totalSpent), money(budget.totalAllocated)),
                slices = slices(lines.sortedByDescending { it.allocated.toDoubleOrNull() ?: 0.0 }.map { it.name to it.allocated }, budget.totalAllocated, locale),
            )
        }
    }

    /** "52% allotted": a line's share of everything budgeted. */
    fun allotted(line: BudgetLine, budget: Budget, locale: String = "en"): String = format(Strings.board_allotted, locale, percentOf(line.allocated, budget.totalAllocated).coerceAtLeast(0).toString())

    /** "18% of spending": an ended month's category as a share of what was spent. */
    fun shareOfSpending(index: Int, budget: Budget, locale: String = "en"): String {
        val spend = budget.spendingByCategory.getOrNull(index) ?: return ""
        return format(Strings.board_share_of_spending, locale, percentOf(spend.spent, spentTotal(budget)).coerceAtLeast(0).toString())
    }

    private fun spentTotal(budget: Budget): String = budget.spendingByCategory.fold("0") { sum, s -> Money.add(sum, s.spent, budget.fractionDigits) ?: sum }

    /** The colour a line is drawn in: its slice's, so the row and the donut agree. */
    fun colorIndexOf(line: BudgetLine, budget: Budget): Int {
        val ranked = budget.allLines.filter { Money.isPositive(it.allocated, budget.fractionDigits) }.sortedByDescending { it.allocated.toDoubleOrNull() ?: 0.0 }
        val at = ranked.indexOfFirst { it.categoryId == line.categoryId }
        return if (at < 0) PALETTE_SIZE - 1 else at.coerceAtMost(PALETTE_SIZE - 1)
    }

    /** As [colorIndexOf], for an ended month's category by its place in the list. */
    fun colorIndexOfSpend(index: Int): Int = if (index < 0) PALETTE_SIZE - 1 else index.coerceAtMost(PALETTE_SIZE - 1)

    private fun slices(items: List<Pair<String, String>>, total: String, locale: String): List<DonutSlice> {
        val named = items.take(LEGEND_NAMED)
        val rest = items.drop(LEGEND_NAMED)
        val totalUnits = total.toDoubleOrNull() ?: 0.0
        if (totalUnits <= 0.0) return emptyList()
        val parts = named.map { it.first to (it.second.toDoubleOrNull() ?: 0.0) }.toMutableList()
        if (rest.isNotEmpty()) {
            parts += format(Strings.board_other, locale, rest.size.toString()) to rest.sumOf { it.second.toDoubleOrNull() ?: 0.0 }
        }
        val percents = wholePercents(parts.map { it.second / totalUnits })
        return parts.mapIndexed { index, (label, units) ->
            DonutSlice(label, percents[index], (units / totalUnits).toFloat().coerceIn(0f, 1f), index.coerceAtMost(PALETTE_SIZE - 1))
        }
    }

    /** Whole percentages that add up to 100, by largest remainder. */
    internal fun wholePercents(shares: List<Double>): List<Int> {
        if (shares.isEmpty()) return emptyList()
        val exact = shares.map { it * 100 }
        val floors = exact.map { kotlin.math.floor(it).toInt() }.toMutableList()
        var missing = 100 - floors.sum()
        exact.indices.sortedByDescending { exact[it] - floors[it] }.forEach { i ->
            if (missing > 0) {
                floors[i]++
                missing--
            }
        }
        return floors
    }

    // ── Next steps ──────────────────────────────────────────────────────

    /**
     * Up to three things worth doing, from the month's own figures: anything
     * left to put to use last, as the one to do. [planAvailable] is whether
     * Your plan is on, which the money-left step opens.
     */
    fun nextSteps(budget: Budget, planAvailable: Boolean, locale: String = "en"): List<NextStep> {
        val digits = budget.fractionDigits
        fun money(amount: String) = Money.format(amount, budget.currency, locale)
        val steps = mutableListOf<NextStep>()
        if (budget.uncategorisedCount > 0) {
            steps += NextStep(NextStepAction.REVIEW, format(Strings.board_step_review, locale, budget.uncategorisedCount.toString()), text(Strings.board_step_review_detail, locale))
        }
        val running = !budget.monthEnded
        val nothingYet = !Money.isPositive(budget.monthIncome ?: "0", digits) && !Money.isPositive(budget.monthSpending ?: "0", digits)
        if (running && nothingYet) {
            steps += NextStep(NextStepAction.IMPORT, text(Strings.board_step_import, locale), text(Strings.board_step_import_detail, locale))
        }
        val unseen = budget.commitments.count { !it.wasSeen }
        if (running && unseen > 0 && !nothingYet) {
            steps += NextStep(NextStepAction.IMPORT, format(Strings.board_step_commitments, locale, unseen.toString()), text(Strings.board_step_commitments_detail, locale))
        }
        if (running && budget.unbudgeted.isNotEmpty()) {
            steps += NextStep(
                NextStepAction.ADD_UNBUDGETED,
                format(Strings.board_step_unbudgeted, locale, budget.unbudgeted.first().name),
                text(Strings.board_step_unbudgeted_detail, locale),
                categoryId = budget.unbudgeted.first().categoryId,
            )
        }
        if (running && budget.savings == null && budget.savingsCategoryId != null) {
            steps += NextStep(NextStepAction.SAVINGS_TARGET, text(Strings.board_step_target, locale), BudgetOverview.savingsRoom(budget, locale) ?: text(Strings.board_step_target_detail, locale))
        }
        val left = BudgetOverview.leftover(budget)
        val using = planAvailable && running && left != null && Money.signOf(left, digits) > 0
        val picked = steps.take(if (using) MAX_STEPS - 1 else MAX_STEPS).toMutableList()
        if (using) {
            picked += NextStep(NextStepAction.PLAN, format(Strings.board_step_plan, locale, money(left!!)), text(Strings.board_step_plan_detail, locale), emphasized = true)
        }
        return picked
    }

    fun nextStepsTitle(locale: String = "en"): String = text(Strings.board_steps_title, locale)

    fun nextStepsSubtitle(count: Int, locale: String = "en"): String = if (count == 1) text(Strings.board_steps_one, locale) else format(Strings.board_steps_many, locale, count.toString())

    // ── The header ──────────────────────────────────────────────────────

    fun eyebrow(locale: String = "en"): String = text(Strings.board_eyebrow, locale)

    // ── Helpers ─────────────────────────────────────────────────────────

    /** [part] as a whole percentage of [whole], rounded half up; 0 without a whole. A ratio, not money. */
    internal fun percentOf(part: String, whole: String): Int {
        val p = part.toDoubleOrNull() ?: return 0
        val w = whole.toDoubleOrNull() ?: return 0
        if (w <= 0.0) return 0
        return kotlin.math.round(p / w * 100).toInt()
    }

    private fun monthName(budget: Budget, locale: String): String {
        val date = Dates.parse(budget.month) ?: return ""
        return text(MONTHS[date.month.ordinal], locale)
    }

    private val MONTHS = listOf(
        Strings.month_short_1, Strings.month_short_2, Strings.month_short_3, Strings.month_short_4,
        Strings.month_short_5, Strings.month_short_6, Strings.month_short_7, Strings.month_short_8,
        Strings.month_short_9, Strings.month_short_10, Strings.month_short_11, Strings.month_short_12,
    )

    private fun text(key: String, locale: String) = LocalizationRegistry.get(key, locale)

    private fun format(key: String, locale: String, vararg args: String) = LocalizationRegistry.format(key, args.toList(), locale)
}
