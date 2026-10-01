package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Consent to AI processing: read the policy, agree to the version shown, and
 * ask where the person stands. Used by the import screen (#31) before the first
 * import, and by the Settings row (#42) to withdraw and give it again.
 */
interface AiConsentRepository {

    @Throws(ApiException::class, CancellationException::class)
    suspend fun policy(): AiPolicy

    /**
     * Agree to [version] — the one the person was actually shown. Raises
     * [ApiException.AiPolicyChanged] if it is no longer in force, so the
     * screen reloads and asks again rather than record consent to unread text.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun consent(version: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun status(): AiConsentStatus

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
