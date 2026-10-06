package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.model.GoalChanges
import com.humblesolutions.finai.model.GoalsPage
import com.humblesolutions.finai.model.NewGoal
import com.humblesolutions.finai.model.Terms
import kotlin.coroutines.cancellation.CancellationException

/**
 * The household's savings goals and the server's projection of each (PRD F5,
 * backend #65). Bounded — at most 20 open — so a screen reads them whole.
 */
interface GoalsRepository {

    /** Every goal in priority order, with the budget comparison and what to disclaim. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(): GoalsPage

    @Throws(ApiException::class, CancellationException::class)
    suspend fun create(goal: NewGoal): Goal

    /** Apply [changes]. A goal re-opened by the edit can meet the limit too: [ApiException.GoalLimitReached]. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun update(id: String, changes: GoalChanges): Goal

    /** Add [amount] to what is saved. Atomic on the server: two adds at once both land. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun add(id: String, amount: String): Goal

    /** Set the order. [ids] must be every goal exactly once, or [ApiException.OrderMismatch]. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun reorder(ids: List<String>): GoalsPage

    @Throws(ApiException::class, CancellationException::class)
    suspend fun delete(id: String)

    /**
     * The region's not-financial-advice disclaimer, from the server (PRD §4.6),
     * or null when the region has none. "None" is an answer, not a failure.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun disclaimer(): Terms?

    fun close()
}
