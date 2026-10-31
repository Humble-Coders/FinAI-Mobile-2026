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
    /** The categories in the person's order, each with its percentage (redesign, 2026-10-31). */
    val weights: List<PlanWeight> = emptyList(),
    /** The household has an Emergency fund goal, so it is one of the categories. */
    @SerialName("has_emergency_fund")
    val hasEmergencyFund: Boolean = false,
)

/** One category of the money left over and its share. */
@Serializable
data class PlanWeight(
    val category: PlanCategory = PlanCategory.UNKNOWN,
    /** A percentage, `"40.00"`. */
    val percent: String = "0",
)

/** Where the money left over can go. */
@Serializable(with = PlanCategorySerializer::class)
enum class PlanCategory(val wire: String) {
    /** The household's Emergency fund goal — a category only when it has one. */
    EMERGENCY_FUND("emergency_fund"),
    DEBT("debt"),
    GOALS("goals"),

    /** One generic bucket. Never a product (PRD §1). */
    SAVE_OR_INVEST("save_or_invest"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): PlanCategory = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object PlanCategorySerializer :
    WireEnumSerializer<PlanCategory>("PlanCategory", { PlanCategory.fromWire(it) }, { it.wire })

@Serializable
data class PlanSuggested(
    /** One month of the household's spending; null with nothing to go on. */
    val buffer: String? = null,
    @SerialName("goal_share_percent")
    val goalSharePercent: String = "0",
    @SerialName("rate_line_percent")
    val rateLinePercent: String = "0",
    val strategy: PlanStrategy = PlanStrategy.AVALANCHE,
    val weights: List<PlanWeight> = emptyList(),
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
    /** The Emergency fund goal's row, when the household has one. */
    @SerialName("emergency_fund")
    val emergencyFund: PlanEmergencyShare? = null,
    /** `"ai"` when the AI proposed it (the server checked it), `"rules"` otherwise. */
    val source: String = "rules",
    /** The AI's words about the split; never a figure. */
    val why: String? = null,
    /** The person set a row by hand. */
    val edited: Boolean = false,
    /** The most each row can be set to. */
    val limits: List<PlanRowLimit> = emptyList(),
)

@Serializable
data class PlanEmergencyShare(
    @SerialName("goal_id")
    val goalId: String = "",
    val name: String = "",
    val amount: String = "0",
    /** What the goal still needs. */
    @SerialName("still_needed")
    val stillNeeded: String = "0",
)

/** The most one row of the split can be set to; null for no limit but the whole. */
@Serializable
data class PlanRowLimit(
    /** `emergency_fund`, `debt`, `goal` or `save_or_invest`. */
    val row: String = "",
    @SerialName("goal_id")
    val goalId: String? = null,
    val most: String? = null,
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
 * `PUT /plan/preferences` — built by `YourPlan` from a draft that passed its
 * blocking reason. [weights] null keeps the saved ones: the Debts screen's
 * strategy switch changes only the strategy.
 */
data class PlanPreferencesToSave(
    val strategy: PlanStrategy,
    val weights: List<PlanWeight>?,
)

/** Which row of the split a person sets by hand. */
enum class PlanRow(val wire: String) {
    EMERGENCY_FUND("emergency_fund"),
    DEBT("debt"),
    GOAL("goal"),
    SAVE_OR_INVEST("save_or_invest"),
}

/** `PUT /plan/split` — one row set by hand; [apply] also puts it where it belongs. */
data class PlanSplitEdit(
    val row: PlanRow,
    val goalId: String?,
    val amount: String,
    val apply: Boolean,
)
