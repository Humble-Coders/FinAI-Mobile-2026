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
import io.github.jan.supabase.auth.providers.IDTokenProvider
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

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
    override suspend fun signUpWithEmail(email: String, password: String) {
        val address = email
        val secret = password
        callSupabase(SupabaseCall.EMAIL) {
            auth.signUpWith(Email) {
                this.email = address
                this.password = secret
            }
        }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun verifySignupCode(email: String, code: String) {
        callSupabase(SupabaseCall.CODE) {
            auth.verifyEmailOtp(type = OtpType.Email.SIGNUP, email = email, token = code)
        }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun resendSignupCode(email: String) {
        callSupabase(SupabaseCall.EMAIL) { auth.resendEmail(OtpType.Email.SIGNUP, email) }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun signInWithEmail(email: String, password: String) {
        val address = email
        val secret = password
        callSupabase(SupabaseCall.EMAIL) {
            auth.signInWith(Email) {
                this.email = address
                this.password = secret
            }
        }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun requestPasswordReset(email: String) {
        callSupabase(SupabaseCall.EMAIL) { auth.resetPasswordForEmail(email) }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun verifyPasswordResetCode(email: String, code: String) {
        callSupabase(SupabaseCall.CODE) {
            auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email, token = code)
        }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun setNewPassword(password: String) {
        val secret = password
        callSupabase(SupabaseCall.PASSWORD) { auth.updateUser { this.password = secret } }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun signInWithIdToken(
        provider: SocialProvider,
        idToken: String,
        nonce: String?,
    ) {
        val token = idToken
        val rawNonce = nonce
        val idTokenProvider = provider.idTokenProvider()
        callSupabase(SupabaseCall.PROVIDER) {
            auth.signInWith(IDToken) {
                this.idToken = token
                this.provider = idTokenProvider
                this.nonce = rawNonce
            }
        }
    }

    /** Needs "manual linking" enabled in Supabase, or it fails as SignInMethodUnavailable. */
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun linkProvider(provider: SocialProvider, idToken: String, nonce: String?) {
        val rawNonce = nonce
        callSupabase(SupabaseCall.PROVIDER) {
            auth.linkIdentityWithIdToken(provider.idTokenProvider(), idToken) { this.nonce = rawNonce }
        }
    }

    /**
     * Adds the number to the signed-in user, which is what sends the code.
     *
     * Note this is `updateUser`, not a sign-in: the session already exists, and
     * the number is being attached to it. That is also why verification uses
     * PHONE_CHANGE rather than SMS below.
     */
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun requestPhoneLink(phone: String) {
        val number = phone
        callSupabase(SupabaseCall.PHONE) { auth.updateUser { this.phone = number } }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun verifyPhoneLink(phone: String, code: String) {
        callSupabase(SupabaseCall.CODE) { auth.verifyPhoneOtp(OtpType.Phone.PHONE_CHANGE, phone, code) }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentAccessToken(): String? {
        auth.awaitInitialization()
        if (auth.currentSessionOrNull() == null) return null
        // Refreshed rather than read: the token has to outlive the person
        // signing in to their other account, which can take a while.
        callSupabase { auth.refreshCurrentSession() }
        return auth.currentSessionOrNull()?.accessToken
    }

    override fun currentSignInProvider(): SocialProvider? {
        val metadata = auth.currentSessionOrNull()?.user?.appMetadata ?: return null
        return when ((metadata["provider"] as? JsonPrimitive)?.contentOrNull) {
            "google" -> SocialProvider.GOOGLE
            "apple" -> SocialProvider.APPLE
            else -> null
        }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun removeOrphanAccount(orphanToken: String): Me =
        http.postJson("me/link", LinkIn(orphanToken))

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

    override fun close() = http.close()

    private fun SocialProvider.idTokenProvider(): IDTokenProvider = when (this) {
        SocialProvider.GOOGLE -> Google
        SocialProvider.APPLE -> Apple
    }

    private suspend fun <T> callSupabase(
        call: SupabaseCall = SupabaseCall.OTHER,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SupabaseErrors.map(e, whileRefreshing = false, call = call)
    }
}

@Serializable
private data class RegionIn(@SerialName("country_code") val countryCode: String)

@Serializable
private data class ConsentIn(val version: String)

/** The orphan's access token. A credential — the request body is never logged. */
@Serializable
private data class LinkIn(@SerialName("orphan_token") val orphanToken: String)
