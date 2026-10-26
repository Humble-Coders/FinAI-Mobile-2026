package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One run of a Your-plan diagnostic: the server's figures, and the AI's words
 * about them. Figures are decimal strings; the words carry no figures.
 *
 * [kind] is `allocation` (pay debt, fund goals, or keep in savings?) or
 * `invest` (should I invest, and how much?). [stored] is true when nothing had
 * changed and the last run was returned.
 */
@Serializable
data class PlanDiagnostic(
    val kind: String = "",
    @SerialName("made_at")
    val madeAt: String = "",
    val stored: Boolean = false,
    val currency: String = "",
    val leftover: String = "0",
    @SerialName("savings_target")
    val savingsTarget: String = "0",
    @SerialName("savings_now")
    val savingsNow: String = "0",
    @SerialName("savings_gap")
    val savingsGap: String = "0",
    @SerialName("to_savings")
    val toSavings: String = "0",
    @SerialName("to_goals")
    val toGoals: String = "0",
    @SerialName("to_debt")
    val toDebt: String = "0",
    @SerialName("to_invest")
    val toInvest: String = "0",
    @SerialName("rate_line_percent")
    val rateLinePercent: String? = null,
    @SerialName("assumed_return_percent")
    val assumedReturnPercent: String? = null,
    val goals: List<DiagnosticGoal> = emptyList(),
    @SerialName("debt_first")
    val debtFirst: String? = null,
    @SerialName("debt_first_rate_percent")
    val debtFirstRatePercent: String? = null,
    @SerialName("interest_saved")
    val interestSaved: String? = null,
    @SerialName("debt_free")
    val debtFree: String? = null,
    @SerialName("debt_free_minimums")
    val debtFreeMinimums: String? = null,
    @SerialName("per_100")
    val per100: String? = null,
    @SerialName("debt_per_100")
    val debtPer100: String? = null,
    @SerialName("invest_per_100")
    val investPer100: String? = null,
    @SerialName("buffer_full")
    val bufferFull: Boolean? = null,
    @SerialName("expensive_debts")
    val expensiveDebts: List<String> = emptyList(),
    @SerialName("goals_covered")
    val goalsCovered: Boolean? = null,
    @SerialName("existing_investments")
    val existingInvestments: String? = null,
    val disclaimer: String? = null,
    val headline: String? = null,
    val steps: List<DiagnosticStep> = emptyList(),
    val note: String? = null,
    val why: String? = null,
    val where: String? = null,
    val next: String? = null,
) {
    val isAllocation: Boolean get() = kind == PlanDiagnostics.ALLOCATION
}

@Serializable
data class DiagnosticGoal(
    val id: String = "",
    val name: String = "",
    val amount: String = "0",
)

@Serializable
data class DiagnosticStep(
    /** savings, goals, debt or invest. */
    val to: String = "",
    val why: String = "",
)

/** The last run of each, the savings figures the form starts with, and fresh runs left this month. */
@Serializable
data class PlanDiagnostics(
    val allocation: PlanDiagnostic? = null,
    val invest: PlanDiagnostic? = null,
    val currency: String = "",
    @SerialName("savings_target")
    val savingsTarget: String = "0",
    @SerialName("savings_now")
    val savingsNow: String = "0",
    @SerialName("runs_left")
    val runsLeft: Map<String, Int> = emptyMap(),
) {
    fun last(kind: String): PlanDiagnostic? = if (kind == ALLOCATION) allocation else invest

    fun runsLeftFor(kind: String): Int = runsLeft[kind] ?: 0

    companion object {
        const val ALLOCATION = "allocation"
        const val INVEST = "invest"
    }
}

@Serializable
internal data class DiagnosticBody(
    @SerialName("savings_target")
    val savingsTarget: String? = null,
    @SerialName("savings_now")
    val savingsNow: String? = null,
)
