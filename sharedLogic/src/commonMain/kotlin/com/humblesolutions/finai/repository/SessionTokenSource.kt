package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Supplies the bearer token for Render API calls. In the app the Supabase
 * session is the only source; tests substitute a fake.
 */
interface SessionTokenSource {

    /**
     * A token that is valid now — refreshed first if it has expired or is about
     * to, so a request is never sent with a stale token. Null when signed out.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun currentToken(): String?

    /** Refreshes regardless of expiry — used once when the server rejects a token. Null if no session remains. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun refreshedToken(): String?
}
