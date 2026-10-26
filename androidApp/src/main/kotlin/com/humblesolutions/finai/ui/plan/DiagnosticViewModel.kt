package com.humblesolutions.finai.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorAiConsentRepository
import com.humblesolutions.finai.data.KtorPlanRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.DiagnosticBlock
import com.humblesolutions.finai.usecase.DiagnosticCheck
import com.humblesolutions.finai.usecase.DiagnosticRow
import com.humblesolutions.finai.usecase.PlanDiagnosticWords
import com.humblesolutions.finai.util.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One diagnostic, as the screen reads it. */
data class DiagnosticUiState(
    val kind: String = PlanDiagnostics.ALLOCATION,
    val loading: Boolean = true,
    val diagnostics: PlanDiagnostics? = null,
    /** The answer on screen: this visit's run, else the last one. */
    val result: PlanDiagnostic? = null,
    val savingsTarget: String = "",
    val savingsNow: String = "",
    val running: Boolean = false,
    val errorKey: String? = null,
    /** The AI may not be asked yet: the person is shown the policy first. */
    val policy: AiPolicy? = null,
    val consentTicked: Boolean = false,
    val consentBusy: Boolean = false,
    /** "Pay debt before goals" is being saved, or was. */
    val applying: Boolean = false,
    val applied: Boolean = false,
    val locale: String = "en",
) {
    val currency: String get() = diagnostics?.currency.orEmpty()

    val symbol: String get() = Money.symbol(currency)

    val title: String get() = PlanDiagnosticWords.title(kind, locale)

    val question: String get() = PlanDiagnosticWords.question(kind, locale)

    val block: DiagnosticBlock? get() = PlanDiagnosticWords.blockingReason(savingsTarget, savingsNow, currency)

    val runsLeft: Int get() = diagnostics?.runsLeftFor(kind) ?: 0

    val runsLeftLabel: String? get() = diagnostics?.let { PlanDiagnosticWords.runsLeft(it, kind, locale) }

    val canRun: Boolean get() = diagnostics != null && block == null && !running && !consentBusy

    val rows: List<DiagnosticRow>
        get() = result?.let {
            if (it.isAllocation) PlanDiagnosticWords.allocationRows(it, locale) else PlanDiagnosticWords.investRows(it, locale)
        }.orEmpty()

    val checks: List<DiagnosticCheck> get() = result?.takeUnless { it.isAllocation }?.let { PlanDiagnosticWords.investChecks(it, locale) }.orEmpty()

    val comparison: String? get() = result?.takeIf { it.isAllocation }?.let { PlanDiagnosticWords.comparison(it, locale) }

    val payoff: String? get() = result?.takeIf { it.isAllocation }?.let { PlanDiagnosticWords.payoff(it, locale) }

    fun stepLabel(key: String): String = PlanDiagnosticWords.stepLabel(key, locale)

    val recommendation: String? get() = result?.takeIf { it.isAllocation }?.let { PlanDiagnosticWords.recommendation(it, locale) }

    val orders: List<DiagnosticRow> get() = result?.takeIf { it.isAllocation }?.let { PlanDiagnosticWords.orders(it, locale) }.orEmpty()

    val waiting: String? get() = result?.takeIf { it.isAllocation }?.let { PlanDiagnosticWords.waiting(it, locale) }

    val canApplyDebtFirst: Boolean get() = !applied && result?.let(PlanDiagnosticWords::canApplyDebtFirst) == true
}

/**
 * Your plan's diagnostics: "Debt, goals or savings?" and "Should I invest?".
 *
 * The form starts from the savings figures the person last ran with (else the
 * Emergency fund goal's, else the plan's suggested buffer). Running with the
 * same figures returns the stored answer at no cost; the server allows four
 * fresh runs of each a month.
 */
class DiagnosticViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticUiState())
    val uiState: StateFlow<DiagnosticUiState> = _uiState.asStateFlow()

    private var repositories: DiagnosticRepositories? = null
    private var boundTo: String? = null

    fun bind(userId: String, kind: String, logging: Boolean) = bind(userId, kind) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            DiagnosticRepositories(
                plan = KtorPlanRepository(ApiConfig.BASE_URL, tokens, logging),
                consent = KtorAiConsentRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, kind: String, build: () -> DiagnosticRepositories?) {
        if (userId.isBlank()) return
        if (userId == boundTo && repositories != null) {
            if (_uiState.value.kind != kind) show(kind)
            return
        }
        repositories?.close()
        boundTo = userId
        _uiState.value = DiagnosticUiState(kind = kind)
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    private fun show(kind: String) = _uiState.update {
        it.copy(kind = kind, result = it.diagnostics?.last(kind), errorKey = null)
    }

    fun load() {
        val repos = repositories ?: return
        _uiState.update { it.copy(loading = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val found = repos.plan.diagnostics()
                _uiState.update {
                    it.copy(
                        loading = false,
                        diagnostics = found,
                        result = found.last(it.kind),
                        savingsTarget = found.savingsTarget,
                        savingsNow = found.savingsNow,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, errorKey = e.messageKey) }
            }
        }
    }

    fun onSavingsTarget(amount: String) = _uiState.update { it.copy(savingsTarget = amount, errorKey = null) }

    fun onSavingsNow(amount: String) = _uiState.update { it.copy(savingsNow = amount, errorKey = null) }

    /** Run on what is in the form. */
    fun run() {
        val repos = repositories ?: return
        val state = _uiState.value
        if (!state.canRun) return
        val digits = Money.fractionDigits(state.currency)
        val target = Money.normalize(state.savingsTarget.ifBlank { "0" }, digits) ?: return
        val now = Money.normalize(state.savingsNow.ifBlank { "0" }, digits) ?: return
        _uiState.update { it.copy(running = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val answer = repos.plan.runDiagnostic(state.kind, target, now)
                // Re-read the counts: a fresh run used one up, a stored one did not.
                val counts = runCatching { repos.plan.diagnostics() }.getOrNull()
                _uiState.update { it.copy(running = false, result = answer, diagnostics = counts ?: it.diagnostics) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.ConsentRequired) {
                _uiState.update { it.copy(running = false) }
                askForConsent()
            } catch (e: ApiException) {
                _uiState.update { it.copy(running = false, errorKey = e.messageKey) }
            }
        }
    }

    /**
     * "Pay debt before goals": the plan's goal share set to 0%, every other
     * choice kept as it is now — the same save as Your choices, one figure changed.
     */
    fun applyDebtFirst() {
        val repos = repositories ?: return
        if (!_uiState.value.canApplyDebtFirst || _uiState.value.applying) return
        _uiState.update { it.copy(applying = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val now = repos.plan.plan(null).preferences
                repos.plan.savePreferences(
                    PlanPreferencesToSave(
                        buffer = now.buffer,
                        goalSharePercent = "0",
                        rateLinePercent = now.rateLinePercent,
                        strategy = now.strategy,
                    ),
                )
                _uiState.update { it.copy(applying = false, applied = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(applying = false, errorKey = e.messageKey) }
            }
        }
    }

    // ── Consent ─────────────────────────────────────────────────────────

    private suspend fun askForConsent() {
        val consent = repositories?.consent ?: return
        try {
            val policy = consent.policy()
            _uiState.update { it.copy(policy = policy, consentTicked = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _uiState.update { it.copy(errorKey = e.messageKey) }
        }
    }

    fun onConsentTicked(ticked: Boolean) = _uiState.update { it.copy(consentTicked = ticked) }

    /** Records consent to the version shown, then runs. */
    fun agree() {
        val consent = repositories?.consent ?: return
        val policy = _uiState.value.policy ?: return
        if (!_uiState.value.consentTicked) return
        _uiState.update { it.copy(consentBusy = true, errorKey = null) }
        viewModelScope.launch {
            try {
                consent.consent(policy.version)
                _uiState.update { it.copy(consentBusy = false, policy = null, consentTicked = false) }
                run()
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
}

/** What this screen talks to. Closed together when the model is cleared. */
internal class DiagnosticRepositories(
    val plan: PlanRepository,
    val consent: AiConsentRepository,
) {
    fun close() {
        plan.close()
        consent.close()
    }
}
