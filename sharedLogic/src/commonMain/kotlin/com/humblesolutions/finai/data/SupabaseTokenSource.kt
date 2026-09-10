@file:OptIn(ExperimentalTime::class)

package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.SessionTokenSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The bearer token for Render API calls, taken from the Supabase session.
 *
 * Phone OTP, Google and Apple all yield the same kind of Supabase session, so
 * the client only ever sends whatever the current session holds.
 */
class SupabaseTokenSource(client: SupabaseClient) : SessionTokenSource {

    private val auth = client.auth

    // Serialises refreshes. Supabase rotates the refresh token on use, so
    // concurrent requests that each found the token stale must not each spend
    // it; whoever takes the lock second finds the session already refreshed.
    private val refreshLock = Mutex()

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? {
        // The session loads from storage asynchronously at startup.
        auth.awaitInitialization()
        val session = auth.currentSessionOrNull() ?: return null
        val stale = TokenFreshness.isStale(session.expiresAt.epochSeconds, Clock.System.now().epochSeconds)
        return if (stale) refresh(session.accessToken) else session.accessToken
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? {
        val current = auth.currentSessionOrNull()?.accessToken ?: return null
        // Refresh against the token the server turned down, not whatever the
        // session holds now — if another request already replaced it, the check
        // in refresh() hands that one back instead of spending the refresh token.
        return refresh(rejected ?: current)
    }

    private suspend fun refresh(staleToken: String): String? = refreshLock.withLock {
        val current = auth.currentSessionOrNull() ?: return@withLock null
        // Someone refreshed while we waited for the lock: use theirs.
        if (current.accessToken != staleToken) return@withLock current.accessToken
        try {
            auth.refreshCurrentSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SupabaseErrors.map(e, whileRefreshing = true)
        }
        auth.currentSessionOrNull()?.accessToken
    }
}
