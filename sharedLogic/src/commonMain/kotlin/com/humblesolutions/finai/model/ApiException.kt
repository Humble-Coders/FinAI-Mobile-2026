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
    class FeatureUnavailable(val feature: String, val reason: FeatureReason) : ApiException("feature unavailable: $feature (${reason.wire})") {
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

    /**
     * The statement was longer than the API accepts (413).
     *
     * The device checks the same limit before sending, so this should be
     * unreachable — but the two copies live in different repositories and the
     * whole reason [com.humblesolutions.finai.config.StatementLimits] exists is
     * that they can drift. When they do, this is the path that carries the
     * news, and it should carry advice rather than "something went wrong".
     */
    class StatementTooLarge : ApiException("statement too large") {
        override val messageKey: String = Strings.statement_too_long
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

    /**
     * A transaction typed in by hand matches one already recorded (#30,
     * Finance-backend #38).
     *
     * Carries the match so the screen can show it — "you already have this" is
     * useful information, and the user decides: keep theirs anyway (resend with
     * `allow_duplicate`) or cancel. Null only if a server that predates the
     * amended 409 answers without the match.
     */
    class DuplicateTransaction(val match: DuplicateMatch?) : ApiException("transaction duplicates an existing one") {
        override val messageKey: String = Strings.manual_entry_duplicate_title
    }

    /**
     * An account name the household already uses. Separate accounts for one real
     * account split its statements into piles that cannot see each other's
     * duplicates, so the server refuses it (Finance-backend 3.3).
     */
    class DuplicateAccountName : ApiException("account name already used") {
        override val messageKey: String = Strings.account_name_taken
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
    class TermsChanged(val currentVersion: String?) : ApiException("terms version mismatch") {
        override val messageKey: String = Strings.error_terms_changed
    }

    // ── The review queue (#32) ──────────────────────────────────────────

    /**
     * A category with that name is already there — the shared taxonomy's or
     * the household's own. [categoryId] is the one that exists, so the picker
     * can simply select it instead of making the person rename theirs.
     */
    class CategoryExists(val categoryId: String?) : ApiException("category already exists") {
        override val messageKey: String = Strings.review_category_exists
    }

    /** A category name with no letter or number in it. */
    class UnnamedCategory : ApiException("category name has no letters") {
        override val messageKey: String = Strings.review_category_unnamed
    }

    /**
     * The correction would make this row a copy of one already recorded — same
     * account, day, amount and description. The screen says which, so the
     * person can delete this one instead.
     */
    class WouldDuplicate(val duplicateOfId: String?) : ApiException("edit would duplicate") {
        override val messageKey: String = Strings.review_would_duplicate
    }

    // ── Importing a statement (#31) ─────────────────────────────────────
    // Each refusal of `POST /statements/parse` gets its own type and words.
    // "Something went wrong" for a quota limit generates support mail; the
    // two size refusals are the cases where the person can actually act.

    /**
     * No consent to AI processing yet, or it was withdrawn (#42). The import
     * screen answers this by showing the consent step, not an error.
     */
    class ConsentRequired(val policyVersion: String?) : ApiException("consent required") {
        override val messageKey: String = Strings.import_consent_required
    }

    /**
     * The AI-processing policy changed between being shown and being agreed
     * to. Reload it and ask again, as with [TermsChanged].
     */
    class AiPolicyChanged(val currentVersion: String?) : ApiException("ai policy version mismatch") {
        override val messageKey: String = Strings.import_consent_changed
    }

    /** This month's imports are used up (429). [resetsAt] is an ISO instant, or null. */
    class ImportQuotaExceeded(val limit: Int?, val resetsAt: String?) : ApiException("import quota exceeded") {
        override val messageKey: String = Strings.import_quota_exceeded
    }

    /** More rows than one import may carry (413 `too_many_transactions`). */
    class TooManyTransactions : ApiException("too many transactions") {
        override val messageKey: String = Strings.import_too_many_transactions
    }

    /**
     * The model failed to read the text (502). Safe to retry: the file is
     * still on the phone, and a failed import does not use up the month.
     */
    class ParseFailed : ApiException("parse failed") {
        override val messageKey: String = Strings.import_parse_failed
    }

    /**
     * Production refuses to send anything to a model until its provider is
     * confirmed not to train on it (503 `ai_processing_unavailable`) — the
     * consent screen promises exactly that. Nothing the person can do but
     * wait, or type transactions in instead.
     */
    class ImportUnavailable : ApiException("ai processing unavailable") {
        override val messageKey: String = Strings.import_unavailable
    }

    class Server(val status: Int) : ApiException("server error $status") {
        override val messageKey: String = Strings.error_server
    }

    /** No response arrived: offline, timed out, DNS or TLS failure. */
    class Network(cause: Throwable) : ApiException("network failure: ${cause::class.simpleName}", cause) {
        override val messageKey: String = Strings.error_network
    }

    /** Supabase is not configured in this build, so there is no session to send. */
    class NotConfigured : ApiException("supabase is not configured") {
        override val messageKey: String = Strings.error_not_configured
    }

    /** A response this build cannot interpret: an unexpected status, or a body that would not decode. */
    class Unexpected(val status: Int?, cause: Throwable? = null) : ApiException("unexpected response" + (status?.let { " $it" } ?: ""), cause) {
        override val messageKey: String = Strings.error_unexpected
    }
}
