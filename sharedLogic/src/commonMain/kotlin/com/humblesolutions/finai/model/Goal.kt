package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the Goals tab shows, from `GET /goals` (#52, PRD F5).
 *
 * **No currency travels with it.** Goals are in the household's currency,
 * which screens take from the capabilities payload — the same one they format
 * every other figure with.
 *
 * **Every figure here is the server's** — what remains, the monthly need,
 * the month it completes, progress and status come from backend #65's
 * `project`. The app formats and orders them; nothing below works one out
 * (CLAUDE.md → Who computes what).
 *
 * Amounts are decimal strings. Defaults on every field, so a payload missing
 * one — or a server that predates goals — still decodes.
 */
@Serializable
data class GoalsPage(
    /** In priority order: the server sorts them, the app keeps that order. */
    val goals: List<Goal> = emptyList(),

    /** The goals' monthly need against this month's savings line; null when there is none to compare. */
    val budget: GoalsBudget? = null,

    /** Why [budget] is null. Null whenever [budget] is set. */
    @SerialName("budget_reason")
    val budgetReason: GoalsBudgetReason? = null,

    /**
     * The regional disclaimer long-term projections are shown with; set when
     * any goal is long-term and the region has one. The text comes from
     * `GET /legal/disclaimer` (PRD §4.6: wording a regulator may need changed
     * cannot live in an app release).
     */
    @SerialName("disclaimer_version")
    val disclaimerVersion: String? = null,

    /** Which version of the projection math produced these figures — `"v1"` today. */
    @SerialName("projection_version")
    val projectionVersion: String = "",

    /**
     * Whether projections count investment growth. False in v1: they count
     * only what is put in, and long-term goals say so in words.
     */
    @SerialName("assumes_growth")
    val assumesGrowth: Boolean = false,
) {
    /** Goals still being saved for — the ones the server's limit counts. */
    val openCount: Int get() = goals.count { it.status != GoalStatus.ACHIEVED }
}

/**
 * One goal and the server's projection of it.
 *
 * [target], [saved], [targetDate] and [monthlyContribution] are what the person
 * entered; everything after them is computed by the server from those.
 */
@Serializable
data class Goal(
    val id: String = "",
    val name: String = "",
    val kind: GoalKind? = null,
    val horizon: GoalHorizon = GoalHorizon.UNKNOWN,
    val target: String = "0",
    val saved: String = "0",

    /** `YYYY-MM-DD`, or null. */
    @SerialName("target_date")
    val targetDate: String? = null,

    @SerialName("monthly_contribution")
    val monthlyContribution: String? = null,

    /** What is left to save. Never negative. */
    val remaining: String = "0",

    /** What each month needs to reach the target by [targetDate]. Null without a date, or once it has passed. */
    @SerialName("required_monthly")
    val requiredMonthly: String? = null,

    /** `YYYY-MM`: when [monthlyContribution] covers what remains. Null without one, or with nothing left. */
    @SerialName("projected_completion")
    val projectedCompletion: String? = null,

    /** 0–100, the server's floor of saved over target. */
    @SerialName("progress_percent")
    val progressPercent: Int = 0,

    val status: GoalStatus = GoalStatus.UNKNOWN,

    @SerialName("achieved_at")
    val achievedAt: String? = null,

    /** Lower sorts first. */
    val priority: Int = 0,
) {
    val isLongTerm: Boolean get() = horizon == GoalHorizon.LONG_TERM

    val isAchieved: Boolean get() = status == GoalStatus.ACHIEVED

    /** The bar's length, 0 to 1, from the server's percentage — never from the amounts. */
    val fraction: Float get() = (progressPercent.coerceIn(0, 100)) / 100f
}

/** The goals' monthly need against the budget's savings line for this month (backend #65). */
@Serializable
data class GoalsBudget(
    /** What the open goals need each month, together. */
    val need: String = "0",
    /** What this month's budget sets aside as savings. */
    @SerialName("set_aside")
    val setAside: String = "0",
    /** How far [need] exceeds [setAside]; null when it does not. */
    val shortfall: String? = null,
)

/** Where a goal stands. Decided by the server; the app only words it. */
@Serializable(with = GoalStatusSerializer::class)
enum class GoalStatus(val wire: String) {
    ACHIEVED("achieved"),
    OVERDUE("overdue"),
    ON_TRACK("on_track"),
    BEHIND("behind"),

    /** Not enough to compare: no date, no monthly amount, or only one of them. */
    OPEN("open"),

    /** A status this build does not know. Worded as nothing rather than guessed. */
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): GoalStatus = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object GoalStatusSerializer :
    WireEnumSerializer<GoalStatus>("GoalStatus", { GoalStatus.fromWire(it) }, { it.wire })

/** What a goal is for — for its picture only (backend #65: no kind suggests an amount). */
@Serializable(with = GoalKindSerializer::class)
enum class GoalKind(val wire: String) {
    EMERGENCY_FUND("emergency_fund"),
    VACATION("vacation"),
    CAR("car"),
    ELECTRONICS("electronics"),
    HOME("home"),
    RETIREMENT("retirement"),
    WEALTH("wealth"),
    OTHER("other"),

    /** A kind this build does not know; drawn as [OTHER]. Never sent. */
    UNKNOWN(""),
    ;

    companion object {
        /** The kinds a person can choose, in the order the picker shows them. */
        val choosable: List<GoalKind> = entries.filter { it != UNKNOWN }

        fun fromWire(value: String?): GoalKind = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object GoalKindSerializer :
    WireEnumSerializer<GoalKind>("GoalKind", { GoalKind.fromWire(it) }, { it.wire })

/**
 * Short or long term. Long-term goals carry the disclaimer and the growth
 * line, so this is never defaulted on a new goal — see `GoalEdit`.
 */
@Serializable(with = GoalHorizonSerializer::class)
enum class GoalHorizon(val wire: String) {
    SHORT_TERM("short_term"),
    LONG_TERM("long_term"),

    /** A horizon this build does not know. Treated as neither. Never sent. */
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): GoalHorizon = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object GoalHorizonSerializer :
    WireEnumSerializer<GoalHorizon>("GoalHorizon", { GoalHorizon.fromWire(it) }, { it.wire })

/** Why there is no budget comparison. */
@Serializable(with = GoalsBudgetReasonSerializer::class)
enum class GoalsBudgetReason(val wire: String) {
    /** Budgets are off for this household. */
    UNAVAILABLE("unavailable"),

    /** Still learning: there is no generated budget yet (PRD F8). Goals themselves still work. */
    LEARNING("learning"),

    /** This month's budget has no savings line — nothing left after the other lines. */
    NO_SAVINGS_LINE("no_savings_line"),

    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): GoalsBudgetReason = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object GoalsBudgetReasonSerializer :
    WireEnumSerializer<GoalsBudgetReason>("GoalsBudgetReason", { GoalsBudgetReason.fromWire(it) }, { it.wire })

/**
 * A goal as it will be created — built by `GoalEdit` from a draft that has
 * passed its blocking reason, never assembled by a screen.
 */
data class NewGoal(
    val name: String,
    val kind: GoalKind?,
    val horizon: GoalHorizon,
    val target: String,
    /** Null when the person left it empty; the server then starts the goal at 0. */
    val saved: String?,
    val targetDate: String?,
    val monthlyContribution: String?,
)

/**
 * What an edit changes, field by field — built by `GoalEdit` from the goal as
 * it was and the draft, so only what the person actually changed is sent.
 *
 * The three optional fields can be **cleared**, which is not the same as
 * leaving them alone: the server reads a field that is absent as "unchanged"
 * and only an explicit null as "remove it" (backend #65). Plain nullable
 * fields cannot say both, and the shared JSON drops nulls on the way out
 * (`explicitNulls = false`), so a removed target date would quietly survive.
 * Hence the `clear…` flags, and a request body written by hand.
 *
 * Every other field is null for "unchanged": name, horizon, target and saved
 * cannot be cleared, and the server refuses a null for them.
 */
data class GoalChanges(
    val name: String? = null,
    val horizon: GoalHorizon? = null,
    val target: String? = null,
    val saved: String? = null,
    val kind: GoalKind? = null,
    val clearKind: Boolean = false,
    val targetDate: String? = null,
    val clearTargetDate: Boolean = false,
    val monthlyContribution: String? = null,
    val clearMonthlyContribution: Boolean = false,
) {
    /** True when nothing would change — not worth a request. */
    val isEmpty: Boolean
        get() = name == null && horizon == null && target == null && saved == null &&
            kind == null && !clearKind && targetDate == null && !clearTargetDate &&
            monthlyContribution == null && !clearMonthlyContribution
}
