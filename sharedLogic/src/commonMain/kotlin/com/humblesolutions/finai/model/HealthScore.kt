package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One category's spending in the month, from `/dashboard` (backend #58).
 *
 * The entries sum to the month's expenses exactly; the server makes them so.
 * [categoryId], [slug] and [name] are all null for the one entry gathering
 * every row nothing filed.
 */
@Serializable
data class CategorySpend(
    @SerialName("category_id")
    val categoryId: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val spent: String = "0",
) {
    val isUncategorised: Boolean get() = categoryId == null
}

/**
 * How current Home's figures are (PRD F12).
 *
 * Both are household-wide, whatever month is in view: until bank linking,
 * every figure is only as fresh as the last statement imported.
 */
@Serializable
data class DataFreshness(
    /** The newest transaction's date (`YYYY-MM-DD`), or null with nothing yet. */
    @SerialName("latest_transaction_on")
    val latestTransactionOn: String? = null,

    /** When the newest import that saved rows was made (ISO instant), or null. */
    @SerialName("last_import_at")
    val lastImportAt: String? = null,
)

/**
 * Why the score shown is not today's (backend #62): last month is not imported
 * yet, so the latest score is held.
 *
 * [code] is what a screen words from its own strings; [message] is the
 * server's English line, kept only as a fallback.
 */
@Serializable
data class ScoreNotice(
    val code: String = "",
    /** The month with no data yet, by its first day. */
    val month: String = "",
    val message: String = "",
) {
    val isLastMonthMissing: Boolean get() = code == LAST_MONTH_MISSING

    companion object {
        const val LAST_MONTH_MISSING = "last_month_missing"
    }
}

/**
 * The Money Health Score as `/dashboard` carries it (PRD F6).
 *
 * A day's score, not a month's: the current month carries today's, a past
 * month the last one kept on or before its end. Nothing here is computed on
 * the device — the score and [previousScore] both come from the server.
 */
@Serializable
data class DashboardScore(
    val status: BudgetStatus = BudgetStatus.UNKNOWN,
    val learning: LearningProgress? = null,
    /** 0 to 100; null while learning, or when nothing can be scored yet. */
    val score: Int? = null,
    @SerialName("formula_version")
    val formulaVersion: String? = null,
    /** The day [score] was computed (`YYYY-MM-DD`). */
    @SerialName("scored_on")
    val scoredOn: String? = null,
    /** The last score kept in the month before; null if none. */
    @SerialName("previous_score")
    val previousScore: Int? = null,
    val notice: ScoreNotice? = null,
) {
    val isReady: Boolean get() = status == BudgetStatus.READY
}

/**
 * The score with its parts, from `GET /health-score` (backend #57), fetched
 * only when someone opens the breakdown.
 */
@Serializable
data class HealthScore(
    val status: BudgetStatus = BudgetStatus.UNKNOWN,
    val learning: LearningProgress? = null,
    val score: Int? = null,
    @SerialName("formula_version")
    val formulaVersion: String? = null,
    val components: List<ScoreComponent> = emptyList(),
    val notice: ScoreNotice? = null,
    @SerialName("held_from")
    val heldFrom: String? = null,
)

/**
 * One part of the score.
 *
 * An unavailable part is not a zero: the server could not score it (no
 * budget last month, say) and left it out, renormalising the others. A screen
 * says "not counted yet" rather than drawing it at 0.
 */
@Serializable
data class ScoreComponent(
    /** `savings_consistency`, `spending_vs_budget`, `debt_payments`; more as the formula grows. */
    val key: String = "",
    /** 0 to 100, or null when [available] is false. */
    val score: Int? = null,
    /** Its share of the score after renormalising, as a decimal string ("61.54"). */
    val weight: String = "0",
    val available: Boolean = false,
)
