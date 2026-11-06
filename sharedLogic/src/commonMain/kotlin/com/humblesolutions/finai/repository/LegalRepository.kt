package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Terms
import kotlin.coroutines.cancellation.CancellationException

/**
 * Every text the person agreed to or is shown, and the AI consent, in one
 * place: what Profile reads and changes (PM decision, 2026-11-06 — consents
 * and disclaimers live in Profile, and nowhere else asks in full).
 */
interface LegalRepository {
    /** The account terms in force. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun terms(): Terms

    /** The region's disclaimer, or null when it has none. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun disclaimer(): Terms?

    /** The AI-processing policy in force. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun aiPolicy(): AiPolicy

    @Throws(ApiException::class, CancellationException::class)
    suspend fun aiStatus(): AiConsentStatus

    /** Agree to [version] of the AI policy. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun consentToAi(version: String)

    /** Take the AI consent back. Answers with where the person stands now. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun withdrawAi(): AiConsentStatus

    fun close()
}
