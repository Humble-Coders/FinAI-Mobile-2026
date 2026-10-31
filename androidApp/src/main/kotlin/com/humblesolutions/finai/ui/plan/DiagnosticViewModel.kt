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
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.DiagnosticBlock
import com.humblesolutions.finai.usecase.DiagnosticCheck
import com.humblesolutions.finai.usecase.DiagnosticRow
import com.humblesolutions.finai.usecase.PlanDiagnosticWords
import com.humblesolutions.finai.usecase.YourPlan
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.PlanChanged
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
    /** The invest diagnostic's two figures. */
    val savingsTarget: String = "",
    val savingsNow: String = "",
    /** The allocation diagnostic's one figure: what is saved each month. Blank uses the Budget's savings target. */
    val monthlySavings: String = "",
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

    val isAllocation: Boolean get() = kind == PlanDiagnostics.ALLOCATION

    val block: DiagnosticBlock?
        get() = if (isAllocation) {
            PlanDiagnosticWords.monthlyBlockingReason(monthlySavings, currency)
        } else {
            PlanDiagnosticWords.blockingReason(savingsTarget, savingsNow, currency)
        }

    val monthlyLabel: String get() = PlanDiagnosticWords.monthlyLabel(locale)

    val monthlyHint: String get() = PlanDiagnosticWords.monthlyHint(locale)

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
 * "Debt, goals or savings?" asks one figure — what is saved each month —
 * starting at the Budget's savings target, and running it makes that the
 * Budget's target (redesign, 2026-10-31). "Should I invest?" keeps its two
 * savings figures. Running with the same figures returns the stored answer at
 * no cost; the server allows four fresh runs of each a month.
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
                        monthlySavings = found.monthlySavings,
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

    fun onMonthlySavings(amount: String) = _uiState.update { it.copy(monthlySavings = amount, errorKey = null) }

    /** Run on what is in the form. */
    fun run() {
        val repos = repositories ?: return
        val state = _uiState.value
        if (!state.canRun) return
        val digits = Money.fractionDigits(state.currency)
        val target: String?
        val now: String?
        val monthly: String?
        if (state.isAllocation) {
            target = null
            now = null
            // Blank: the server uses the Budget's savings target.
            monthly = state.monthlySavings.takeIf { it.isNotBlank() }?.let { Money.normalize(it, digits) ?: return }
        } else {
            target = Money.normalize(state.savingsTarget.ifBlank { "0" }, digits) ?: return
            now = Money.normalize(state.savingsNow.ifBlank { "0" }, digits) ?: return
            monthly = null
        }
        _uiState.update { it.copy(running = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val answer = repos.plan.runDiagnostic(state.kind, target, now, monthly)
                if (state.isAllocation) {
                    // The monthly saving is now the Budget's savings target, and
                    // the plan divides what the Budget leaves over.
                    BudgetChanged.announce()
                    PlanChanged.announce()
                }
                // Re-read the counts: a fresh run used one up, a stored one did not.
                val counts = try {
                    repos.plan.diagnostics()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    null
                }
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
     * "Pay debt before goals": debt moved to the top of the choices with the
     * goals' share added to it, the goals given none — the same save as Your
     * choices. Without a debt choice there is nothing to change.
     */
    fun applyDebtFirst() {
        val repos = repositories ?: return
        if (!_uiState.value.canApplyDebtFirst || _uiState.value.applying) return
        _uiState.update { it.copy(applying = true, errorKey = null) }
        viewModelScope.launch {
            try {
                val choices = YourPlan.debtFirstChoices(repos.plan.plan(null).preferences)
                if (choices == null) {
                    _uiState.update { it.copy(applying = false) }
                    return@launch
                }
                repos.plan.savePreferences(choices)
                _uiState.update { it.copy(applying = false, applied = true) }
                // Home's plan card and Goals' suggested amounts follow the plan.
                PlanChanged.announce()
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
