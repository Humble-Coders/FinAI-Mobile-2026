package com.humblesolutions.finai.i18n

/**
 * Keys for every user-facing string.
 *
 * Platform code never contains a string literal a user can read — it resolves
 * a key from here (kmp-arch-v2 → i18n). Values live in [EnglishStrings];
 * further languages add their own map.
 */
object Strings {
    const val app_name = "app_name" // "FinAI"
    const val placeholder_title = "placeholder_title" // "FinAI"
    const val placeholder_body = "placeholder_body" // "Foundation in place. Features land in M2."

    // API errors — every ApiException maps to one of these.
    const val error_unauthorized = "error_unauthorized" // "Your session has ended. Please sign in again."
    const val error_feature_unavailable = "error_feature_unavailable" // "This feature isn't available for your account yet."
    const val error_forbidden = "error_forbidden" // "You don't have access to this."
    const val error_not_found = "error_not_found" // "We couldn't find that."
    const val error_validation = "error_validation" // "Something in that request wasn't accepted. Please check it and try again."
    const val error_server = "error_server" // "Something went wrong on our side. Please try again shortly."
    const val error_network = "error_network" // "Can't reach FinAI. Check your connection and try again."
    const val error_not_configured = "error_not_configured" // "This build isn't connected to a backend yet."
    const val error_unexpected = "error_unexpected" // "Something unexpected happened. Please try again."
    const val error_phone_already_linked = "error_phone_already_linked" // "This number already has an account — sign in with your phone number instead."
    const val error_terms_changed = "error_terms_changed" // "The terms have been updated. Please read them again."

    // Why a feature is unavailable — FeatureReason.messageKey.
    const val feature_reason_coming_soon = "feature_reason_coming_soon" // "Coming soon"
    const val feature_reason_not_in_plan = "feature_reason_not_in_plan" // "Not included in your plan"
    const val feature_reason_region_unsupported = "feature_reason_region_unsupported" // "Not available in your region yet"
    const val feature_reason_region_unknown = "feature_reason_region_unknown" // "Available once we know your region"
    const val feature_reason_unavailable = "feature_reason_unavailable" // "Not available"

    // Throwaway demo screen (ticket #6), replaced in M2.
    const val demo_title = "demo_title" // "API check"
    const val demo_phone_label = "demo_phone_label" // "Test phone number"
    const val demo_code_label = "demo_code_label" // "Code"
    const val demo_send_code = "demo_send_code" // "Send code"
    const val demo_verify_code = "demo_verify_code" // "Sign in"
    const val demo_fetch = "demo_fetch" // "Load capabilities"
    const val demo_expire_token = "demo_expire_token" // "Expire token, then load"
    const val demo_sign_out = "demo_sign_out" // "Sign out"
    const val demo_session_loading = "demo_session_loading" // "Checking session…"
    const val demo_signed_out = "demo_signed_out" // "Signed out"
    const val demo_signed_in = "demo_signed_in" // "Signed in"
    const val demo_refresh_failed = "demo_refresh_failed" // "Session refresh failed — will retry"
    const val demo_region = "demo_region" // "Region"
    const val demo_currency = "demo_currency" // "Currency"
    const val demo_locale = "demo_locale" // "Locale"
    const val demo_features = "demo_features" // "Features"
    const val demo_onboarding = "demo_onboarding" // "Still needed"
    const val demo_on = "demo_on" // "On"
    const val demo_value_none = "demo_value_none" // "—"
    const val demo_token_after_expire = "demo_token_after_expire" // "Token life right after expiring (s)"
    const val demo_token_after_load = "demo_token_after_load" // "Token life after the load (s)"
}
