package com.humblesolutions.finai.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.SupabaseAuthRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.OnboardingStep
import com.humblesolutions.finai.model.ResetStage
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.model.WelcomeMode
import com.humblesolutions.finai.repository.AuthRepository
import com.humblesolutions.finai.util.Credentials
import com.humblesolutions.finai.util.DialCode
import com.humblesolutions.finai.util.DialCodes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives sign-in, signup and the onboarding steps after it.
 *
 * The repository is built fresh in [bind] and closed in [onCleared] — never a
 * singleton (kmp-arch-v2). Where to go next is never decided here: the state's
 * `destination` reads the one shared rule.
 */
class OnboardingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var auth: AuthRepository? = null
    private var bound = false
    private var resendTicker: Job? = null

    /**
     * @param deviceRegion the platform's region, for pre-selecting a dialling
     *   code only — never sent as the user's region (PRD §4.6).
     */
    fun bind(logging: Boolean, deviceRegion: String?, timeZoneId: String?) {
        if (bound) return
        bound = true

        _uiState.update { it.copy(dialCode = DialCodes.defaultFor(deviceRegion, timeZoneId)) }

        Supabase.configurationProblem?.let { problem ->
            _uiState.update { it.copy(configurationProblem = problem) }
            return
        }
        val client = Supabase.clientOrNull() ?: return
        val repository = SupabaseAuthRepository(client, ApiConfig.BASE_URL, SupabaseTokenSource(client), logging)
        auth = repository

        viewModelScope.launch {
            repository.sessionState.collect { session ->
                val was = _uiState.value.session
                _uiState.update { it.copy(session = session) }
                when {
                    // Signed in, and we have nothing (or stale) to route on.
                    session == SessionState.SIGNED_IN -> loadMe()
                    // Signing out clears everything the previous account loaded.
                    session == SessionState.SIGNED_OUT && was != SessionState.SIGNED_OUT ->
                        _uiState.update {
                            it.copy(
                                me = null, meFailure = null, terms = null, code = "", codeSent = false,
                                phoneDigits = "", reset = null,
                                emailCodeFor = null, password = "",
                            )
                        }
                }
            }
        }
    }

    /**
     * A frozen logo is indistinguishable from a hang, and Render's free tier can
     * take most of a minute to wake. After a few seconds the splash says so.
     *
     * Driven by the splash screen itself, for as long as it is on screen, rather
     * than once at startup. The splash a user actually waits on is usually the
     * SECOND one — after verifying a code, while the first `/me` loads — and a
     * one-shot timer armed at launch has always expired by then.
     */
    suspend fun watchForSlowStart() {
        _uiState.update { it.copy(startIsSlow = false) }
        delay(SLOW_START_MS)
        _uiState.update { it.copy(startIsSlow = true) }
    }

    /** The launch splash finished its intro; routing may take over. */
    fun onIntroFinished() = _uiState.update { it.copy(introFinished = true) }

    fun loadMe() {
        val auth = auth ?: return
        viewModelScope.launch {
            try {
                val me = auth.me()
                _uiState.update { it.copy(me = me, meFailure = null) }
                if (me.onboardingRequired.firstOrNull() == OnboardingStep.CONSENT) loadTerms()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(meFailure = e) }
            }
        }
    }

    // ── Welcome: email and password ─────────────────────────────────────

    fun onWelcomeModeChange(mode: WelcomeMode) =
        _uiState.update {
            it.copy(welcomeMode = mode, errorKey = null, providerErrorKey = null, emailTaken = false)
        }

    fun onEmailChange(value: String) =
        _uiState.update { it.copy(email = value, errorKey = null, emailTaken = false) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorKey = null) }

    /** Creates the account or signs in, depending on the mode. */
    fun submitCredentials() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canSubmitCredentials) return
        val email = Credentials.normalizeEmail(state.email)
        val password = state.password

        if (state.creatingAccount) {
            var taken = false
            perform({
                try {
                    auth.signUpWithEmail(email, password)
                } catch (e: ApiException.EmailAlreadyRegistered) {
                    // Not an error to fix in the form: the card offers the way on.
                    taken = true
                }
            }) {
                if (taken) {
                    // The password stays: Sign in is one tap away and can use it.
                    it.copy(emailTaken = true)
                } else {
                    startResendCountdown()
                    it.copy(emailCodeFor = email, code = "", password = "")
                }
            }
            return
        }

        var needsCode = false
        perform({
            try {
                auth.signInWithEmail(email, password)
            } catch (e: ApiException.EmailNotConfirmed) {
                // An account that never entered its code. Send a fresh one and
                // go to the code screen, rather than naming a problem they
                // cannot act on. A rate limit means one was sent moments ago.
                needsCode = true
                try {
                    auth.resendSignupCode(email)
                } catch (limited: ApiException.TooManyAttempts) {
                    // The earlier code is still on its way.
                }
            }
        }) {
            if (needsCode) {
                startResendCountdown()
                it.copy(emailCodeFor = email, code = "", password = "")
            } else {
                it.copy(password = "")
            }
        }
    }

    /** Verifies the emailed signup code, which signs the user in. */
    fun verifyEmailCode() {
        val auth = auth ?: return
        val state = _uiState.value
        val email = state.emailCodeFor ?: return
        if (!state.canVerify) return
        val code = state.code
        perform({ auth.verifySignupCode(email, code) }) { it.copy(emailCodeFor = null, code = "") }
    }

    fun resendEmailCode() {
        val auth = auth ?: return
        val email = _uiState.value.emailCodeFor ?: return
        perform({ auth.resendSignupCode(email) }) {
            startResendCountdown()
            it
        }
    }

    /** Back from the email code screen, to fix a mistyped address. */
    fun editEmail() {
        resendTicker?.cancel()
        _uiState.update { it.copy(emailCodeFor = null, code = "", resendSeconds = 0, errorKey = null) }
    }

    // ── Forgot password ─────────────────────────────────────────────────

    fun startReset() = _uiState.update {
        it.copy(reset = ResetStage.REQUEST, password = "", code = "", errorKey = null, providerErrorKey = null)
    }

    fun requestResetCode() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canRequestReset) return
        val email = Credentials.normalizeEmail(state.email)
        perform({ auth.requestPasswordReset(email) }) {
            startResendCountdown()
            it.copy(reset = ResetStage.CODE, email = email, code = "")
        }
    }

    fun verifyResetCode() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canVerify) return
        val email = state.email
        val code = state.code
        perform({ auth.verifyPasswordResetCode(email, code) }) {
            it.copy(reset = ResetStage.NEW_PASSWORD, code = "", password = "")
        }
    }

    fun saveNewPassword() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canSaveNewPassword) return
        val password = state.password
        perform({ auth.setNewPassword(password) }) { it.copy(reset = null, password = "") }
    }

    /**
     * Leaves the reset. Once the code has verified the user is signed in with
     * a recovery session and no new password, so leaving then signs out: the
     * app is never reached by a reset that did not finish.
     */
    fun cancelReset() {
        val signedInForReset = _uiState.value.reset == ResetStage.NEW_PASSWORD
        resendTicker?.cancel()
        _uiState.update { it.copy(reset = null, code = "", password = "", resendSeconds = 0, errorKey = null) }
        if (signedInForReset) signOut()
    }

    // ── The phone step ──────────────────────────────────────────────────

    fun onDialCodeSelected(dialCode: DialCode) = _uiState.update { it.copy(dialCode = dialCode) }

    fun onPhoneChange(value: String) =
        _uiState.update { it.copy(phoneDigits = value, errorKey = null) }

    fun onCodeChange(value: String) = _uiState.update {
        it.copy(code = value.filter(Char::isDigit).take(OnboardingUiState.CODE_LENGTH), errorKey = null)
    }

    /**
     * Attaches the number to the signed-in account, which texts the code.
     *
     * A number another account already has fails as
     * [ApiException.PhoneAlreadyLinked], shown under the field like any other
     * refusal: the user types a different number. Nothing offers to sign in to
     * or link with that account (manager decision, 2026-09-15).
     */
    fun sendCode() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canSendCode) return
        val number = state.e164
        perform({ auth.requestPhoneLink(number) }) {
            startResendCountdown()
            it.copy(codeSent = true, code = "")
        }
    }

    fun verifyCode() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canVerify) return
        val number = state.e164
        val code = state.code
        perform({
            auth.verifyPhoneLink(number, code)
            // Linking keeps the same session, so no new SIGNED_IN arrives to
            // trigger a reload — ask for the new state directly.
            loadMe()
        }) { it.copy(codeSent = false, code = "") }
    }

    /** Back from the code screen to fix a mistyped number. */
    fun editNumber() {
        resendTicker?.cancel()
        _uiState.update { it.copy(codeSent = false, code = "", resendSeconds = 0, errorKey = null) }
    }

    // ── Google and Apple ────────────────────────────────────────────────

    /**
     * Signs in with an ID token the platform obtained natively.
     *
     * Deliberately not [perform]: that reports into `errorKey`, which the form
     * fields render. A provider Supabase refuses — one not enabled in the
     * dashboard, say — would then read as though what was typed were wrong.
     * It belongs under the buttons it came from.
     */
    fun signInWithProvider(provider: SocialProvider, idToken: String, nonce: String?) {
        val auth = auth ?: return
        _uiState.update { it.copy(busy = true, providerErrorKey = null) }
        viewModelScope.launch {
            try {
                auth.signInWithIdToken(provider, idToken, nonce)
                _uiState.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(busy = false, providerErrorKey = e.messageKey) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, providerErrorKey = Strings.error_unexpected) }
            }
        }
    }

    /** The provider sheet was dismissed. Silently back — a cancel is not an error. */
    fun onProviderCancelled() =
        _uiState.update { it.copy(busy = false, providerErrorKey = null) }

    fun onProviderFailed(messageKey: String) =
        _uiState.update { it.copy(busy = false, providerErrorKey = messageKey) }

    fun onProviderStarted() =
        _uiState.update { it.copy(busy = true, errorKey = null, providerErrorKey = null) }

    fun signOut() {
        val auth = auth ?: return
        perform({ auth.signOut() }) { it }
    }

    fun retry() {
        _uiState.update { it.copy(meFailure = null, errorKey = null) }
        loadMe()
    }

    fun loadTerms() {
        val auth = auth ?: return
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(terms = auth.terms()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorKey = e.messageKey) }
            }
        }
    }

    fun acceptTerms() {
        val auth = auth ?: return
        val version = _uiState.value.terms?.version ?: return
        perform({
            val me = auth.acceptTerms(version)
            _uiState.update { it.copy(me = me) }
        }) { it }
    }

    fun setRegion(countryCode: String) {
        val auth = auth ?: return
        perform({
            val me = auth.setRegion(countryCode)
            _uiState.update { it.copy(me = me) }
            if (me.onboardingRequired.firstOrNull() == OnboardingStep.CONSENT) loadTerms()
        }) { it }
    }

    private fun startResendCountdown() {
        resendTicker?.cancel()
        resendTicker = viewModelScope.launch {
            for (second in RESEND_SECONDS downTo 0) {
                _uiState.update { it.copy(resendSeconds = second) }
                if (second > 0) delay(1_000)
            }
        }
    }

    private fun perform(work: suspend () -> Unit, onSuccess: (OnboardingUiState) -> OnboardingUiState) {
        _uiState.update { it.copy(busy = true, errorKey = null) }
        viewModelScope.launch {
            try {
                work()
                _uiState.update { onSuccess(it).copy(busy = false) }
            } catch (e: CancellationException) {
                throw e // a superseded call is not a failure
            } catch (e: ApiException) {
                // The terms moved on: reload them so the user reads what they
                // are actually agreeing to, rather than retrying the old text.
                if (e is ApiException.TermsChanged) loadTerms()
                _uiState.update { it.copy(busy = false, errorKey = e.messageKey) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, errorKey = Strings.error_unexpected) }
            }
        }
    }

    override fun onCleared() {
        resendTicker?.cancel()
        auth?.close()
    }

    private companion object {
        const val RESEND_SECONDS = 60
        const val SLOW_START_MS = 4_000L
    }
}
