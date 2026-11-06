package com.humblesolutions.finai.ui.aibudget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorBudgetRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorLegalRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.repository.AiBudgetDraftStore
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.LegalRepository
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
    /**
     * The server said AI processing needs consent. The screen says so and
     * links to Profile, the one place it is given (PM decision, 2026-11-06).
     */
    val aiOff: Boolean = false,
    /**
     * In setup only, where there is no Profile yet: the AI policy, asked for
     * once here so a new household's first budget can still be suggested.
     */
    val policy: AiPolicy? = null,
    val consentTicked: Boolean = false,
    val consentBusy: Boolean = false,
    val consentErrorKey: String? = null,
    val suggesting: Boolean = false,
    val starting: Boolean = false,
    /** Budgets are off for this household, or there is no income to divide: nothing to suggest. */
    val unavailable: Boolean = false,
    val locale: String = "en",
    /** Every category the household has, for "Add a category"; empty until read. */
    val categories: List<Category> = emptyList(),
    val adding: Boolean = false,
) {
    val currency: String get() = proposal?.currency.orEmpty()

    val block: AiBudgetBlock? get() = proposal?.let { AiBudgetEdit.blockingReason(draft, it) }

    val canStart: Boolean get() = proposal != null && block == null && !starting && !suggesting

    val canSuggestAgain: Boolean get() = (proposal?.attemptsLeft ?: 0) > 0 && !suggesting && !starting

    val shareLabel: String? get() = proposal?.let { AiBudgetEdit.shareLabel(it, locale) }

    val leftLabel: String? get() = proposal?.let { AiBudgetEdit.leftLabel(draft, it, locale) }

    val attemptsLabel: String? get() = proposal?.let { AiBudgetEdit.attemptsLabel(it, locale) }

    val symbol: String get() = Money.symbol(currency)

    fun lineBlock(index: Int): AiBudgetBlock? = draft.lines.getOrNull(index)?.let { AiBudgetEdit.lineBlock(it, currency) }

    fun canRemove(index: Int): Boolean = !starting && draft.lines.getOrNull(index)?.let { AiBudgetEdit.canRemove(it, currency) } == true

    val addable: List<Category> get() = AiBudgetEdit.addable(categories, draft)
}

/**
 * The AI's suggested first budget (product decision, 2026-10-25): shown at the
 * end of setup, and from the Budget tab for a household already set up.
 *
 * Opening it shows the suggestion already made this month when there is one
 * — asking costs money, and the server allows three — and asks for one only
 * when there is none. The person's edits, lines added and removed included,
 * are kept on the phone ([AiBudgetDraftStore]) until Start writes the lines
 * as their own, so coming back shows them again without asking the AI.
 */
class AiBudgetViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AiBudgetUiState())
    val uiState: StateFlow<AiBudgetUiState> = _uiState.asStateFlow()

    private var repositories: AiBudgetRepositories? = null
    private var boundTo: String? = null

    fun bind(userId: String, logging: Boolean, drafts: AiBudgetDraftStore) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            AiBudgetRepositories(
                budgets = KtorBudgetRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                drafts = drafts,
                legal = KtorLegalRepository(ApiConfig.BASE_URL, tokens, logging),
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
                _uiState.update { it.copy(loading = false, suggesting = false, aiOff = true) }
                loadPolicy()
            } catch (e: ApiException.AiBudgetLimitReached) {
                // The last one stays; show it rather than nothing.
                runCatching { repos.budgets.setupSuggestion() }.getOrNull()?.let(::show) ?: failed(e)
            } catch (e: ApiException) {
                failed(e)
            }
        }
    }

    private fun show(proposal: AiProposal) {
        val kept = boundTo?.let { repositories?.drafts?.load(it) }
        _uiState.update {
            it.copy(
                loading = false,
                suggesting = false,
                proposal = proposal,
                draft = AiBudgetEdit.restore(kept, proposal) ?: AiBudgetEdit.draftOf(proposal),
                errorKey = null,
                aiOff = false,
            )
        }
        loadCategories()
    }

    /** For "Add a category"; quietly, since the screen works without it. */
    private fun loadCategories() {
        val repos = repositories ?: return
        if (_uiState.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val categories = repos.categories.list()
                _uiState.update { it.copy(categories = categories) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // The add button stays hidden with nothing to offer.
            }
        }
    }

    /** Every edit is kept at once, so leaving by any way loses nothing. */
    private fun edit(change: (AiBudgetUiState) -> AiBudgetUiState) {
        _uiState.update(change)
        val state = _uiState.value
        val proposal = state.proposal ?: return
        val user = boundTo ?: return
        repositories?.drafts?.save(user, AiBudgetEdit.encode(state.draft, proposal))
    }

    private fun failed(e: ApiException) = _uiState.update {
        val nothingToSuggest = e is ApiException.FeatureUnavailable || e is ApiException.SetupIncomeMissing
        it.copy(loading = false, suggesting = false, starting = false, errorKey = e.messageKey, unavailable = nothingToSuggest)
    }

    /** The policy for setup's one-time ask; quietly, the Skip stays either way. */
    private fun loadPolicy() {
        val legal = repositories?.legal ?: return
        viewModelScope.launch {
            try {
                val policy = legal.aiPolicy()
                _uiState.update { it.copy(policy = policy, consentTicked = false, consentErrorKey = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(consentErrorKey = e.messageKey) }
            }
        }
    }

    fun onConsentTicked(ticked: Boolean) = _uiState.update { it.copy(consentTicked = ticked) }

    /** Setup's one-time agree: records it, then asks for the suggestion. */
    fun agreeToAi() {
        val legal = repositories?.legal ?: return
        val state = _uiState.value
        val policy = state.policy ?: return
        if (!state.consentTicked || state.consentBusy) return
        _uiState.update { it.copy(consentBusy = true, consentErrorKey = null) }
        viewModelScope.launch {
            try {
                legal.consentToAi(policy.version)
                _uiState.update { it.copy(consentBusy = false, aiOff = false, policy = null, consentTicked = false) }
                suggest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.AiPolicyChanged) {
                _uiState.update { it.copy(consentBusy = false) }
                loadPolicy()
            } catch (e: ApiException) {
                _uiState.update { it.copy(consentBusy = false, consentErrorKey = e.messageKey) }
            }
        }
    }

    /** Back from Profile with AI perhaps turned on: the suggestion, as on opening. */
    fun retryAfterAi() {
        if (!_uiState.value.aiOff) return
        _uiState.update { it.copy(aiOff = false) }
        load()
    }

    // ── Editing ─────────────────────────────────────────────────────────

    fun onAmount(index: Int, amount: String) = edit {
        it.copy(draft = AiBudgetEdit.withAmount(it.draft, index, amount), errorKey = null)
    }

    fun onSavings(amount: String) = edit { it.copy(draft = it.draft.copy(savings = amount), errorKey = null) }

    fun openAdd() = _uiState.update { it.copy(adding = true) }

    fun closeAdd() = _uiState.update { it.copy(adding = false) }

    fun onAdd(category: Category) = edit {
        it.copy(draft = AiBudgetEdit.withLineAdded(it.draft, category), adding = false, errorKey = null)
    }

    fun onRemove(index: Int) = edit {
        it.copy(draft = AiBudgetEdit.withoutLine(it.draft, index, it.currency), errorKey = null)
    }

    /** Start the budget from what is on screen; [onDone] once it is written. */
    fun start(onDone: () -> Unit) {
        val repos = repositories ?: return
        val state = _uiState.value
        if (!state.canStart) return
        _uiState.update { it.copy(starting = true, errorKey = null) }
        viewModelScope.launch {
            try {
                repos.budgets.acceptSetup(AiBudgetEdit.accept(state.draft, state.currency))
                boundTo?.let { repos.drafts.clear(it) }
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
    val categories: CategoriesRepository,
    val drafts: AiBudgetDraftStore,
    /** Setup's one-time AI consent; null where it is never asked. */
    val legal: LegalRepository? = null,
) {
    fun close() {
        budgets.close()
        categories.close()
        legal?.close()
    }
}
