package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Dashboard
import kotlin.coroutines.cancellation.CancellationException

/** The monthly overview (PRD F3), computed by the server so both apps agree. */
interface DashboardRepository {

    /**
     * One month, or the current one when [month] is null.
     *
     * [month] is `YYYY-MM`. A month with nothing in it is a normal result with
     * zeroes, not an error: having no transactions yet is a state of the
     * account, and a screen should not meet its own first month as a failure.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun read(month: String? = null): Dashboard

    fun close()
}
