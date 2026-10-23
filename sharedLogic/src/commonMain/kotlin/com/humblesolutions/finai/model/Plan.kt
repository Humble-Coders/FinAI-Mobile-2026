package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the money left over goes, debt payoff first — `GET /plan` (backend #76).
 *
 * **Every figure is the server's**: the money left over (the budget's Savings
 * line), the split, each debt's payoff month and interest, the totals and the
 * comparisons. The app words and orders them; nothing here works one out
 * (CLAUDE.md → Who computes what).
 *
 * Amounts are decimal strings, rates percentages (`"6.00"`), months `YYYY-MM`.
 */
@Serializable
data class Plan(
    val month: String = "",
    val currency: String = "",
    val status: PlanStatus = PlanStatus.UNKNOWN,
    /** This month's budget Savings line: what the split divides. `"0.00"` while learning. */
    val leftover: String = "0",
    /** How far the budget's lines exceed income; then there is nothing to split. */
    val shortfall: String? = null,
    val preferences: PlanPreferences = PlanPreferences(),
    val split: PlanSplit = PlanSplit(),
    /** In the household's order: setup's debts first, then the rest. */
    val debts: List<PlanDebt> = emptyList(),
    val totals: PlanTotals = PlanTotals(),
)

@Serializable(with = PlanStatusSerializer::class)
enum class PlanStatus(val wire: String) {
    READY("ready"),

    /** No budget yet (PRD F8): nothing to split, and the debts shown at their minimums. */
    LEARNING("learning"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): PlanStatus = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object PlanStatusSerializer :
    WireEnumSerializer<PlanStatus>("PlanStatus", { PlanStatus.fromWire(it) }, { it.wire })

/**
 * The person's four choices — or, until [saved], the suggestions the plan
 * runs on, which [suggested] repeats so a screen can say "suggested".
 */
@Serializable
data class PlanPreferences(
    val saved: Boolean = false,
    /** The safety buffer's target; null for no buffer. */
    val buffer: String? = null,
    /** The emergency-fund goal the buffer is; null until one is chosen. */
    @SerialName("buffer_goal_id")
    val bufferGoalId: String? = null,
    @SerialName("goal_share_percent")
    val goalSharePercent: String = "0",
    @SerialName("rate_line_percent")
    val rateLinePercent: String = "0",
    val strategy: PlanStrategy = PlanStrategy.AVALANCHE,
    val suggested: PlanSuggested = PlanSuggested(),
)

@Serializable
data class PlanSuggested(
    /** One month of the household's spending; null with nothing to go on. */
    val buffer: String? = null,
    @SerialName("goal_share_percent")
    val goalSharePercent: String = "0",
    @SerialName("rate_line_percent")
    val rateLinePercent: String = "0",
    val strategy: PlanStrategy = PlanStrategy.AVALANCHE,
)

/** Which debt the extra goes to first. */
@Serializable(with = PlanStrategySerializer::class)
enum class PlanStrategy(val wire: String) {
    /** The highest rate first: the least interest. */
    AVALANCHE("avalanche"),

    /** The smallest balance first: a debt gone sooner. */
    SNOWBALL("snowball"),
    ;

    companion object {
        /** An unknown strategy reads as avalanche, the default, rather than failing the plan. */
        fun fromWire(value: String?): PlanStrategy = entries.firstOrNull { it.wire == value } ?: AVALANCHE
    }
}

internal object PlanStrategySerializer :
    WireEnumSerializer<PlanStrategy>("PlanStrategy", { PlanStrategy.fromWire(it) }, { it.wire })

/** The money left over, divided. The parts add up exactly to `Plan.leftover`. */
@Serializable
data class PlanSplit(
    val buffer: String = "0",
    /** What the buffer's goal holds, beside [bufferTarget]; null with no buffer. */
    @SerialName("buffer_saved")
    val bufferSaved: String? = null,
    @SerialName("buffer_target")
    val bufferTarget: String? = null,
    /** By priority; only the goals given something. */
    val goals: List<PlanGoalShare> = emptyList(),
    @SerialName("extra_debt")
    val extraDebt: String = "0",
    /** One generic bucket. Never a product (PRD §1). */
    @SerialName("save_or_invest")
    val saveOrInvest: String = "0",
)

@Serializable
data class PlanGoalShare(
    @SerialName("goal_id")
    val goalId: String = "",
    val name: String = "",
    val amount: String = "0",
)

/** One debt's place in the plan. */
@Serializable
data class PlanDebt(
    @SerialName("debt_id")
    val debtId: String = "",
    val name: String = "",
    val kind: DebtKind = DebtKind.OTHER,
    val balance: String = "0",
    @SerialName("minimum_payment")
    val minimumPayment: String? = null,
    /** The rate charged this month — the promotional one while it lasts. */
    @SerialName("rate_percent")
    val ratePercent: String? = null,
    /** From 1; null for a debt that only gets its minimum or has no rate. */
    val order: Int? = null,
    /** At or below the rate line: only ever its minimum. */
    @SerialName("minimum_only")
    val minimumOnly: Boolean = false,
    /** No rate, so not planned: the screen asks for one rather than guessing. */
    @SerialName("missing_rate")
    val missingRate: Boolean = false,
    /** This month's extra goes here first. */
    @SerialName("gets_extra_now")
    val getsExtraNow: Boolean = false,
    /** `YYYY-MM`; null when never paid off at this payment, or with no rate. */
    @SerialName("paid_off")
    val paidOff: String? = null,
    @SerialName("never_paid_off")
    val neverPaidOff: Boolean = false,
    val interest: String = "0",
)

@Serializable
data class PlanTotals(
    /** Null when a debt is never paid off: there is no finite total. */
    @SerialName("total_interest")
    val totalInterest: String? = null,
    /** `YYYY-MM`; null while any debt is never paid off or has no rate. */
    @SerialName("debt_free")
    val debtFree: String? = null,
    @SerialName("minimums_only_interest")
    val minimumsOnlyInterest: String? = null,
    /** Against paying only minimums. */
    @SerialName("interest_saved")
    val interestSaved: String? = null,
    @SerialName("other_strategy")
    val otherStrategy: PlanStrategy = PlanStrategy.SNOWBALL,
    /** What the other strategy costs more; negative when it costs less. */
    @SerialName("other_strategy_difference")
    val otherStrategyDifference: String? = null,
)

/**
 * `PUT /plan/preferences` — built by `PlanChoices` from a draft that passed its
 * blocking reason. Every field is required by the server, [buffer] included:
 * its null means "no buffer", which the shared JSON would drop, so the
 * repository writes this body by hand.
 */
data class PlanPreferencesToSave(
    val buffer: String?,
    val goalSharePercent: String,
    val rateLinePercent: String,
    val strategy: PlanStrategy,
)
