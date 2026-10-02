package com.humblesolutions.finai.ui.dashboard

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * The dashboard as the screen reads it (PRD F3).
 *
 * Derived rules are computed vals here rather than work done inside the
 * composable, so the wiring is testable with a plain constructor and no
 * dispatcher (kmp-arch-v2). Anything that is a *decision* lives further in, on
 * [Dashboard] or [DashboardMonths]; what is left here is wording and layout.
 *
 * **No property below adds an expectation to an actual.** Doing so is the
 * double count the server refuses to make, and it would arrive on screen just
 * as wrong for being computed here.
 */
data class DashboardUiState(
    val month: LocalDate = DashboardMonths.current(),
    val data: Dashboard = Dashboard(),
    val locale: String = "en",

    /** First paint. A month change uses [refreshing], so the figures stay up. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Only when there is nothing to show: a failed refresh keeps the month it had. */
    val loadFailed: Boolean = false,
    val errorKey: String? = null,

    /**
     * Whether figures are masked. Session-only and deliberately not saved: it
     * is for the moment somebody is on a train, not a setting, and a dashboard
     * that opens blank because of a tap days ago is a bug report.
     */
    val amountsHidden: Boolean = false,
) {
    private val digits: Int get() = data.fractionDigits

    private fun money(amount: String): String = if (amountsHidden) text(Strings.dashboard_hidden_amount) else Money.format(amount, data.currency, locale)

    private fun text(key: String): String = LocalizationRegistry.get(key, locale)

    private fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

    /** `Aug 2026`, in the language the server sent. */
    val monthLabel: String get() = text(
        Strings.dashboard_month_display,
        Dates.monthShort(month, locale),
        month.year.toString(),
    )

    /** The label for the eye, which says what tapping it will do. */
    val hideToggleLabel: String get() =
        text(if (amountsHidden) Strings.dashboard_show_amounts else Strings.dashboard_hide_amounts)

    /** Whether anything is waiting, which is what the bell's dot means. */
    val hasPending: Boolean get() = data.pendingReview > 0

    val notificationsLabel: String get() =
        text(if (hasPending) Strings.dashboard_notifications else Strings.dashboard_notifications_none)

    val canGoBack: Boolean get() = !loading
    val canGoForward: Boolean get() = !loading && DashboardMonths.canGoForward(month)

    val net: String get() = money(data.net)
    val netIsPositive: Boolean get() = data.netIsPositive

    /**
     * "$400.00 more than last month", or null when there is nothing to compare.
     *
     * An absolute difference rather than a percentage: a rise from nothing has
     * no percentage, and "+12%" of a figure nobody remembers is harder to read
     * than the amount itself.
     */
    val changeLabel: String? get() {
        val previous = data.previousNet ?: return null
        val gap = Money.difference(data.net, previous, digits) ?: return null
        return when (Money.signOf(gap, digits)) {
            0 -> text(Strings.dashboard_change_same)
            1 -> text(Strings.dashboard_change_up, money(Money.magnitudeOf(gap, digits).orEmpty()))
            else -> text(Strings.dashboard_change_down, money(Money.magnitudeOf(gap, digits).orEmpty()))
        }
    }

    val incomeAmount: String get() = money(data.income.actual)
    val expensesAmount: String get() = money(data.expenses.actual)
    val investmentsAmount: String get() = money(data.investments.balance)
    val debtsAmount: String get() = money(data.debts.balance)

    /** "of $5,200.00 expected", or null before the wizard has been filled in. */
    val incomeExpectation: String? get() = expectation(data.income.expected)
    val expensesExpectation: String? get() = expectation(data.expenses.expected)

    private fun expectation(expected: String?): String? = expected?.let { text(Strings.dashboard_of_expected, money(it)) }

    /** Only expenses: over on income is good news, and a screen should not scold. */
    val expensesAreOver: Boolean get() = data.expenses.isOverExpected

    val investmentsMovement: String get() = movement(data.investments, Strings.dashboard_set_aside)
    val debtsMovement: String get() = movement(data.debts, Strings.dashboard_paid_down)

    private fun movement(stock: com.humblesolutions.finai.model.Stock, key: String): String = if (stock.movedThisMonth) text(key, money(stock.moved)) else text(Strings.dashboard_nothing_moved)

    val commitments: List<CommitmentRow> get() = data.commitments.map { commitment ->
        CommitmentRow(
            name = commitment.name,
            expected = money(commitment.expected),
            wasSeen = commitment.wasSeen,
            detail = detailFor(commitment),
        )
    }

    /**
     * "Seen Aug 2, 2026" — never "Paid".
     *
     * A commitment settled in cash, from another account, or under a name the
     * bank writes differently is indistinguishable from one never paid, so the
     * screen says only what it knows.
     */
    private fun detailFor(commitment: com.humblesolutions.finai.model.Commitment): String {
        val match = commitment.match ?: return text(Strings.dashboard_commitment_not_seen)
        if (commitment.paidADifferentAmount) {
            return text(
                Strings.dashboard_commitment_differs,
                money(match.amount),
                money(commitment.expected),
            )
        }
        val on = Dates.parse(match.occurredOn)?.let { Dates.display(it) } ?: match.occurredOn
        return text(Strings.dashboard_commitment_seen, on)
    }

    val commitmentsSummary: String? get() = data.commitments.takeIf { it.isNotEmpty() }?.let {
        text(
            Strings.dashboard_commitments_summary,
            data.commitmentsSeenCount.toString(),
            it.size.toString(),
        )
    }

    val pendingReviewLabel: String? get() = when {
        data.pendingReview <= 0 -> null
        data.pendingReview == 1 -> text(Strings.dashboard_pending_review_one)
        else -> text(Strings.dashboard_pending_review, data.pendingReview.toString())
    }

    /**
     * The bars, each as a fraction of the tallest month in view.
     *
     * Scaled by magnitude, so a heavy loss draws as tall as a heavy gain and
     * the direction is carried by colour rather than by height — a month that
     * went badly should not look like a quiet one.
     */
    val bars: List<Bar> get() {
        val magnitudes = data.trend.associate { point ->
            point.month to point.net?.let { Money.magnitudeOf(it, digits) }
        }
        val tallest = magnitudes.values.filterNotNull()
            .maxWithOrNull { a, b -> Money.compare(a, b, digits) }

        return data.trend.map { point ->
            val magnitude = magnitudes[point.month]
            val label = Dates.parse(point.month)?.let { Dates.monthShort(it, locale) }.orEmpty()
            Bar(
                month = point.month,
                shortLabel = label,
                // Null is a gap, not a zero: the screen outlines it rather
                // than drawing a bar for a month nobody recorded.
                fraction = magnitude?.let { fractionOf(it, tallest) },
                isNegative = point.net?.let { Money.signOf(it, digits) < 0 } ?: false,
                description = point.net?.let { text(Strings.dashboard_trend_month, label, money(it)) }
                    ?: text(Strings.dashboard_trend_no_data_month, label),
            )
        }
    }

    /**
     * [magnitude] against the tallest bar, floored so a real but tiny month is
     * still a visible mark rather than nothing at all.
     */
    private fun fractionOf(magnitude: String, tallest: String?): Float {
        if (tallest == null || Money.compare(tallest, "0", digits) == 0) return MINIMUM_BAR
        val ratio = (magnitude.toFloatOrNull() ?: return MINIMUM_BAR) /
            (tallest.toFloatOrNull()?.takeIf { it > 0f } ?: return MINIMUM_BAR)
        return ratio.coerceIn(MINIMUM_BAR, 1f)
    }

    /** Nothing recorded at all, so the screen offers a first step instead of zeroes. */
    val showsEmptyState: Boolean get() = !loading && !loadFailed && data.isEmpty

    /** Drawn only once a month in view holds something. */
    val showsTrend: Boolean get() = data.trend.any { it.hasData }

    private companion object {
        /** Floor for a bar, so a £2 month is a mark and not an absence. */
        const val MINIMUM_BAR = 0.06f
    }
}

/** One commitment as a row: its name, what was expected, and what we saw. */
data class CommitmentRow(
    val name: String,
    val expected: String,
    val wasSeen: Boolean,
    val detail: String,
)

/**
 * One bar of the trend.
 *
 * [fraction] is null for a month with no rows at all. The screen draws those
 * as an outline — a zero-height bar states a fact nobody observed.
 */
data class Bar(
    val month: String,
    val shortLabel: String,
    val fraction: Float?,
    val isNegative: Boolean,
    val description: String,
)
