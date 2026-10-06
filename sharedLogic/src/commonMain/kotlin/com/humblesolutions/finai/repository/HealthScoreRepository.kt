package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.HealthScore
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Money Health Score with its parts (PRD F6).
 *
 * Home already carries the score itself; this is read only when someone opens
 * the breakdown, so the parts cost nothing until they are wanted.
 */
interface HealthScoreRepository {

    /**
     * Today's score and what it is made of.
     *
     * Still learning is a normal answer (`status: learning`), not an error.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun current(): HealthScore

    fun close()
}
