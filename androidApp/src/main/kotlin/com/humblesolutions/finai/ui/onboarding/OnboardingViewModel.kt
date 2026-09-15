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
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.repository.AuthRepository
import com.humblesolutions.finai.usecase.Destination
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
 * Drives signup and the onboarding steps after it.
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
                            it.copy(me = null, meFailure = null, terms = null, code = "", codeSent = false)
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

    fun onDialCodeSelected(dialCode: DialCode) = _uiState.update { it.copy(dialCode = dialCode) }

    fun onPhoneChange(value: String) =
        _uiState.update { it.copy(phoneDigits = value, errorKey = null, providerErrorKey = null) }

    fun onCodeChange(value: String) = _uiState.update {
        it.copy(code = value.filter(Char::isDigit).take(OnboardingUiState.CODE_LENGTH), errorKey = null)
    }

    /** Sends the code. A signed-in user is ATTACHING a number, which is a different call. */
    fun sendCode() {
        val auth = auth ?: return
        val state = _uiState.value
        if (!state.canSendCode) return
        val number = state.e164
        val linking = state.session == SessionState.SIGNED_IN
        perform({
            if (linking) auth.requestPhoneLink(number) else auth.requestPhoneCode(number)
        }) {
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
        val linking = state.session == SessionState.SIGNED_IN
        perform({
            if (linking) auth.verifyPhoneLink(number, code) else auth.verifyPhoneCode(number, code)
            // Linking keeps the same session, so no new SIGNED_IN arrives to
            // trigger a reload — ask for the new state directly.
            if (linking) loadMe()
        }) { it.copy(codeSent = false, code = "") }
    }

    /** Back from the code screen to fix a mistyped number. */
    fun editNumber() {
        resendTicker?.cancel()
        _uiState.update { it.copy(codeSent = false, code = "", resendSeconds = 0, errorKey = null) }
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

    /**
     * Signs in with an ID token the platform obtained natively.
     *
     * Only half a signup: every route ends at a verified phone, so the router
     * sends the user to the phone step next (PRD §4.6).
     */
    /**
     * Deliberately not [perform]: that reports into `errorKey`, which the phone
     * field renders. A provider Supabase refuses — one not enabled in the
     * dashboard, say — would then read as though the typed number were wrong.
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
                _uiState.update {
                    it.copy(busy = false, providerErrorKey = Strings.error_unexpected)
                }
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
