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

    /**
     * An amount the server would not store, named by its path in the request
     * (`debts.0.balance`). The path is what lets the wizard highlight the row
     * the user typed rather than reddening the whole form.
     */
    class InvalidAmount(val field: String?) : ApiException("amount rejected: " + (field ?: "unknown")) {
        override val messageKey: String = Strings.error_invalid_amount
    }

    /** The server rejected what was sent (400, 409, 422). */
    class Validation(val status: Int) : ApiException("rejected with $status") {
        override val messageKey: String = Strings.error_validation
    }

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

    /** A sign-in method is switched off for this Supabase project. */
    class SignInMethodUnavailable : ApiException("sign-in method disabled") {
        override val messageKey: String = Strings.error_provider_not_configured
    }

    /**
     * The phone number already belongs to another account.
     *
     * Raised by the API (`phone_already_linked`) and by Supabase
     * (`AuthErrorCode.PhoneExists`), which may refuse first — the number is the
     * identity key, so this is how one person is stopped from becoming two
     * households. The phone step shows it and asks for another number; it never
     * offers to sign in to, or link with, the account that has it.
     */
    class PhoneAlreadyLinked : ApiException("phone already linked to another account") {
        override val messageKey: String = Strings.error_phone_already_linked
    }

    /** Creating an account with an email that already has one. */
    class EmailAlreadyRegistered : ApiException("email already registered") {
        override val messageKey: String = Strings.error_email_taken
    }

    /**
     * Signing in to an account whose email was never confirmed. The app answers
     * by sending a fresh code rather than showing this.
     */
    class EmailNotConfirmed : ApiException("email not confirmed") {
        override val messageKey: String = Strings.error_email_not_confirmed
    }

    /** Email and password do not match. Deliberately does not say which is wrong. */
    class WrongCredentials : ApiException("invalid login credentials") {
        override val messageKey: String = Strings.error_wrong_credentials
    }

    /** The password is too short, too simple, reused, or known to be leaked. */
    class WeakPassword : ApiException("password rejected") {
        override val messageKey: String = Strings.error_weak_password
    }

    class InvalidEmail : ApiException("email address rejected") {
        override val messageKey: String = Strings.error_invalid_email
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
