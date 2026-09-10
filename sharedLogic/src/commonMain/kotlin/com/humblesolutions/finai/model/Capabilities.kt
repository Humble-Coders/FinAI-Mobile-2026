package com.humblesolutions.finai.model

import com.humblesolutions.finai.i18n.Strings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What this household may see — the payload both apps render from (PRD §4.6).
 *
 * Clients render what it says; there is no per-country branch anywhere in
 * client code. It controls what is SHOWN — the API independently enforces what
 * is ALLOWED, so a hidden feature is never assumed to be a secured one.
 *
 * Every field has a default, so a payload missing a field still decodes.
 */
@Serializable
data class Capabilities(
    /** ISO country code, or null until the phone step completes — never guessed. */
    val region: String? = null,
    val currency: String = "",
    val locale: String = "",
    /**
     * Keyed by feature key. Keys stay strings: adding a feature is a database row
     * on the server, and an enum here would turn every new one into a release.
     */
    val features: Map<String, Feature> = emptyMap(),
    val content: CapabilityContent = CapabilityContent(),
    @SerialName("onboarding_required")
    val onboardingRequired: List<OnboardingStep> = emptyList(),
) {
    /** False for any key the payload does not mention: an unknown feature stays hidden. */
    fun isEnabled(featureKey: String): Boolean = features[featureKey]?.enabled == true

    /**
     * Why [featureKey] is unavailable, or null when it is enabled — the single
     * source for a disabled control and its explanation (blocking-reason pattern).
     */
    fun blockingReason(featureKey: String): FeatureReason? {
        val feature = features[featureKey] ?: return FeatureReason.UNKNOWN_FEATURE
        return if (feature.enabled) null else feature.reason ?: FeatureReason.UNKNOWN
    }

    val needsOnboarding: Boolean get() = onboardingRequired.isNotEmpty()
}

@Serializable
data class Feature(
    /** Missing means off: when in doubt, a feature stays hidden. */
    val enabled: Boolean = false,
    /** Why it is off; null when enabled. */
    val reason: FeatureReason? = null,
)

/**
 * Region-specific copy the server supplies. Typed rather than a raw JSON tree —
 * raw trees cross the Swift bridge badly — with the keys the API sends today and
 * any other key ignored.
 */
@Serializable
data class CapabilityContent(
    @SerialName("tax_accounts")
    val taxAccounts: List<String> = emptyList(),
    @SerialName("disclaimer_version")
    val disclaimerVersion: String? = null,
)

@Serializable(with = FeatureReasonSerializer::class)
enum class FeatureReason(val wire: String, val messageKey: String) {
    COMING_SOON("coming_soon", Strings.feature_reason_coming_soon),
    NOT_IN_PLAN("not_in_plan", Strings.feature_reason_not_in_plan),
    REGION_UNSUPPORTED("region_unsupported", Strings.feature_reason_region_unsupported),
    REGION_UNKNOWN("region_unknown", Strings.feature_reason_region_unknown),
    UNKNOWN_FEATURE("unknown_feature", Strings.feature_reason_unavailable),

    /** A reason this build does not know yet. */
    UNKNOWN("", Strings.feature_reason_unavailable);

    companion object {
        fun fromWire(value: String?): FeatureReason =
            entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

@Serializable(with = OnboardingStepSerializer::class)
enum class OnboardingStep(val wire: String) {
    PHONE("phone"),

    /** A step this build does not know yet — kept, so the app still knows onboarding is incomplete. */
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): OnboardingStep =
            entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object FeatureReasonSerializer :
    WireEnumSerializer<FeatureReason>("FeatureReason", { FeatureReason.fromWire(it) }, { it.wire })

internal object OnboardingStepSerializer :
    WireEnumSerializer<OnboardingStep>("OnboardingStep", { OnboardingStep.fromWire(it) }, { it.wire })
