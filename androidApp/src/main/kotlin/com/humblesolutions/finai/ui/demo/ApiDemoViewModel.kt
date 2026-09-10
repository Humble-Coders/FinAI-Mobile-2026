package com.humblesolutions.finai.ui.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.SupabaseAuthRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Throwaway demo (ticket #6): proves sign-in → bearer token → `/capabilities`
 * end to end on Android. Replaced by real screens in M2.
 *
 * Repositories are built fresh in [bind] and closed in [onCleared] — never
 * singletons (kmp-arch-v2).
 */
class ApiDemoViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ApiDemoUiState())
    val uiState: StateFlow<ApiDemoUiState> = _uiState.asStateFlow()

    private var auth: SupabaseAuthRepository? = null
    private var capabilitiesRepository: KtorCapabilitiesRepository? = null
    private var bound = false

    /** @param logging true only in debug builds — see FinAiHttpClient. */
    fun bind(logging: Boolean) {
        if (bound) return
        bound = true
        Supabase.configurationProblem?.let { problem ->
            _uiState.update { it.copy(configurationProblem = problem) }
            return
        }
        val client = Supabase.clientOrNull() ?: return
        val tokens = SupabaseTokenSource(client)
        val auth = SupabaseAuthRepository(client, ApiConfig.BASE_URL, tokens, logging)
        this.auth = auth
        capabilitiesRepository = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging)
        viewModelScope.launch {
            auth.sessionState.collect { state -> _uiState.update { it.copy(session = state) } }
        }
    }

    fun onPhoneChange(value: String) = _uiState.update { it.copy(phone = value) }

    fun onCodeChange(value: String) = _uiState.update { it.copy(code = value) }

    fun sendCode() {
        val auth = auth ?: return
        val phone = _uiState.value.phone
        perform({ auth.requestPhoneCode(phone) }) { it.copy(codeSent = true) }
    }

    fun verifyCode() {
        val auth = auth ?: return
        val state = _uiState.value
        perform({ auth.verifyPhoneCode(state.phone, state.code) }) { it }
    }

    fun loadCapabilities() {
        val repository = capabilitiesRepository ?: return
        perform({
            val capabilities = repository.fetch()
            _uiState.update { it.copy(capabilities = capabilities) }
        }) { it }
    }

    /** Marks the access token expired, then loads — the request must refresh first and still succeed. */
    fun expireTokenThenLoad() {
        val auth = auth ?: return
        val repository = capabilitiesRepository ?: return
        perform({
            auth.expireAccessTokenForTesting()
            val afterExpire = auth.tokenSecondsLeftForTesting()
            val capabilities = repository.fetch()
            val afterLoad = auth.tokenSecondsLeftForTesting()
            _uiState.update {
                it.copy(capabilities = capabilities, tokenLifeAfterExpire = afterExpire, tokenLifeAfterLoad = afterLoad)
            }
        }) { it }
    }

    fun signOut() {
        val auth = auth ?: return
        perform({ auth.signOut() }) { it.copy(capabilities = null, codeSent = false, code = "") }
    }

    private fun perform(work: suspend () -> Unit, onSuccess: (ApiDemoUiState) -> ApiDemoUiState) {
        _uiState.update { it.copy(busy = true, errorKey = null) }
        viewModelScope.launch {
            try {
                work()
                _uiState.update { onSuccess(it).copy(busy = false) }
            } catch (e: CancellationException) {
                throw e // a superseded call is not an error
            } catch (e: ApiException) {
                _uiState.update { it.copy(busy = false, errorKey = e.messageKey) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, errorKey = Strings.error_unexpected) }
            }
        }
    }

    override fun onCleared() {
        auth?.close()
        capabilitiesRepository?.close()
    }
}
