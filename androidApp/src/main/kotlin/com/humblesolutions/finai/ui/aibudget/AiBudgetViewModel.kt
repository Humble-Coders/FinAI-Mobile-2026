package com.humblesolutions.finai.ui.aibudget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorAiConsentRepository
import com.humblesolutions.finai.data.KtorBudgetRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.usecase.AiBudgetBlock
import com.humblesolutions.finai.usecase.AiBudgetDraft
import com.humblesolutions.finai.usecase.AiBudgetEdit
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The suggested budget, as the screen reads it. */
data class AiBudgetUiState(
    val loading: Boolean = true,
    val proposal: AiProposal? = null,
    val draft: AiBudgetDraft = AiBudgetDraft(),
    val errorKey: String? = null,
    /** The AI may not be asked yet: the person is shown the policy first. */
    val policy: AiPolicy? = null,
    val consentTicked: Boolean = false,
    val consentBusy: Boolean = false,
    val suggesting: Boolean = false,
    val starting: Boolean = false,
    /** Budgets are off for this household, or there is no income to divide: nothing to suggest. */
    val unavailable: Boolean = false,
    val locale: String = "en",
) {
    val currency: String get() = proposal?.currency.orEmpty()

    val needsConsent: Boolean get() = policy != null

    val block: AiBudgetBlock? get() = proposal?.let { AiBudgetEdit.blockingReason(draft, it) }

    val canStart: Boolean get() = proposal != null && block == null && !starting && !suggesting

    val canSuggestAgain: Boolean get() = (proposal?.attemptsLeft ?: 0) > 0 && !suggesting && !starting

    val shareLabel: String? get() = proposal?.let { AiBudgetEdit.shareLabel(it, locale) }

    val leftLabel: String? get() = proposal?.let { AiBudgetEdit.leftLabel(draft, it, locale) }

    val attemptsLabel: String? get() = proposal?.let { AiBudgetEdit.attemptsLabel(it, locale) }

    val symbol: String get() = Money.symbol(currency)

    fun lineBlock(index: Int): AiBudgetBlock? = draft.lines.getOrNull(index)?.let { AiBudgetEdit.lineBlock(it, currency) }
}

/**
 * The AI's suggested first budget (product decision, 2026-10-25): shown at the
 * end of setup, and from the Budget tab for a household already set up.
 *
 * Opening it shows the suggestion already made this month when there is one
 * — asking costs money, and the server allows three — and asks for one only
 * when there is none. Start writes the lines the person kept as their own.
 */
class AiBudgetViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AiBudgetUiState())
    val uiState: StateFlow<AiBudgetUiState> = _uiState.asStateFlow()

    private var repositories: AiBudgetRepositories? = null
    private var boundTo: String? = null

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            AiBudgetRepositories(
                budgets = KtorBudgetRepository(ApiConfig.BASE_URL, tokens, logging),
                consent = KtorAiConsentRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> AiBudgetRepositories?) {
        if (userId.isBlank() || (userId == boundTo && repositories != null)) return
        repositories?.close()
        boundTo = userId
        _uiState.value = AiBudgetUiState()
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /** The suggestion made this month if there is one; otherwise ask for one. */
    fun load() {
        val repos = repositories ?: return
        _uiState.update { it.copy(loading = true, errorKey = null) }
        viewModelScope.launch {
            try {
                show(repos.budgets.setupSuggestion())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.NotFound) {
                suggest()
            } catch (e: ApiException) {
                failed(e)
            }
        }
    }

    /** A new suggestion — "Suggest again", or the first one. */
    fun suggest() {
        val repos = repositories ?: return
        _uiState.update { it.copy(suggesting = true, errorKey = null) }
        viewModelScope.launch {
            try {
                show(repos.budgets.suggestSetup())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.ConsentRequired) {
                askForConsent()
            } catch (e: ApiException.AiBudgetLimitReached) {
                // The last one stays; show it rather than nothing.
                runCatching { repos.budgets.setupSuggestion() }.getOrNull()?.let(::show) ?: failed(e)
            } catch (e: ApiException) {
                failed(e)
            }
        }
    }

    private fun show(proposal: AiProposal) = _uiState.update {
        it.copy(
            loading = false,
            suggesting = false,
            proposal = proposal,
            draft = AiBudgetEdit.draftOf(proposal),
            errorKey = null,
            policy = null,
        )
    }

    private fun failed(e: ApiException) = _uiState.update {
        val nothingToSuggest = e is ApiException.FeatureUnavailable || e is ApiException.SetupIncomeMissing
        it.copy(loading = false, suggesting = false, starting = false, errorKey = e.messageKey, unavailable = nothingToSuggest)
    }

    // ── Consent ─────────────────────────────────────────────────────────

    private suspend fun askForConsent() {
        val consent = repositories?.consent ?: return
        try {
            val policy = consent.policy()
            _uiState.update { it.copy(loading = false, suggesting = false, policy = policy, consentTicked = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            failed(e)
        }
    }

    fun onConsentTicked(ticked: Boolean) = _uiState.update { it.copy(consentTicked = ticked) }

    /** Records consent to the version shown, then asks for the suggestion. */
    fun agree() {
        val consent = repositories?.consent ?: return
        val policy = _uiState.value.policy ?: return
        if (!_uiState.value.consentTicked) return
        _uiState.update { it.copy(consentBusy = true, errorKey = null) }
        viewModelScope.launch {
            try {
                consent.consent(policy.version)
                _uiState.update { it.copy(consentBusy = false, policy = null, consentTicked = false) }
                suggest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.AiPolicyChanged) {
                _uiState.update { it.copy(consentBusy = false) }
                askForConsent()
            } catch (e: ApiException) {
                _uiState.update { it.copy(consentBusy = false, errorKey = e.messageKey) }
            }
        }
    }

    // ── Editing ─────────────────────────────────────────────────────────

    fun onAmount(index: Int, amount: String) = _uiState.update {
        it.copy(draft = AiBudgetEdit.withAmount(it.draft, index, amount), errorKey = null)
    }

    fun onSavings(amount: String) = _uiState.update { it.copy(draft = it.draft.copy(savings = amount), errorKey = null) }

    /** Start the budget from what is on screen; [onDone] once it is written. */
    fun start(onDone: () -> Unit) {
        val repos = repositories ?: return
        val state = _uiState.value
        if (!state.canStart) return
        _uiState.update { it.copy(starting = true, errorKey = null) }
        viewModelScope.launch {
            try {
                repos.budgets.acceptSetup(AiBudgetEdit.accept(state.draft, state.currency))
                _uiState.update { it.copy(starting = false) }
                BudgetChanged.announce()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(starting = false, errorKey = e.messageKey) }
            } catch (e: Exception) {
                _uiState.update { it.copy(starting = false, errorKey = Strings.error_unexpected) }
            }
        }
    }
}

/** What this screen talks to. Closed together when the model is cleared. */
internal class AiBudgetRepositories(
    val budgets: BudgetRepository,
    val consent: AiConsentRepository,
) {
    fun close() {
        budgets.close()
        consent.close()
    }
}
