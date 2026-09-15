package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException

/** Which call failed, so the same error code can be explained in the right words. */
internal enum class SupabaseCall {
    /** Sending a code to a number, or attaching one to an account. */
    PHONE,

    /** Verifying the six digits. */
    CODE,

    /** Exchanging a Google or Apple ID token, or linking one to an account. */
    PROVIDER,

    /** Creating an account, or signing in, with an email and password. */
    EMAIL,

    /** Choosing a new password. */
    PASSWORD,

    OTHER,
}

/**
 * Maps a failure from the Supabase SDK onto [ApiException], so screens see one
 * error type whatever the source.
 *
 * Matched on the SDK's typed [AuthErrorCode], never on message text — messages
 * are not a contract. The [SupabaseCall] matters because one code means
 * different things in different places: `ValidationFailed` on a phone call is
 * a malformed number (nearly always the wrong country code in front of it),
 * while the same code on a provider exchange is a bad token and has nothing to
 * do with anything the user typed.
 */
internal object SupabaseErrors {

    /**
     * @param whileRefreshing a rejected refresh means the session is gone for
     *   good — expired or revoked refresh token — so the only way on is to sign
     *   in again: [ApiException.Unauthorized].
     */
    fun map(
        error: Exception,
        whileRefreshing: Boolean,
        call: SupabaseCall = SupabaseCall.OTHER,
    ): ApiException = when {
        error is ApiException -> error
        error is HttpRequestException -> ApiException.Network(error)
        whileRefreshing -> ApiException.Unauthorized("session refresh rejected")
        error is AuthRestException -> fromAuthCode(error, call)
        error is RestException && error.statusCode in 500..599 -> ApiException.Server(error.statusCode)
        error is RestException -> ApiException.Validation(error.statusCode)
        else -> ApiException.Unexpected(null, error)
    }

    private fun fromAuthCode(error: AuthRestException, call: SupabaseCall): ApiException =
        when (error.errorCode) {
            // The number already belongs to someone else. Supabase often
            // refuses before the API ever sees it, so both sides map here.
            AuthErrorCode.PhoneExists -> ApiException.PhoneAlreadyLinked()

            AuthErrorCode.OtpExpired -> ApiException.InvalidCode()

            // The same code for a wrong password and a wrong one-time code.
            AuthErrorCode.InvalidCredentials ->
                if (call == SupabaseCall.EMAIL) ApiException.WrongCredentials() else ApiException.InvalidCode()

            AuthErrorCode.OverSmsSendRateLimit,
            AuthErrorCode.OverEmailSendRateLimit,
            -> ApiException.TooManyAttempts()

            AuthErrorCode.EmailExists,
            AuthErrorCode.UserAlreadyExists,
            -> ApiException.EmailAlreadyRegistered()

            AuthErrorCode.EmailNotConfirmed -> ApiException.EmailNotConfirmed()

            AuthErrorCode.WeakPassword,
            AuthErrorCode.SamePassword,
            -> ApiException.WeakPassword()

            AuthErrorCode.EmailAddressInvalid -> ApiException.InvalidEmail()

            AuthErrorCode.IdentityAlreadyExists -> ApiException.IdentityInUse()

            // EmailAddressNotAuthorized is Supabase's built-in mailer refusing
            // anyone outside the project team: a setup gap, not a bad address.
            AuthErrorCode.PhoneProviderDisabled,
            AuthErrorCode.EmailProviderDisabled,
            AuthErrorCode.EmailAddressNotAuthorized,
            AuthErrorCode.ManualLinkingDisabled,
            AuthErrorCode.OtpDisabled,
            AuthErrorCode.SignupDisabled,
            -> ApiException.SignInMethodUnavailable()

            // Could not text the code — nothing the user did wrong, and
            // retrying is the only useful advice.
            AuthErrorCode.SmsSendFailed -> ApiException.Server(error.statusCode)

            AuthErrorCode.ValidationFailed -> when (call) {
                SupabaseCall.PHONE -> ApiException.InvalidPhone()
                SupabaseCall.CODE -> ApiException.InvalidCode()
                SupabaseCall.EMAIL -> ApiException.InvalidEmail()
                SupabaseCall.PASSWORD -> ApiException.WeakPassword()
                else -> ApiException.Validation(error.statusCode)
            }

            else ->
                if (error.statusCode in 500..599) ApiException.Server(error.statusCode)
                else ApiException.Validation(error.statusCode)
        }
}
