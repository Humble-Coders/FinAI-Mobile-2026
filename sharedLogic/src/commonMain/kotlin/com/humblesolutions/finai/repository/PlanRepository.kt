package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanSplitEdit
import com.humblesolutions.finai.model.Terms
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the money left over goes (backend #76), gated by `debt_plan`. Every
 * figure is the server's.
 */
interface PlanRepository {

    /** [month] as `YYYY-MM`, or null for this month. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun plan(month: String?): Plan

    /** Keep the person's choices: their order and percentages, and the strategy. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences

    /** The region's not-financial-advice disclaimer, or null when it has none. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun disclaimer(): Terms?

    /** The last run of each diagnostic and what the form starts with. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun diagnostics(): PlanDiagnostics

    /**
     * Run a diagnostic on the person's savings figures (decimal strings).
     * The same figures as last time return the stored run; past four fresh
     * runs a month, [ApiException.DiagnosticLimitReached].
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun runDiagnostic(kind: String, savingsTarget: String?, savingsNow: String?, monthlySavings: String?): PlanDiagnostic

    /**
     * Set one row of this month's split by hand; the others rebalance. With
     * `apply` it is also put where it belongs — a goal's monthly amount, the
     * Budget's Debt line or its savings target. More than the row can take:
     * [ApiException.Rejected] with `over_limit`.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun editSplit(edit: PlanSplitEdit): Plan

    fun close()
}
