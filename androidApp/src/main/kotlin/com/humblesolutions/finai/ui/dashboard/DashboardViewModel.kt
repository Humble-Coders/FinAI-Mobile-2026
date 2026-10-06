package com.humblesolutions.finai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorDashboardRepository
import com.humblesolutions.finai.data.KtorFinancialSetupRepository
import com.humblesolutions.finai.data.KtorGoalsRepository
import com.humblesolutions.finai.data.KtorHealthScoreRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.repository.GoalsRepository
import com.humblesolutions.finai.repository.HealthScoreRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CommitmentDraft
import com.humblesolutions.finai.usecase.CommitmentEdit
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.GoalsChanged
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the dashboard (PRD F3): one month at a time, and moving between them.
 *
 * Which month comes before this one, and every figure on the screen, are
 * decided elsewhere — [DashboardMonths] and the server. This model moves
 * answers in and out.
 *
 * Nothing is saved across process death. The screen is a read of the server
 * with no draft in it, and re-reading is both cheap and more honest than
 * restoring figures that may have changed.
 */
class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var repositories: DashboardRepositories? = null
    private var boundTo: String? = null

    /**
     * Rises on every bind and every month change, so a slow answer for the
     * month the user has already left cannot land on top of the one they are
     * looking at.
     */
    private var generation = 0

    /** One collector for the model's life; see [listenForChanges]. */
    private var listening = false

    /** The recent list's own counter; see [loadRecent]. */
    private var recentGeneration = 0

    /** The goals card's own counter; see [loadGoals]. */
    private var goalsGeneration = 0

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            DashboardRepositories(
                dashboard = KtorDashboardRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                setup = KtorFinancialSetupRepository(ApiConfig.BASE_URL, tokens, logging),
                healthScore = KtorHealthScoreRepository(ApiConfig.BASE_URL, tokens, logging),
                goals = KtorGoalsRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> DashboardRepositories?) {
        if (userId.isBlank() || userId == boundTo) return
        repositories?.close()
        generation++
        boundTo = userId
        _uiState.value = DashboardUiState()
        repositories = build() ?: return
        listenForChanges()
        load()
        loadRecent()
    }

    /**
     * Re-read whenever something changed the ledger.
     *
     * A refresh, not a load: the figures already on screen stay up while the
     * new ones arrive, so coming back from an import does not flash an empty
     * dashboard on the way to a full one.
     *
     * Collected once per bind. A second collector would re-read the month
     * twice for every write, which is invisible on a fast connection and a
     * doubled bill on a slow one.
     */
    private fun listenForChanges() {
        if (listening) return
        listening = true
        viewModelScope.launch {
            LedgerChanged.events.collect {
                load(refresh = true)
                loadRecent()
            }
        }
        // A line set on the Budget tab moves Home's budget section and, with
        // it, the score's spending part; the ledger is untouched, so only the
        // month is re-read.
        viewModelScope.launch {
            BudgetChanged.events.collect { load(refresh = true) }
        }
        // A goal created, topped up or reordered on the Goals tab moves only
        // the goals card; the month itself is untouched.
        viewModelScope.launch {
            GoalsChanged.events.collect { loadGoals() }
        }
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /**
     * @param refresh re-reading with something already on screen. The figures
     *   stay up and a failure keeps them, rather than blanking a month that
     *   was read successfully a moment ago.
     */
    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = ++generation
        val month = _uiState.value.month
        _uiState.update {
            it.copy(loading = !refresh, refreshing = refresh, loadFailed = false, errorKey = null)
        }
        viewModelScope.launch {
            try {
                val data = repos.dashboard.read(DashboardMonths.wire(month))
                val capabilities = orNull { repos.capabilities.fetch() }
                val locale = capabilities?.locale.orEmpty()
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        data = data,
                        locale = locale.ifBlank { it.locale },
                        // A failed read keeps the payload it had, rather than
                        // hiding the score and the budget on a blip.
                        capabilities = capabilities ?: it.capabilities,
                        loading = false,
                        refreshing = false,
                        loadFailed = false,
                        errorKey = null,
                    )
                }
                loadGoals()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // A refresh that fails keeps what it had; a first load
                        // has nothing to keep and must say so.
                        loadFailed = !refresh,
                        errorKey = e.messageKey,
                    )
                }
            }
        }
    }

    /**
     * The newest few rows, for the list under the figures.
     *
     * Its own generation, because it does not follow the month: stepping back
     * to August must not cancel a read of what happened most recently. And it
     * fails quietly — the figures are the screen, and a list that could not
     * load is simply not drawn rather than turning home into an error.
     */
    private fun loadRecent() {
        val repos = repositories ?: return
        val transactions = repos.transactions ?: return
        val started = ++recentGeneration
        viewModelScope.launch {
            val rows = orNull { transactions.recent(RECENT_COUNT) } ?: return@launch
            // A name beside each row is a nicety; the rows are the point.
            val categories = repos.categories?.let { orNull { it.list() } }
            if (started != recentGeneration) return@launch
            _uiState.update {
                it.copy(recent = rows, categories = categories ?: it.categories)
            }
        }
    }

    /**
     * The goals for Home's card (#52), read only when capabilities enable
     * them. Its own counter, like the recent rows, and quiet on failure: the
     * card is simply not drawn, rather than a blip turning Home into an error.
     */
    private fun loadGoals() {
        val repos = repositories ?: return
        val goals = repos.goals ?: return
        if (_uiState.value.capabilities?.isEnabled(GOALS_FEATURE) != true) return
        val started = ++goalsGeneration
        viewModelScope.launch {
            val page = orNull { goals.list() } ?: return@launch
            if (started != goalsGeneration) return@launch
            _uiState.update { it.copy(goals = page) }
        }
    }

    // ── Editing a commitment ────────────────────────────────────────────

    /** Open the editor on the commitment at [index] in the month's list. */
    fun editCommitment(index: Int) {
        val commitment = _uiState.value.data.commitments.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                editingCommitment = commitment,
                addingCommitment = false,
                confirmingCommitmentDelete = false,
                commitmentDraft = CommitmentEdit.draftOf(commitment),
                commitmentSaving = false,
                commitmentErrorKey = null,
            )
        }
    }

    fun onCommitmentName(name: String) = _uiState.update {
        it.copy(commitmentDraft = it.commitmentDraft.copy(name = name), commitmentErrorKey = null)
    }

    fun onCommitmentAmount(amount: String) = _uiState.update {
        it.copy(commitmentDraft = it.commitmentDraft.copy(amount = amount), commitmentErrorKey = null)
    }

    /** Open the editor empty, to add one. */
    fun addCommitment() = _uiState.update {
        it.copy(
            addingCommitment = true,
            editingCommitment = null,
            confirmingCommitmentDelete = false,
            commitmentDraft = CommitmentDraft(),
            commitmentSaving = false,
            commitmentErrorKey = null,
        )
    }

    fun cancelCommitment() {
        if (_uiState.value.commitmentSaving) return
        _uiState.update {
            it.copy(
                editingCommitment = null,
                addingCommitment = false,
                confirmingCommitmentDelete = false,
                commitmentErrorKey = null,
            )
        }
    }

    /** Ask before deleting: nothing is sent until the person says yes. */
    fun askDeleteCommitment() {
        if (_uiState.value.editingCommitment == null || _uiState.value.commitmentSaving) return
        _uiState.update { it.copy(confirmingCommitmentDelete = true) }
    }

    fun keepCommitment() = _uiState.update { it.copy(confirmingCommitmentDelete = false) }

    /**
     * Read the wizard's answers, change the one commitment, write them back.
     *
     * The server keeps commitments as one list replaced whole, with no ids, so
     * there is no smaller request to make. If the commitment is no longer in
     * the list — changed on another phone since this month was read — nothing
     * is written over it: the month is re-read and the person told.
     */
    fun saveCommitment() {
        val state = _uiState.value
        if (!state.canSaveCommitment) return
        val draft = state.commitmentDraft
        val original = state.editingCommitment
        if (state.addingCommitment) {
            // Null when the list filled up on another phone since this month
            // was read: the same refusal the count check gives up front.
            writeSetup(goneKey = Strings.commitment_add_limit) { CommitmentEdit.added(it, draft) }
        } else if (original != null) {
            writeSetup(goneKey = Strings.commitment_edit_gone) { CommitmentEdit.applied(it, original, draft) }
        }
    }

    /** Delete the commitment being edited, once the person has said yes. */
    fun deleteCommitment() {
        val state = _uiState.value
        val original = state.editingCommitment ?: return
        if (!state.confirmingCommitmentDelete || state.commitmentSaving) return
        _uiState.update { it.copy(confirmingCommitmentDelete = false) }
        writeSetup(goneKey = Strings.commitment_edit_gone) { CommitmentEdit.removed(it, original) }
    }

    /**
     * Read the wizard's answers, [change] them, and write them back; then
     * re-read the month so the list shows what was saved.
     *
     * [change] returns null when the answers moved on since this month was
     * read — on another phone, say. Nothing is written over them then: the
     * month is re-read and [goneKey] says why.
     */
    private fun writeSetup(goneKey: String, change: (FinancialSetup) -> FinancialSetup?) {
        val setupRepository = repositories?.setup ?: return
        _uiState.update { it.copy(commitmentSaving = true, commitmentErrorKey = null) }
        viewModelScope.launch {
            try {
                val changed = change(setupRepository.get())
                if (changed == null) {
                    _uiState.update { it.copy(commitmentSaving = false, commitmentErrorKey = goneKey) }
                    load(refresh = true)
                    return@launch
                }
                setupRepository.save(changed)
                _uiState.update {
                    it.copy(commitmentSaving = false, editingCommitment = null, addingCommitment = false)
                }
                load(refresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(commitmentSaving = false, commitmentErrorKey = e.messageKey) }
            }
        }
    }

    // ── The score's breakdown and "Where it went" (#46) ─────────────────

    /** Open the breakdown and read the score's parts; they cost nothing until wanted. */
    fun openBreakdown() {
        _uiState.update { it.copy(breakdownOpen = true) }
        loadBreakdown()
    }

    fun closeBreakdown() = _uiState.update { it.copy(breakdownOpen = false) }

    fun retryBreakdown() = loadBreakdown()

    private fun loadBreakdown() {
        val repository = repositories?.healthScore ?: return
        _uiState.update { it.copy(breakdownLoading = true, breakdownFailed = false) }
        viewModelScope.launch {
            val answer = orNull { repository.current() }
            _uiState.update {
                it.copy(
                    breakdown = answer ?: it.breakdown,
                    breakdownLoading = false,
                    // A breakdown already read stays up; only nothing at all is a failure.
                    breakdownFailed = answer == null && it.breakdown == null,
                )
            }
        }
    }

    /** "Show all" and back in "Where it went". */
    fun toggleSpending() = _uiState.update { it.copy(spendingExpanded = !it.spendingExpanded) }

    /** Mask or unmask every figure on screen. */
    fun toggleAmounts() = _uiState.update { it.copy(amountsHidden = !it.amountsHidden) }

    /** Step to the month before the one shown. */
    fun showPreviousMonth() = show(DashboardMonths.previous(_uiState.value.month))

    /** Step forward, which does nothing on the month that is running. */
    fun showNextMonth() {
        val state = _uiState.value
        if (!DashboardMonths.canGoForward(state.month)) return
        show(DashboardMonths.next(state.month))
    }

    private fun show(month: LocalDate) {
        if (month == _uiState.value.month) return
        _uiState.update { it.copy(month = month) }
        // A refresh, not a load: the month label changes at once and the old
        // figures stay until the new ones arrive, rather than the screen
        // emptying on every step.
        load(refresh = true)
    }

    private suspend fun <T> orNull(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }
}

internal class DashboardRepositories(
    val dashboard: DashboardRepository,
    val capabilities: CapabilitiesRepository,
    // Optional so a test about the month need not build a recent list too.
    val transactions: TransactionsRepository? = null,
    val categories: CategoriesRepository? = null,
    val setup: FinancialSetupRepository? = null,
    val healthScore: HealthScoreRepository? = null,
    val goals: GoalsRepository? = null,
) {
    fun close() {
        dashboard.close()
        capabilities.close()
        transactions?.close()
        categories?.close()
        setup?.close()
        healthScore?.close()
        goals?.close()
    }
}

/** As many as the design shows; the rest are one tap away under View all. */
internal const val RECENT_COUNT = 3
