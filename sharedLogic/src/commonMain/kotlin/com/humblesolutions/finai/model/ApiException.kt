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
