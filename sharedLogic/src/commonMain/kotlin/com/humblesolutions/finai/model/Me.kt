package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The caller and their household, from `GET /me`. Defaults on every field for wire tolerance. */
@Serializable
data class Me(
    val user: MeUser = MeUser(),
    val household: MeHousehold = MeHousehold(),
    @SerialName("onboarding_required")
    val onboardingRequired: List<OnboardingStep> = emptyList(),
)

/** Personal data — never log it (CLAUDE.md → Security & privacy). */
@Serializable
data class MeUser(
    val id: String = "",
    val email: String? = null,
    val phone: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
)

@Serializable
data class MeHousehold(
    val id: String = "",
    /** Null until the phone step completes — never guessed (PRD §4.6). */
    @SerialName("country_code")
    val countryCode: String? = null,
)
