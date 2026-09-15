package com.humblesolutions.finai.model

import com.humblesolutions.finai.i18n.Strings

/**
 * Every way a call to the Render API can fail. Screens branch on these — never
 * on HTTP status codes — and show [messageKey].
 *
 * Messages are for developers and deliberately carry no response body: bodies
 * hold financial data, and exception messages end up in logs and crash reports
 * (CLAUDE.md → Security & privacy).
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The i18n key a screen shows for this failure. */
    abstract val messageKey: String

    /** No valid session: signed out, or a token the server rejected even after a refresh. */
    class Unauthorized(detail: String) : ApiException("unauthorized: $detail") {
        override val messageKey: String = Strings.error_unauthorized
    }

    /** The server refused [feature] for this household; [reason] says why. */
    class FeatureUnavailable(val feature: String, val reason: FeatureReason) :
        ApiException("feature unavailable: $feature (${reason.wire})") {
        override val messageKey: String = Strings.error_feature_unavailable
    }

    /** Forbidden for a reason other than feature gating. */
    class Forbidden : ApiException("forbidden") {
        override val messageKey: String = Strings.error_forbidden
    }

    class NotFound : ApiException("not found") {
        override val messageKey: String = Strings.error_not_found
    }

    /** The server rejected what was sent (400, 409, 422). */
    class Validation(val status: Int) : ApiException("rejected with $status") {
        override val messageKey: String = Strings.error_validation
    }

    /**
     * The phone number already belongs to another account.
     *
     * Raised by the API (`phone_already_linked`) and by Supabase
     * (`AuthErrorCode.PhoneExists`), which may refuse first — the number is the
     * identity key, so this is how one person is stopped from becoming two
     * households. Merging two sign-in methods is a follow-up, so the only way
     * on is to sign in with the number instead.
     */
    /** The number itself was refused — usually the wrong country code in front of it. */
    class InvalidPhone : ApiException("phone number rejected") {
        override val messageKey: String = Strings.error_invalid_phone
    }

    /** The six digits were wrong, or the code has expired. */
    class InvalidCode : ApiException("verification code rejected") {
        override val messageKey: String = Strings.error_invalid_code
    }

    /** Too many codes requested. Supabase rate-limits SMS per number. */
    class TooManyAttempts : ApiException("sms rate limit reached") {
        override val messageKey: String = Strings.error_too_many_attempts
    }

    /** Phone sign-in is switched off for this Supabase project. */
    class SignInMethodUnavailable : ApiException("sign-in method disabled") {
        override val messageKey: String = Strings.error_provider_not_configured
    }

    class PhoneAlreadyLinked : ApiException("phone already linked to another account") {
        override val messageKey: String = Strings.error_phone_already_linked
    }

    /**
     * The terms changed between being shown and being accepted, so consent was
     * refused rather than recorded against text the user never read. Reload the
     * terms and ask again; [currentVersion] is the one now in force.
     */
    class TermsChanged(val currentVersion: String?) :
        ApiException("terms version mismatch") {
        override val messageKey: String = Strings.error_terms_changed
    }

    class Server(val status: Int) : ApiException("server error $status") {
        override val messageKey: String = Strings.error_server
    }

    /** No response arrived: offline, timed out, DNS or TLS failure. */
    class Network(cause: Throwable) :
        ApiException("network failure: ${cause::class.simpleName}", cause) {
        override val messageKey: String = Strings.error_network
    }

    /** Supabase is not configured in this build, so there is no session to send. */
    class NotConfigured : ApiException("supabase is not configured") {
        override val messageKey: String = Strings.error_not_configured
    }

    /** A response this build cannot interpret: an unexpected status, or a body that would not decode. */
    class Unexpected(val status: Int?, cause: Throwable? = null) :
        ApiException("unexpected response" + (status?.let { " $it" } ?: ""), cause) {
        override val messageKey: String = Strings.error_unexpected
    }
}
