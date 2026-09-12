@file:OptIn(ExperimentalTime::class)

package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AuthRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    override suspend fun signInWithIdToken(
        provider: SocialProvider,
        idToken: String,
        nonce: String?,
    ) {
        val token = idToken
        val rawNonce = nonce
        val idTokenProvider = when (provider) {
            SocialProvider.GOOGLE -> Google
            SocialProvider.APPLE -> Apple
        }
        callSupabase {
            auth.signInWith(IDToken) {
                this.idToken = token
                this.provider = idTokenProvider
                this.nonce = rawNonce
            }
        }
    }

    /**
     * Adds the number to the signed-in user, which is what sends the code.
     *
     * Note this is `updateUser`, not a second sign-in: the session already
     * exists from the provider, and the number is being attached to it. That is
     * also why verification uses PHONE_CHANGE rather than SMS below.
     */
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun requestPhoneLink(phone: String) {
        val number = phone
        callSupabase { auth.updateUser { this.phone = number } }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun verifyPhoneLink(phone: String, code: String) {
        callSupabase { auth.verifyPhoneOtp(OtpType.Phone.PHONE_CHANGE, phone, code) }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun signOut() {
        callSupabase { auth.signOut() }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun me(): Me = http.getJson("me")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun terms(): Terms = http.getJson("legal/terms")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun setRegion(countryCode: String): Me =
        http.putJson("me/region", RegionIn(countryCode))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun acceptTerms(version: String): Me =
        http.postJson("me/consent", ConsentIn(version))

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

@Serializable
private data class RegionIn(@SerialName("country_code") val countryCode: String)

@Serializable
private data class ConsentIn(val version: String)
