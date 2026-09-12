package com.humblesolutions.finai.model

import kotlinx.serialization.Serializable

/**
 * The account terms in force, from `GET /legal/terms` (PRD Appendix A.5).
 *
 * The client shows [body] and, on acceptance, sends back the [version] it
 * showed. If the terms changed in between the API refuses with
 * [ApiException.TermsChanged] rather than record consent to text the user never
 * read — so never cache this across an acceptance.
 *
 * Defaults on every field, for wire tolerance.
 */
@Serializable
data class Terms(
    val version: String = "",
    val body: String = "",
)
