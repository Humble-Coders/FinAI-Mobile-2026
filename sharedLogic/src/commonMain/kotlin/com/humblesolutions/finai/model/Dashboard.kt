package com.humblesolutions.finai.model

import com.humblesolutions.finai.util.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One month of the dashboard, from `GET /dashboard` (PRD F3).
 *
 * **Nothing here adds an expectation to an actual.** The wizard's figures and
 * what the bank did are different kinds of fact, and summing them counts every
 * commitment twice — rent typed at setup and then imported is one payment. The
 * server refuses to combine them and so does this model: there is no property
 * anywhere below that returns the two together, and none should be added.
 *
 * Amounts are decimal strings in both directions (PRD §4.4). Defaults on every
 * field, so a payload missing one still decodes.
 */
@Serializable
data class Dashboard(
    /** The first day of the month this describes — a month is named by its 1st. */
    val month: String = "",
    val currency: String = "",

    /**
     * The hero: income minus expenses, computed by the server.
     *
     * Not a balance. We never see an account balance — there is no such column
     * and a statement's closing figure is not parsed — so a "total balance"
     * would be invented. This is the figure the month actually kept.
     */
    val net: String = "0",

    /** Null when the previous month has no rows at all; see [changeLabelAvailable]. */
    @SerialName("previous_net")
    val previousNet: String? = null,

    val income: Flow = Flow(),
    val expenses: Flow = Flow(),
    val investments: Stock = Stock(),
    val debts: Stock = Stock(),

    /** The setup obligations, each with the payment that settles it — or not. */
    val commitments: List<Commitment> = emptyList(),

    /** Oldest first, ending at [month]. */
    val trend: List<MonthPoint> = emptyList(),

    /**
     * The month's running in-minus-out, one point a day from the 1st, ending
     * at [net]. Empty for a month with nothing in it — and from a server older
     * than the field, which a screen treats the same: no chart.
     */
    val daily: List<DayPoint> = emptyList(),

    /** Rows this month still waiting on a person, and the caveat on the figures. */
    @SerialName("pending_review")
    val pendingReview: Int = 0,
) {
    val symbol: String get() = Money.symbol(currency)

    val fractionDigits: Int get() = Money.fractionDigits(currency)

    /** Whether the month kept anything. False includes a month that broke even. */
    val netIsPositive: Boolean get() = Money.signOf(net) > 0

    /**
     * Whether a "vs last month" line can be drawn.
     *
     * False when the previous month has no rows, because a rise from nothing is
     * not a percentage. The screen omits the line rather than printing one.
     */
    val changeLabelAvailable: Boolean get() = previousNet != null

    /** Commitments with no payment found this month, in the order set up. */
    val commitmentsNotSeen: List<Commitment> get() = commitments.filter { !it.wasSeen }

    /** How many commitments were seen going out, for "3 of 5". */
    val commitmentsSeenCount: Int get() = commitments.count { it.wasSeen }

    /** True when there is nothing to show yet, so the screen offers a first step. */
    val isEmpty: Boolean
        get() = commitments.isEmpty() &&
            trend.none { it.hasData } &&
            Money.signOf(income.actual) == 0 &&
            Money.signOf(expenses.actual) == 0
}

/**
 * A month's movement in one direction, and what the wizard expected of it.
 *
 * Two fields and no third: [actual] is what the bank did, [expected] is what
 * was declared at setup, and a screen shows one against the other. Adding them
 * is the double count this whole design exists to prevent.
 */
@Serializable
data class Flow(
    val actual: String = "0",
    /** Null until the wizard is filled in — never zero, which would read as a figure. */
    val expected: String? = null,
) {
    /** Whether "of X expected" can be shown at all. */
    val hasExpectation: Boolean get() = expected != null

    /**
     * Whether the actual has passed what was expected.
     *
     * Meaningful for expenses, where over is a warning; on income the same
     * comparison is good news, so the screen decides what it means, not this.
     */
    val isOverExpected: Boolean
        get() {
            val target = expected ?: return false
            return Money.compare(actual, target) > 0
        }
}

/**
 * A balance the user maintains, and this month's movement against it.
 *
 * Two numbers because only one is observed. We never see a portfolio's market
 * value or a loan's outstanding principal — only what moved through the bank —
 * so [balance] stays whatever the wizard was told and [moved] is ours.
 */
@Serializable
data class Stock(
    val balance: String = "0",
    val moved: String = "0",
) {
    /** Whether anything moved, so the screen can omit a line reading "0.00". */
    val movedThisMonth: Boolean get() = Money.signOf(moved) > 0
}

/**
 * One obligation from setup, and the payment that settles it.
 *
 * [match] being null means **not seen this month**, which is not the same as
 * unpaid: a commitment settled in cash, from another account, or under a name
 * the bank writes differently will not be found. [wasSeen] is named for what it
 * actually knows, so no screen can accidentally say "unpaid".
 */
@Serializable
data class Commitment(
    val name: String = "",
    val expected: String = "0",
    val match: CommitmentMatch? = null,
) {
    val wasSeen: Boolean get() = match != null

    /**
     * Whether what went out differs from what was expected, for a screen that
     * wants to point at the figure rather than just tick it.
     */
    val paidADifferentAmount: Boolean
        get() {
            val paid = match?.amount ?: return false
            return Money.compare(paid, expected) != 0
        }
}

@Serializable
data class CommitmentMatch(
    val id: String = "",
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val amount: String = "0",
    val description: String? = null,
)

/**
 * One bar of the trend.
 *
 * [net] is null for a month with no rows at all. That is not zero, and a chart
 * must not draw it as one: a zero-height bar states a fact nobody observed.
 */
@Serializable
data class MonthPoint(
    val month: String = "",
    val net: String? = null,
) {
    val hasData: Boolean get() = net != null
}

/**
 * One day of the month's running balance: everything in minus everything out
 * from the 1st up to and including [day]. A quiet day carries the day before's.
 */
@Serializable
data class DayPoint(
    val day: String = "",
    val net: String = "0",
)
