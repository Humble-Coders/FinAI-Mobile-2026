package com.humblesolutions.finai.ui.onboarding

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ConfigurationProblem
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.usecase.Destination
import com.humblesolutions.finai.usecase.OnboardingRouter
import com.humblesolutions.finai.util.DialCode
import com.humblesolutions.finai.util.DialCodes

/**
 * Everything the onboarding flow shows.
 *
 * The derived rules are computed properties here rather than logic in a
 * composable, so they can be asserted with a plain constructor and no UI
 * (kmp-arch-v2 → ViewModels).
 */
data class OnboardingUiState(
    /** Set when this build has no backend configured; the screen says so rather than failing oddly. */
    val configurationProblem: ConfigurationProblem? = null,
    val session: SessionState = SessionState.LOADING,
    val me: Me? = null,
    val meFailure: ApiException? = null,

    /** True once the splash has been up long enough to deserve a progress indicator. */
    val startIsSlow: Boolean = false,

    val dialCode: DialCode = DialCodes.fallback,
    val phoneDigits: String = "",

    /** A code has been sent for [e164]; the code screen replaces phone entry. */
    val codeSent: Boolean = false,
    val code: String = "",
    val resendSeconds: Int = 0,

    val terms: Terms? = null,
    val busy: Boolean = false,
    val errorKey: String? = null,

    /**
     * A provider sign-in that failed, kept apart from [errorKey].
     *
     * They are shown in different places and mean different things: one is
     * about what the user typed, the other about a button they pressed. Sharing
     * a field turned the phone box red because Google was misconfigured.
     */
    val providerErrorKey: String? = null,
) {

    /**
     * Where the app should be — from the one shared rule, never decided here.
     * The code screen is the single exception the router does not model: it is
     * a sub-state of the phone step, entered once a code has been sent.
     */
    val destination: Destination get() = OnboardingRouter.destinationFor(session, me, meFailure)

    /** The number as the API wants it, digits only behind a `+`. */
    val e164: String get() = "+" + dialCode.code + digits

    private val digits: String get() = phoneDigits.filter { it.isDigit() }

    val canSendCode: Boolean get() = !busy && digits.length >= MIN_PHONE_DIGITS
    val canVerify: Boolean get() = !busy && code.length == CODE_LENGTH
    val canResend: Boolean get() = !busy && resendSeconds == 0

    /** `0:47`, for the resend countdown. */
    val resendCountdown: String
        get() = (resendSeconds / 60).toString() + ":" + (resendSeconds % 60).toString().padStart(2, '0')

    companion object {
        const val CODE_LENGTH = 6

        /**
         * Enough to be a real number somewhere. Deliberately not a per-country
         * length check: that is libphonenumber's job on the server, and getting
         * it wrong here would block valid numbers before they were ever sent.
         */
        const val MIN_PHONE_DIGITS = 4
    }
}
