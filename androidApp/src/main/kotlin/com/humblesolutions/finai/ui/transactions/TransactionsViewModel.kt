package com.humblesolutions.finai.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorStatementImportRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.StatementImportRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.TransactionBrowsing
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything the household has, one statement or one month at a time (#F3).
 *
 * Which filter each mode sends is decided by [TransactionBrowsing] and read
 * the same way by iOS. This model moves answers in and out.
 */
class TransactionsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private var repositories: TransactionsRepositories? = null
    private var boundTo: String? = null

    /** Rises on every bind and every change of slice, so a slow page for a
     *  slice the user has left cannot land on the one they are looking at. */
    private var generation = 0

    private var listening = false

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            TransactionsRepositories(
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                imports = KtorStatementImportRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> TransactionsRepositories?) {
        if (userId.isBlank() || userId == boundTo) return
        repositories?.close()
        generation++
        boundTo = userId
        _uiState.value = TransactionsUiState()
        repositories = build() ?: return
        listenForChanges()
        loadSlices()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    /**
     * Re-read when something changed the ledger, so this screen does not go
     * stale behind a correction made in the review queue.
     */
    private fun listenForChanges() {
        if (listening) return
        listening = true
        viewModelScope.launch {
            LedgerChanged.events.collect { load(refresh = true) }
        }
    }

    /** The statements and months on offer, then the first page of the default slice. */
    private fun loadSlices() {
        val repos = repositories ?: return
        val started = generation
        viewModelScope.launch {
            val imports = try {
                repos.imports.list().imports
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // The pickers are how the screen is steered, but the rows are
                // the screen. Losing the pickers leaves browsing by month,
                // which always offers the current one.
                emptyList()
            }
            val categories = try {
                repos.categories.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                emptyList()
            }
            if (started != generation) return@launch
            _uiState.update {
                it.copy(
                    statements = TransactionBrowsing.statementsFrom(imports),
                    months = TransactionBrowsing.monthsFrom(imports, DashboardMonths.current()),
                    categories = categories,
                    statementId = TransactionBrowsing.statementsFrom(imports).firstOrNull()?.id,
                    month = DashboardMonths.current(),
                )
            }
            load()
        }
    }

    fun showMode(mode: TransactionBrowsing.Mode) {
        if (mode == _uiState.value.mode) return
        _uiState.update { it.copy(mode = mode) }
        load(refresh = true)
    }

    fun showStatement(id: String) {
        if (id == _uiState.value.statementId) return
        _uiState.update { it.copy(statementId = id) }
        load(refresh = true)
    }

    fun showMonth(month: LocalDate) {
        if (month == _uiState.value.month) return
        _uiState.update { it.copy(month = month) }
        load(refresh = true)
    }

    /**
     * @param refresh changing slice with rows already on screen. They stay up
     *   until the new ones arrive rather than the list emptying on every tap.
     */
    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val state = _uiState.value
        val started = ++generation
        val query = TransactionBrowsing.query(state.mode, state.statementId, state.month)
        _uiState.update {
            it.copy(loading = !refresh, refreshing = refresh, loadFailed = false, errorKey = null)
        }
        viewModelScope.launch {
            try {
                val page = repos.transactions.list(
                    statementImportId = query.statementImportId,
                    month = query.month,
                )
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        rows = page.rows,
                        nextCursor = page.nextCursor,
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
                        // A failed refresh keeps the rows it had; a first load
                        // has nothing to keep and must say so.
                        loadFailed = !refresh,
                        errorKey = e.messageKey,
                    )
                }
            }
        }
    }

    /** The next page, keyed off the last one's cursor — never an offset. */
    fun loadMore() {
        val repos = repositories ?: return
        val state = _uiState.value
        val cursor = state.nextCursor ?: return
        if (state.loadingMore) return
        val started = generation
        val query = TransactionBrowsing.query(state.mode, state.statementId, state.month)
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repos.transactions.list(
                    statementImportId = query.statementImportId,
                    month = query.month,
                    cursor = cursor,
                )
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        rows = it.rows + page.rows,
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(loadingMore = false, errorKey = e.messageKey) }
            }
        }
    }
}

internal class TransactionsRepositories(
    val transactions: TransactionsRepository,
    val imports: StatementImportRepository,
    val categories: CategoriesRepository,
) {
    fun close() {
        transactions.close()
        imports.close()
        categories.close()
    }
}
