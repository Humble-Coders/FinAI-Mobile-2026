package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import kotlin.coroutines.cancellation.CancellationException

/**
 * A month's budget, generated from real spending by the server (PRD F4).
 *
 * **Every call answers with the whole budget, not the part that changed.**
 * The server recomputes the month on a write and returns it, so a save or a
 * reset needs no follow-up read: the totals, the shortfall and every other
 * line's suggestion may all have moved, and a client stitching one line into
 * a stale budget would show figures that never existed together.
 */
interface BudgetRepository {

    /**
     * The budget for [month] (`YYYY-MM`).
     *
     * A household with too little history is a normal result, not an error:
     * the budget comes back with `status: learning` and the progress toward
     * a first one. Any lines the person set by hand come back either way.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun get(month: String): Budget

    /**
     * Set one line by hand. Regeneration will not move it again.
     *
     * Creates the line when the category had none, so a budget can be built
     * by hand before there is enough history to generate one (PRD F9).
     * [amount] is a decimal string.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun setLine(month: String, categoryId: String, amount: String): Budget

    /**
     * Put one line back to its suggestion.
     *
     * A hand-added line with no suggestion behind it is removed instead.
     * Resetting a line that is not there answers [ApiException.NotFound].
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun resetLine(month: String, categoryId: String): Budget

    fun close()
}
