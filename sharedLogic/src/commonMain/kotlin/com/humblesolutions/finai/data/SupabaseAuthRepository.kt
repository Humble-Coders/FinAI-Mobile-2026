@file:OptIn(ExperimentalTime::class)

package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.repository.AuthRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * The session from Supabase Auth, the caller from the Render API.
 *
 * Supabase does exactly two things in this app — Auth and Storage — so this
 * uses it only for the session. Everything else, `/me` included, goes to the
 * Render API with the session's token. Owns its HTTP client: call [close].
 */
class SupabaseAuthRepository internal constructor(
    client: SupabaseClient,
    private val http: HttpClient,
) : AuthRepository {

    constructor(client: SupabaseClient, baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(client, FinAiHttpClient.create(baseUrl, tokens, logging))

    private val auth = client.auth

    override val sessionState: Flow<SessionState> = auth.sessionStatus
        .map { status ->
            when (status) {
                is SessionStatus.Authenticated -> SessionState.SIGNED_IN
                is SessionStatus.NotAuthenticated -> SessionState.SIGNED_OUT
                is SessionStatus.RefreshFailure -> SessionState.REFRESH_FAILED
                else -> SessionState.LOADING
            }
        }
        .distinctUntilChanged()

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun requestPhoneCode(phone: String) {
        val number = phone
        callSupabase { auth.signInWith(OTP) { this.phone = number } }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun verifyPhoneCode(phone: String, code: String) {
        callSupabase { auth.verifyPhoneOtp(OtpType.Phone.SMS, phone, code) }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun signOut() {
        callSupabase { auth.signOut() }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun me(): Me = http.getJson("me")

    /**
     * Throwaway demo aid (ticket #6): marks the current access token as expired,
     * so the next API call must refresh it first — the live check that a stale
     * token is never sent. Not on [AuthRepository]; M2 removes it with the demo.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun expireAccessTokenForTesting() {
        val session = auth.currentSessionOrNull() ?: throw ApiException.Unauthorized("no session to expire")
        val expired = session.copy(expiresIn = 0, expiresAt = Clock.System.now() - 1.minutes)
        // autoRefresh = false, so it is this app's refresh path that must notice, not the SDK's timer.
        callSupabase { auth.importSession(expired, false) }
    }

    /**
     * Throwaway demo aid (ticket #6): seconds until the current access token
     * expires — negative once it has — or null when signed out. Read before and
     * after a load on the demo screen, as evidence that a refresh really
     * happened: a successful request alone cannot show it, because a locally
     * expired token is still accepted by the server. Removed with the demo.
     */
    fun tokenSecondsLeftForTesting(): Long? =
        auth.currentSessionOrNull()?.let { it.expiresAt.epochSeconds - Clock.System.now().epochSeconds }

    override fun close() = http.close()

    private suspend fun <T> callSupabase(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SupabaseErrors.map(e, whileRefreshing = false)
    }
}
