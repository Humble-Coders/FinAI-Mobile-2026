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
 * Three ways in: email and password, Google, and Apple (manager decision,
 * 2026-09-15 — PRD §9). Whichever is used, the account then verifies a phone
 * number once (PRD §4.6): it is the key that stops one person becoming two
 * households, and what the server derives the region from. The number is never
 * a way to sign in.
 *
 * Every public suspend function declares `@Throws`: from Swift an undeclared
 * Kotlin exception terminates the process (kmp-arch-v2 → SKIE).
 */
interface AuthRepository {

    /** Live session state. SKIE exposes it to Swift as an AsyncSequence. */
    val sessionState: Flow<SessionState>

    // ── Email and password ──────────────────────────────────────────────

    /**
     * Creates an account and emails a code to confirm the address. No session
     * exists until [verifySignupCode] succeeds.
     *
     * Supabase answers an address that already has a confirmed account with
     * an apparent success and sends nothing, so nobody can probe which
     * addresses are registered. The code screen says so.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun signUpWithEmail(email: String, password: String)

    /** Confirms the address with the emailed code, which signs the user in. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun verifySignupCode(email: String, code: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun resendSignupCode(email: String)

    /** Raises [ApiException.EmailNotConfirmed] for an account that never entered its code. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun signInWithEmail(email: String, password: String)

    /** Emails a code that lets the user choose a new password. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun requestPasswordReset(email: String)

    /** Verifies the reset code. This signs the user in, before a new password exists. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun verifyPasswordResetCode(email: String, code: String)

    /** Sets the signed-in user's password — the last step of a reset. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun setNewPassword(password: String)

    // ── Google and Apple ────────────────────────────────────────────────

    /**
     * Signs in with an ID token obtained natively by the platform.
     *
     * @param nonce the RAW nonce, where the provider was given its SHA-256.
     *   Supabase compares them, so sending the hash here fails the check.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun signInWithIdToken(provider: SocialProvider, idToken: String, nonce: String?)

    /**
     * Adds a Google or Apple identity to the signed-in account. Supabase refuses
     * with [ApiException.IdentityInUse] while that identity still belongs to
     * another account, so [removeOrphanAccount] comes first.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun linkProvider(provider: SocialProvider, idToken: String, nonce: String?)

    // ── The phone step ──────────────────────────────────────────────────

    /**
     * Attaches [phone] to the signed-in user and texts a code. Raises
     * [ApiException.PhoneAlreadyLinked] when the number already belongs to
     * someone else.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun requestPhoneLink(phone: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun verifyPhoneLink(phone: String, code: String)

    // ── Linking an orphan ───────────────────────────────────────────────

    /**
     * A freshly refreshed access token for the current session, so it lasts as
     * long as possible — the orphan's proof in a [com.humblesolutions.finai.model.PendingLink].
     * Null when signed out. A credential: never log it.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun currentAccessToken(): String?

    /** The provider the current session signed in with, or null for email (or no session). */
    fun currentSignInProvider(): SocialProvider?

    /**
     * Removes the empty account [orphanToken] belongs to, while signed in to
     * the real one. Returns this account's `/me`.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun removeOrphanAccount(orphanToken: String): Me

    // ── Session and onboarding ──────────────────────────────────────────

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
