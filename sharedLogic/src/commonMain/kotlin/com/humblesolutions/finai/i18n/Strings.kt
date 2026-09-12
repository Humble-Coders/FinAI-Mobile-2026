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

    // Shared actions.
    const val action_continue = "action_continue" // "Continue"
    const val action_back = "action_back" // "Back"
    const val action_retry = "action_retry" // "Try again"
    const val action_change = "action_change" // "Change"
    const val action_sign_out = "action_sign_out" // "Sign out"

    // Splash and launch.
    const val app_tagline = "app_tagline" // "AI-Powered Personal Finance"
    const val splash_slow = "splash_slow" // "Still working — the server may be waking up."

    // Welcome: phone entry and the provider routes.
    const val welcome_phone_label = "welcome_phone_label" // "Phone number"
    const val welcome_phone_hint = "welcome_phone_hint" // "416 555 0100"
    const val welcome_dial_code_label = "welcome_dial_code_label" // "Country calling code"
    const val welcome_or = "welcome_or" // "or"
    const val welcome_google = "welcome_google" // "Continue with Google"
    const val welcome_code_notice = "welcome_code_notice" // "We'll text a code to confirm it's you"

    // The phone step after a Google or Apple sign-in.
    const val phone_link_title = "phone_link_title" // "Add your phone number"
    const val phone_link_body = "phone_link_body" // "Your number confirms it's you and sets your region."

    // Code entry.
    const val code_title = "code_title" // "Enter the code"
    const val code_sent_to = "code_sent_to" // "Sent to {0}"
    const val code_verify = "code_verify" // "Verify"
    const val code_resend = "code_resend" // "Resend code"
    const val code_resend_in = "code_resend_in" // "Resend in {0}"
    const val code_wrong_number = "code_wrong_number" // "Wrong number? Go back and edit it."

    // Region, asked only when the server could not place the number.
    const val region_title = "region_title" // "Which country are you in?"
    const val region_body = "region_body" // "We couldn't tell from your number. This decides which accounts and disclaimers we show you."
    const val region_label = "region_label" // "Country"

    // Consent.
    const val consent_title = "consent_title" // "Before you start"
    const val consent_subtitle = "consent_subtitle" // "Educational guidance, not regulated financial advice."
    const val consent_region_label = "consent_region_label" // "Your region"
    const val consent_agree = "consent_agree" // "Agree and continue"

    // Home placeholder, until M4.
    const val home_title = "home_title" // "You're all set"
    const val home_body = "home_body" // "Your dashboard arrives with the first milestone of insights."

    // A step this build does not know.
    const val update_required_title = "update_required_title" // "Update FinAI to continue"
    const val update_required_body = "update_required_body" // "This version doesn't know one of the steps your account still needs. Update the app to finish setting up."

    // The financial setup step, until 2.4 builds the wizard behind it.
    const val setup_pending_title = "setup_pending_title" // "One more step"
    const val setup_pending_body = "setup_pending_body" // "We need a couple of figures before your dashboard can say anything useful. This step arrives in the next update."

    // A provider sign-in that failed for a reason of its own.
    const val error_provider_failed = "error_provider_failed" // "That sign-in didn't complete. Try again, or use your phone number."
    const val error_provider_not_configured = "error_provider_not_configured" // "This build isn't set up for that sign-in method yet."

    // Failure to load.
    const val error_title = "error_title" // "Something went wrong"


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
