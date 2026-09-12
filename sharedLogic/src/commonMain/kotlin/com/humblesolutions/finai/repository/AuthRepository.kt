package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.model.Terms
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The session, and the onboarding answers the user gives.
 *
 * Every sign-in route ends at a verified phone number (PRD §4.6): it is the
 * identity key that stops one person becoming two households, and it is what
 * the server derives the region from. So a provider sign-in is only half a
 * signup — [signInWithIdToken] creates the session, then [requestPhoneLink] and
 * [verifyPhoneLink] attach the number to it.
 *
 * Every public suspend function declares `@Throws`: from Swift an undeclared
 * Kotlin exception terminates the process (kmp-arch-v2 → SKIE).
 */
interface AuthRepository {

    /** Live session state. SKIE exposes it to Swift as an AsyncSequence. */
    val sessionState: Flow<SessionState>

    /** Sends a one-time code to [phone] — the primary signup route. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun requestPhoneCode(phone: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun verifyPhoneCode(phone: String, code: String)

    /**
     * Signs in with an ID token obtained natively by the platform.
     *
     * @param nonce the RAW nonce, where the provider was given its SHA-256.
     *   Supabase compares them, so sending the hash here fails the check.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun signInWithIdToken(provider: SocialProvider, idToken: String, nonce: String?)

    /**
     * Attaches [phone] to the signed-in user and texts a code — the step after
     * a Google or Apple sign-in. Raises [ApiException.PhoneAlreadyLinked] when
     * the number already belongs to someone else.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun requestPhoneLink(phone: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun verifyPhoneLink(phone: String, code: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun signOut()

    /** The caller and their household, with what onboarding still requires. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun me(): Me

    /** The account terms in force, to show before asking for consent. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun terms(): Terms

    /**
     * Overrides the region when the server could not derive one, or the user
     * disagrees with it. Reachable during onboarding by design: signup is never
     * hard-blocked on region (PRD §4.6).
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun setRegion(countryCode: String): Me

    /**
     * Records consent to [version] — the version the user was actually shown.
     * Raises [ApiException.TermsChanged] if the terms moved on in between;
     * reload them and ask again rather than record consent to unread text.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun acceptTerms(version: String): Me

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
