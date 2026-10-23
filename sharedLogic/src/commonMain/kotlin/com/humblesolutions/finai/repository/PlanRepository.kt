package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
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

    /**
     * Keep the person's choices. A buffer makes or updates their
     * emergency-fund goal, so a new one can meet the goal limit:
     * [ApiException.GoalLimitReached].
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences

    /** The region's not-financial-advice disclaimer, or null when it has none. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun disclaimer(): Terms?

    fun close()
}
