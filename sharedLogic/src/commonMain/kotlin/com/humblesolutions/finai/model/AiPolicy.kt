package com.humblesolutions.finai.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The AI-processing policy in force — `GET /legal/ai-processing`.
 *
 * Its own consent, separate from the account terms: express and unbundled
 * (PRD Appendix A.5 §1). The text is the server's, so it can change without an
 * app release; the app shows it and records which [version] was shown.
 */
@Serializable
data class AiPolicy(
    val version: String = "",
    val body: String = "",
    @SerialName("effective_from")
    val effectiveFrom: String? = null,
)

/** `GET /legal/ai-processing/consent` — where this person stands now (#42). */
@Serializable
data class AiConsentStatus(
    val consented: Boolean = false,
    /** The policy in force, or null when none is configured. */
    val version: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class AiConsentIn(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: String = "",
)

@Serializable
internal data class AiConsentAccepted(val version: String = "")
