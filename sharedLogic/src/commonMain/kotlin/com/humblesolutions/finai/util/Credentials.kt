package com.humblesolutions.finai.util

import com.humblesolutions.finai.i18n.Strings

/** Why an email and password cannot be submitted yet. */
enum class CredentialsProblem(val messageKey: String) {
    EMAIL_INVALID(Strings.error_invalid_email),
    PASSWORD_MISSING(Strings.welcome_password_label),
    PASSWORD_TOO_SHORT(Strings.welcome_password_rule),
}

/**
 * The client-side checks on an email and password — the single source for the
 * disabled button on both platforms.
 *
 * Deliberately shallow. Supabase decides what a valid address and an
 * acceptable password are, including rejecting leaked passwords; these checks
 * only stop a request that is certain to fail. Being stricter than the server
 * would lock out a valid address.
 */
object Credentials {

    /**
     * Keep in step with Supabase → Authentication → Password settings. A lower
     * server minimum is harmless; a higher one surfaces as
     * [com.humblesolutions.finai.model.ApiException.WeakPassword].
     */
    const val MIN_PASSWORD_LENGTH = 8

    /** What is sent: surrounding whitespace removed, nothing else changed. */
    fun normalizeEmail(raw: String): String = raw.trim()

    /** One `@`, something before it, and a dot somewhere in what follows. */
    fun looksLikeEmail(raw: String): Boolean {
        val email = normalizeEmail(raw)
        if (email.any { it.isWhitespace() }) return false
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@')) return false
        val domain = email.substring(at + 1)
        val dot = domain.indexOf('.')
        return dot > 0 && dot < domain.length - 1
    }

    /**
     * @param creating true for a new password (signup, reset), which must meet
     *   the minimum. Signing in only needs one typed: an existing password was
     *   set under whatever rule applied then, and must still be accepted.
     */
    fun problem(email: String, password: String, creating: Boolean): CredentialsProblem? = when {
        !looksLikeEmail(email) -> CredentialsProblem.EMAIL_INVALID
        else -> passwordProblem(password, creating)
    }

    fun passwordProblem(password: String, creating: Boolean): CredentialsProblem? = when {
        password.isEmpty() -> CredentialsProblem.PASSWORD_MISSING
        creating && password.length < MIN_PASSWORD_LENGTH -> CredentialsProblem.PASSWORD_TOO_SHORT
        else -> null
    }
}
