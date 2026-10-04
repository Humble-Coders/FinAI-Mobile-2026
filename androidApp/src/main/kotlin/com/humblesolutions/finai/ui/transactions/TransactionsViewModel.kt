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
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.StatementImportRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ReviewQueue
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

    /** The rows seen at the last read, so a change can tell which ones it added. */
    private var knownIds: Set<String> = emptySet()

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
        knownIds = emptySet()
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
            // Statements, months and the slice on screen together, so the
            // screen can move to what just landed rather than sit on the
            // month it was showing.
            LedgerChanged.events.collect { loadSlices(afterChange = true) }
        }
    }

    /**
     * The statements and months on offer, then the first page of the slice to
     * show: the newest on a first load, or — [afterChange] — wherever the
     * change landed; see [TransactionBrowsing.monthAfterChange].
     */
    private fun loadSlices(afterChange: Boolean = false) {
        val repos = repositories ?: return
        val started = generation
        if (afterChange) _uiState.update { it.copy(refreshing = true) }
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
            // Which months have anything in them, from the transactions'
            // own dates. Losing this leaves the current month on offer.
            val everything = orEmpty { readAll(repos) }
            if (started != generation) return@launch
            val today = DashboardMonths.current()
            val known = if (afterChange) knownIds else emptySet()
            knownIds = everything.map { it.id }.toSet()
            _uiState.update {
                it.copy(
                    statements = TransactionBrowsing.statementsFrom(imports),
                    months = TransactionBrowsing.monthsWithRows(everything, today),
                    categories = categories,
                    statementId = TransactionBrowsing.statementAfterChange(
                        current = it.statementId.takeIf { afterChange },
                        before = if (afterChange) it.statements else emptyList(),
                        imports = imports,
                    ),
                    month = TransactionBrowsing.monthAfterChange(it.month.takeIf { afterChange }, known, everything, today),
                )
            }
            load(refresh = afterChange)
        }
    }

    /** Every row, newest first, page after page — capped; see [CATEGORY_PAGES]. */
    private suspend fun readAll(repos: TransactionsRepositories): List<Transaction> {
        var page = repos.transactions.list()
        val rows = page.rows.toMutableList()
        var pages = 1
        while (page.nextCursor != null && pages < CATEGORY_PAGES) {
            page = repos.transactions.list(cursor = page.nextCursor)
            rows += page.rows
            pages++
        }
        return rows
    }

    private suspend fun <T> orEmpty(read: suspend () -> List<T>): List<T> = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        emptyList()
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
                var page = repos.transactions.list(
                    statementImportId = query.statementImportId,
                    month = query.month,
                )
                val rows = page.rows.toMutableList()
                // By category, every page: a category's total over half the
                // ledger is a wrong total, and the headings would move as
                // more loaded. Capped, so a vast ledger stops and offers
                // "Show more" rather than reading forever.
                if (state.mode == TransactionBrowsing.Mode.BY_CATEGORY) {
                    var pages = 1
                    while (page.nextCursor != null && pages < CATEGORY_PAGES) {
                        page = repos.transactions.list(cursor = page.nextCursor)
                        if (started != generation) return@launch
                        rows += page.rows
                        pages++
                    }
                }
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        rows = rows,
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

    // ── Editing a row ───────────────────────────────────────────────────

    /** Open the editor on [id], filled in as the row stands. */
    fun edit(id: String) {
        val row = _uiState.value.rows.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                editing = row,
                draft = ReviewQueue.draftOf(row),
                editErrorKey = null,
                saving = false,
                today = ManualEntry.today(),
            )
        }
    }

    fun cancelEdit() {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(editing = null, draft = CorrectionDraft(), editErrorKey = null) }
    }

    fun onDateChange(date: LocalDate) = editDraft { it.copy(occurredOn = date) }

    fun onAmountChange(value: String) = editDraft { it.copy(amount = value) }

    fun onDirectionChange(direction: TransactionDirection) = editDraft { it.copy(direction = direction) }

    fun onDescriptionChange(value: String) = editDraft { it.copy(description = value) }

    fun onCategoryChosen(id: String) = editDraft { it.copy(categoryId = id) }

    private fun editDraft(change: (CorrectionDraft) -> CorrectionDraft) {
        if (_uiState.value.editing == null) return
        _uiState.update { it.copy(draft = change(it.draft), editErrorKey = null) }
    }

    /**
     * Send only what changed, through the same rule as the review queue.
     *
     * On success the ledger has moved, so it is announced: home re-reads its
     * month and this list re-reads its slice — which is also what moves a row
     * whose new date took it out of the month being looked at.
     */
    fun saveEdit() {
        val repos = repositories ?: return
        val state = _uiState.value
        val row = state.editing ?: return
        if (state.saving) return
        val patch = ReviewQueue.correction(row, state.draft, state.today) ?: return
        _uiState.update { it.copy(saving = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.transactions.correct(row.id, patch)
                _uiState.update { it.copy(saving = false, editing = null, draft = CorrectionDraft()) }
                LedgerChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // A duplicate is said in the sheet, with the row still open:
                // the person's draft is the thing they need to fix.
                _uiState.update { it.copy(saving = false, editErrorKey = e.messageKey) }
            }
        }
    }

    fun openNewCategory() = _uiState.update { it.copy(newCategoryName = "", newCategoryErrorKey = null) }

    fun onNewCategoryName(name: String) = _uiState.update { it.copy(newCategoryName = name, newCategoryErrorKey = null) }

    fun cancelNewCategory() {
        if (_uiState.value.creatingCategory) return
        _uiState.update { it.copy(newCategoryName = null, newCategoryErrorKey = null) }
    }

    /** Adds the category and files the row being edited into it. */
    fun createCategory() {
        val repos = repositories ?: return
        val name = _uiState.value.newCategoryName?.trim().orEmpty()
        if (name.isEmpty() || _uiState.value.creatingCategory) return
        _uiState.update { it.copy(creatingCategory = true, newCategoryErrorKey = null) }
        viewModelScope.launch {
            try {
                val made = repos.categories.create(name)
                _uiState.update {
                    it.copy(
                        creatingCategory = false,
                        newCategoryName = null,
                        categories = it.categories + made,
                        draft = it.draft.copy(categoryId = made.id),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.CategoryExists) {
                // Taken by one they can already use, so use it.
                _uiState.update {
                    it.copy(
                        creatingCategory = false,
                        newCategoryName = null,
                        draft = it.draft.copy(categoryId = e.categoryId ?: it.draft.categoryId),
                    )
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(creatingCategory = false, newCategoryErrorKey = e.messageKey) }
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

/** Pages read up front when browsing by category; see `load`. */
internal const val CATEGORY_PAGES = 20
