package com.humblesolutions.finai.ui.money

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
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CommitmentDraft
import com.humblesolutions.finai.usecase.CommitmentEdit
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.MoneyDetail
import com.humblesolutions.finai.usecase.MoneyKind
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives one of the four money screens. One model, four screens: what differs
 * between them — which rows, which figure, which comparison — is decided by
 * [MoneyDetail], so the four cannot drift apart.
 *
 * A change to the ledger anywhere (an entry from this screen's own sheet, an
 * import, a correction) re-reads the month, so a figure never sits stale under
 * a row that changed it.
 */
class MoneyDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MoneyDetailUiState())
    val uiState: StateFlow<MoneyDetailUiState> = _uiState.asStateFlow()

    private var repositories: MoneyDetailRepositories? = null
    private var boundTo: Pair<String, MoneyKind>? = null
    private var generation = 0
    private var listening = false

    fun bind(userId: String, kind: MoneyKind, logging: Boolean) = bind(userId, kind) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            MoneyDetailRepositories(
                dashboard = KtorDashboardRepository(ApiConfig.BASE_URL, tokens, logging),
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                setup = KtorFinancialSetupRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, kind: MoneyKind, build: () -> MoneyDetailRepositories?) {
        if (userId.isBlank() || boundTo == userId to kind) return
        repositories?.close()
        generation++
        boundTo = userId to kind
        _uiState.value = MoneyDetailUiState(kind = kind)
        repositories = build() ?: return
        listenForChanges()
        load()
    }

    private fun listenForChanges() {
        if (listening) return
        listening = true
        viewModelScope.launch { LedgerChanged.events.collect { load(refresh = true) } }
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /** The month's figures, the screen's rows, and what the lists need beside them. */
    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = ++generation
        val state = _uiState.value
        _uiState.update { it.copy(loading = !refresh && it.rows.isEmpty(), refreshing = refresh, loadFailed = false, errorKey = null) }
        viewModelScope.launch {
            try {
                val query = MoneyDetail.query(state.kind, state.month)
                // In a scope of their own, so one failing ends the rest and
                // the failure itself is what is thrown. Awaited directly
                // under launch, a failed request cancelled its siblings and
                // the first await reported the cancellation instead — the
                // error was lost and the screen sat on its loader.
                val loaded = coroutineScope {
                    val dashboard = async { repos.dashboard.read(query.month) }
                    val page = async { repos.transactions.browse(query.month, query.direction, query.categorySlug, null) }
                    // Each a list beside the figures, not the figures: losing
                    // one leaves the screen readable rather than failed.
                    val categories = async { orNull { repos.categories.list() } }
                    val setup = async { orNull { repos.setup.get() } }
                    val locale = async { orNull { repos.capabilities.fetch() }?.locale }
                    Loaded(dashboard.await(), page.await(), categories.await(), setup.await(), locale.await())
                }
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        data = loaded.data,
                        rows = loaded.page.rows,
                        nextCursor = loaded.page.nextCursor,
                        categories = loaded.categories ?: it.categories,
                        setup = loaded.setup ?: it.setup,
                        locale = loaded.locale?.ifBlank { null } ?: it.locale,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // A failed refresh keeps what was on screen; only a first load fails outright.
                _uiState.update { it.copy(loading = false, refreshing = false, loadFailed = it.rows.isEmpty() && !refresh, errorKey = e.messageKey) }
            }
        }
    }

    fun loadMore() {
        val repos = repositories ?: return
        val state = _uiState.value
        val cursor = state.nextCursor ?: return
        if (state.loadingMore) return
        val started = generation
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val query = MoneyDetail.query(state.kind, state.month)
                val page = repos.transactions.browse(query.month, query.direction, query.categorySlug, cursor)
                if (started != generation) return@launch
                _uiState.update { it.copy(loadingMore = false, rows = it.rows + page.rows, nextCursor = page.nextCursor) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { it.copy(loadingMore = false, errorKey = e.messageKey) }
            }
        }
    }

    fun showMonth(month: LocalDate) {
        val first = DashboardMonths.first(month)
        if (first == _uiState.value.month) return
        _uiState.update { it.copy(month = first, rows = emptyList(), nextCursor = null, query = "") }
        load(refresh = true)
    }

    fun showTab(tab: ExpensesTab) = _uiState.update { it.copy(tab = tab) }

    fun openSearch() = _uiState.update { it.copy(searching = true) }

    fun closeSearch() = _uiState.update { it.copy(searching = false, query = "") }

    fun onQuery(query: String) = _uiState.update { it.copy(query = query) }

    fun dismissNotice() = _uiState.update { it.copy(noticeKey = null) }

    // ── Adding an obligation ────────────────────────────────────────────

    fun openObligation() = _uiState.update {
        it.copy(obligationDraft = CommitmentDraft(), obligationTouched = false, obligationErrorKey = null)
    }

    fun onObligationChange(draft: CommitmentDraft) = _uiState.update {
        it.copy(obligationDraft = draft, obligationTouched = true, obligationErrorKey = null)
    }

    fun cancelObligation() {
        if (_uiState.value.savingObligation) return
        _uiState.update { it.copy(obligationDraft = null, obligationErrorKey = null) }
    }

    fun saveObligation() {
        val state = _uiState.value
        val draft = state.obligationDraft ?: return
        if (!state.canSaveObligation) {
            _uiState.update { it.copy(obligationTouched = true) }
            return
        }
        _uiState.update { it.copy(savingObligation = true, obligationErrorKey = null) }
        viewModelScope.launch {
            val ok = addObligation(draft)
            _uiState.update {
                if (ok == null) {
                    it.copy(savingObligation = false, obligationDraft = null)
                } else {
                    it.copy(savingObligation = false, obligationErrorKey = ok)
                }
            }
            if (ok == null) load(refresh = true)
        }
    }

    /**
     * "Mark as obligation" on a saved entry: the entry is already in, so a
     * failure here says so and points at where to add it, rather than
     * pretending the save failed.
     */
    fun markAsObligation(name: String, amount: String, dueDay: Int?) {
        viewModelScope.launch {
            val failed = addObligation(CommitmentDraft(name = name, amount = amount, dueDay = dueDay?.toString().orEmpty()))
            _uiState.update { it.copy(noticeKey = if (failed == null) Strings.money_obligation_added else Strings.money_obligation_failed) }
            if (failed == null) load(refresh = true)
        }
    }

    /** Null when it was added; otherwise the message key saying why not. */
    private suspend fun addObligation(draft: CommitmentDraft): String? {
        val repos = repositories ?: return Strings.error_unexpected
        return try {
            // Read fresh, then write the whole list back: the server keeps the
            // wizard's answers as one list, and a stale one would overwrite
            // a change made elsewhere.
            val setup = repos.setup.get()
            val next = CommitmentEdit.added(setup, draft) ?: return Strings.commitment_add_limit
            repos.setup.save(next)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.messageKey
        }
    }

    private suspend fun <T> orNull(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }
}

private class Loaded(
    val data: Dashboard,
    val page: ReviewPage,
    val categories: List<Category>?,
    val setup: FinancialSetup?,
    val locale: String?,
)

internal class MoneyDetailRepositories(
    val dashboard: DashboardRepository,
    val transactions: TransactionsRepository,
    val categories: CategoriesRepository,
    val setup: FinancialSetupRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        dashboard.close()
        transactions.close()
        categories.close()
        setup.close()
        capabilities.close()
    }
}
