package com.humblesolutions.finai.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorLegalRepository
import com.humblesolutions.finai.data.SupabaseAuthRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.AuthRepository
import com.humblesolutions.finai.repository.LegalRepository
import com.humblesolutions.finai.usecase.Profile
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.GoalsChanged
import com.humblesolutions.finai.util.LedgerChanged
import com.humblesolutions.finai.util.PlanChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Profile (PM decision, 2026-11-06): who the person is, their region, every
 * consent and disclaimer, and deleting the account.
 *
 * The one place AI processing is turned on or off — every other screen only
 * says it is off and links here. Signing out is the app's own sign-out,
 * passed in by the router; deleting clears the session, which routes the app
 * to Welcome the same way.
 */
class ProfileViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var repositories: ProfileRepositories? = null
    private var boundTo: String? = null

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            ProfileRepositories(
                auth = SupabaseAuthRepository(client, ApiConfig.BASE_URL, tokens, logging),
                legal = KtorLegalRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> ProfileRepositories?) {
        if (userId.isBlank() || (userId == boundTo && repositories != null)) return
        repositories?.close()
        boundTo = userId
        _uiState.value = ProfileUiState()
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /**
     * `/me`, then where AI consent stands and whether the region has a
     * disclaimer. Only `/me` failing fails the screen: the other two leave
     * their rows without a status, or out.
     */
    fun load() {
        val repos = repositories ?: return
        _uiState.update { it.copy(loading = true, loadErrorKey = null) }
        viewModelScope.launch {
            try {
                val me = repos.auth.me()
                _uiState.update { it.copy(me = me, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, loadErrorKey = e.messageKey) }
                return@launch
            }
            val status = quietly { repos.legal.aiStatus() }
            val disclaimer = quietly { repos.legal.disclaimer() }
            _uiState.update { it.copy(aiStatus = status ?: it.aiStatus, disclaimer = disclaimer) }
        }
    }

    fun clearNotice() = _uiState.update { it.copy(noticeKey = null) }

    /** Setup's answers were saved from here; said once under the header. */
    fun setupUpdated() = _uiState.update { it.copy(noticeKey = Strings.profile_setup_updated) }

    // ── Name ────────────────────────────────────────────────────────────

    fun openName() = _uiState.update {
        it.copy(editingName = true, nameDraft = it.me?.user?.displayName.orEmpty(), nameErrorKey = null)
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(nameDraft = value, nameErrorKey = null) }

    fun closeName() = _uiState.update { it.copy(editingName = false, nameErrorKey = null) }

    /** Saves the name; [onSaved] once the server has it, so Home's monogram can follow. */
    fun saveName(onSaved: () -> Unit = {}) {
        val repos = repositories ?: return
        val state = _uiState.value
        if (state.savingName) return
        Profile.nameBlock(state.nameDraft)?.let { block ->
            _uiState.update { it.copy(nameErrorKey = block) }
            return
        }
        val name = state.nameDraft.trim()
        _uiState.update { it.copy(savingName = true, nameErrorKey = null) }
        viewModelScope.launch {
            try {
                val me = repos.auth.updateName(name)
                _uiState.update { it.copy(me = me, savingName = false, editingName = false, noticeKey = Strings.profile_saved) }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(savingName = false, nameErrorKey = e.messageKey) }
            }
        }
    }

    // ── Region ──────────────────────────────────────────────────────────

    /**
     * The household's region, and with it the currency. Every screen showing
     * money re-reads once the server has it; [onChanged] lets the router
     * re-read capabilities and the app's own `/me`.
     */
    fun changeRegion(countryCode: String, onChanged: () -> Unit = {}) {
        val repos = repositories ?: return
        if (_uiState.value.regionBusy) return
        _uiState.update { it.copy(regionBusy = true, regionErrorKey = null) }
        viewModelScope.launch {
            try {
                val me = repos.auth.setRegion(countryCode)
                // A new region can carry its own disclaimer.
                val disclaimer = quietly { repos.legal.disclaimer() }
                _uiState.update { it.copy(me = me, regionBusy = false, disclaimer = disclaimer) }
                announceEverything()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(regionBusy = false, regionErrorKey = e.messageKey) }
            }
        }
    }

    fun clearRegionError() = _uiState.update { it.copy(regionErrorKey = null) }

    // ── Terms and the disclaimer ────────────────────────────────────────

    /** Opens the reading sheet; the terms are read the first time. */
    fun read(text: LegalText) {
        val repos = repositories ?: return
        _uiState.update { it.copy(reading = text, readingErrorKey = null) }
        if (text != LegalText.TERMS || _uiState.value.terms != null) return
        viewModelScope.launch {
            try {
                val terms = repos.legal.terms()
                _uiState.update { it.copy(terms = terms) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(readingErrorKey = e.messageKey) }
            }
        }
    }

    fun closeReading() = _uiState.update { it.copy(reading = null, readingErrorKey = null) }

    // ── AI processing ───────────────────────────────────────────────────

    /** "Turn on": the policy in force, to read and agree to. */
    fun openAiOn() {
        _uiState.update { it.copy(turningAiOn = true, aiTicked = false, aiErrorKey = null) }
        loadPolicy()
    }

    private fun loadPolicy(errorKey: String? = null) {
        val repos = repositories ?: return
        _uiState.update { it.copy(aiPolicy = null, aiTicked = false, aiBusy = true, aiErrorKey = errorKey) }
        viewModelScope.launch {
            try {
                val policy = repos.legal.aiPolicy()
                _uiState.update { it.copy(aiPolicy = policy, aiBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(aiBusy = false, aiErrorKey = e.messageKey) }
            }
        }
    }

    fun onAiTicked(ticked: Boolean) = _uiState.update { it.copy(aiTicked = ticked) }

    fun closeAiOn() = _uiState.update { it.copy(turningAiOn = false, aiTicked = false, aiErrorKey = null) }

    /** Records consent to the version shown. */
    fun agreeToAi() {
        val repos = repositories ?: return
        val state = _uiState.value
        val policy = state.aiPolicy ?: return
        if (!state.canAgreeToAi) return
        _uiState.update { it.copy(aiBusy = true, aiErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.legal.consentToAi(policy.version)
                val status = quietly { repos.legal.aiStatus() } ?: AiConsentStatus(consented = true, version = policy.version)
                _uiState.update { it.copy(aiStatus = status, aiBusy = false, turningAiOn = false, aiTicked = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.AiPolicyChanged) {
                // The text changed under them: show the new one, unticked.
                loadPolicy(e.messageKey)
            } catch (e: ApiException) {
                _uiState.update { it.copy(aiBusy = false, aiErrorKey = e.messageKey) }
            }
        }
    }

    fun askAiOff() = _uiState.update { it.copy(confirmingAiOff = true, aiErrorKey = null) }

    fun keepAiOn() = _uiState.update { it.copy(confirmingAiOff = false) }

    fun turnAiOff() {
        val repos = repositories ?: return
        if (_uiState.value.aiBusy) return
        _uiState.update { it.copy(aiBusy = true, aiErrorKey = null) }
        viewModelScope.launch {
            try {
                val status = repos.legal.withdrawAi()
                _uiState.update { it.copy(aiStatus = status, aiBusy = false, confirmingAiOff = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(aiBusy = false, confirmingAiOff = false, aiErrorKey = e.messageKey) }
            }
        }
    }

    // ── Deleting the account ────────────────────────────────────────────

    fun askDelete() = _uiState.update { it.copy(confirmingDelete = true, deleteTyped = "", deleteErrorKey = null) }

    fun onDeleteTyped(value: String) = _uiState.update { it.copy(deleteTyped = value, deleteErrorKey = null) }

    fun cancelDelete() = _uiState.update {
        if (it.deleting) it else it.copy(confirmingDelete = false, deleteTyped = "", deleteErrorKey = null)
    }

    /**
     * Deletes the account. On success the session is gone and the app goes to
     * Welcome on its own; on a refusal nothing was deleted, the person stays
     * signed in, and the dialog says why.
     */
    fun deleteAccount() {
        val repos = repositories ?: return
        if (!_uiState.value.canDelete) return
        _uiState.update { it.copy(deleting = true, deleteErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.auth.deleteAccount()
                _uiState.update { it.copy(deleting = false, confirmingDelete = false, deleteTyped = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(deleting = false, deleteErrorKey = e.messageKey) }
            } catch (e: Exception) {
                _uiState.update { it.copy(deleting = false, deleteErrorKey = Strings.error_unexpected) }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun <T> quietly(read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }

    private fun announceEverything() {
        LedgerChanged.announce()
        BudgetChanged.announce()
        GoalsChanged.announce()
        PlanChanged.announce()
    }
}

/** What Profile talks to. Closed together when the model is cleared. */
internal class ProfileRepositories(
    val auth: AuthRepository,
    val legal: LegalRepository,
) {
    fun close() {
        auth.close()
        legal.close()
    }
}
