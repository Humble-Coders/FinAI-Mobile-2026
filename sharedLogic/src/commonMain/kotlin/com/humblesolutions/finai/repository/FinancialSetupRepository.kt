package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FinancialSetup
import kotlin.coroutines.cancellation.CancellationException

/**
 * The financial setup wizard's two calls (Finance-backend#25, amended by #29).
 *
 * [save] replaces everything the wizard owns, which is what makes it resumable:
 * the same call repeated leaves the same rows. So **send the whole wizard every
 * time** — omitting income clears it, and that re-raises the onboarding gate.
 *
 * Every public suspend function declares `@Throws`: from Swift an undeclared
 * Kotlin exception terminates the process (kmp-arch-v2 → SKIE).
 */
interface FinancialSetupRepository {

    /** What is saved, and the currency it is denominated in. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun get(): FinancialSetup

    /**
     * Saves the wizard. Idempotent, so it is called after every step rather
     * than once at the end — closing the app then resumes where it left off.
     *
     * Raises [ApiException.InvalidAmount] naming the field the server refused,
     * so the screen can highlight the row the user typed.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun save(setup: FinancialSetup): FinancialSetup

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
