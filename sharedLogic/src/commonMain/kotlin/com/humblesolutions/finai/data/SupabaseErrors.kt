package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException

/** Maps a failure from the Supabase SDK onto [ApiException], so screens see one error type whatever the source. */
internal object SupabaseErrors {

    /**
     * @param whileRefreshing a rejected refresh means the session is gone for
     *   good — expired or revoked refresh token — so the only way on is to sign
     *   in again: [ApiException.Unauthorized].
     */
    fun map(error: Exception, whileRefreshing: Boolean): ApiException = when {
        error is ApiException -> error
        error is HttpRequestException -> ApiException.Network(error)
        // Supabase refuses a number already held by another sign-in before the
        // API ever sees it, so the same situation can arrive from either side
        // and must read the same to a screen. Matched on the SDK's typed error
        // code, never on the message text — messages are not a contract.
        error is AuthRestException && error.errorCode == AuthErrorCode.PhoneExists ->
            ApiException.PhoneAlreadyLinked()
        whileRefreshing -> ApiException.Unauthorized("session refresh rejected")
        error is RestException && error.statusCode in 500..599 -> ApiException.Server(error.statusCode)
        error is RestException -> ApiException.Validation(error.statusCode)
        else -> ApiException.Unexpected(null, error)
    }
}
