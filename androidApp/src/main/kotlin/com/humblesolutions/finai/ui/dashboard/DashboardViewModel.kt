package com.humblesolutions.finai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorDashboardRepository
import com.humblesolutions.finai.data.KtorFinancialSetupRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CommitmentEdit
import com.humblesolutions.finai.usecase.DashboardMonths
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

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            DashboardRepositories(
                dashboard = KtorDashboardRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                setup = KtorFinancialSetupRepository(ApiConfig.BASE_URL, tokens, logging),
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
                val locale = orNull { repos.capabilities.fetch() }?.locale.orEmpty()
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        data = data,
                        locale = locale.ifBlank { it.locale },
                        loading = false,
                        refreshing = false,
                        loadFailed = false,
                        errorKey = null,
                    )
                }
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

    // ── Editing a commitment ────────────────────────────────────────────

    /** Open the editor on the commitment at [index] in the month's list. */
    fun editCommitment(index: Int) {
        val commitment = _uiState.value.data.commitments.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                editingCommitment = commitment,
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

    fun cancelCommitment() {
        if (_uiState.value.commitmentSaving) return
        _uiState.update { it.copy(editingCommitment = null, commitmentErrorKey = null) }
    }

    /**
     * Read the wizard's answers, change the one commitment, write them back.
     *
     * The server keeps commitments as one list replaced whole, with no ids, so
     * there is no smaller request to make. If the commitment is no longer in
     * the list — changed on another phone since this month was read — nothing
     * is written over it: the month is re-read and the person told.
     */
    fun saveCommitment() {
        val repos = repositories ?: return
        val setupRepository = repos.setup ?: return
        val state = _uiState.value
        val original = state.editingCommitment ?: return
        if (!state.canSaveCommitment) return
        val draft = state.commitmentDraft
        _uiState.update { it.copy(commitmentSaving = true, commitmentErrorKey = null) }
        viewModelScope.launch {
            try {
                val setup = setupRepository.get()
                val changed = CommitmentEdit.applied(setup, original, draft)
                if (changed == null) {
                    _uiState.update {
                        it.copy(commitmentSaving = false, commitmentErrorKey = Strings.commitment_edit_gone)
                    }
                    load(refresh = true)
                    return@launch
                }
                setupRepository.save(changed)
                _uiState.update { it.copy(commitmentSaving = false, editingCommitment = null) }
                load(refresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(commitmentSaving = false, commitmentErrorKey = e.messageKey) }
            }
        }
    }

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
) {
    fun close() {
        dashboard.close()
        capabilities.close()
        transactions?.close()
        categories?.close()
        setup?.close()
    }
}

/** As many as the design shows; the rest are one tap away under View all. */
internal const val RECENT_COUNT = 3
