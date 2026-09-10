package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import kotlin.coroutines.cancellation.CancellationException

interface CapabilitiesRepository {

    /** The capabilities payload for the signed-in household. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun fetch(): Capabilities

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
