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

    /**
     * A token to retry with after the server rejected [rejected] — used once per
     * request. If the session has already moved past [rejected] (another request
     * refreshed first), that newer token; otherwise a fresh refresh, regardless
     * of expiry. Null if no session remains.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun refreshedToken(rejected: String?): String?
}
