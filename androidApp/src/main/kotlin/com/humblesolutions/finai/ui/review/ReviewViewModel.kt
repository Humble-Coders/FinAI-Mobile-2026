package com.humblesolutions.finai.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ReviewQueue
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the review queue (#32): the rows extraction could not resolve, and
 * what the person does about them.
 *
 * What may be confirmed, what a correction sends and what it changed beyond
 * its row are never decided here — [ReviewQueue] answers all of it, and iOS
 * reads the same rules. This model moves answers in and out.
 *
 * **A correction in progress survives the process being killed.** The row's id
 * and the draft go in [saved] as plain values, tied to the user who typed
 * them. The list itself is not saved: it is the server's, and re-reading it is
 * both cheap and more honest than restoring rows that may have changed.
 */
class ReviewViewModel(private val saved: SavedStateHandle) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var repositories: ReviewRepositories? = null
    private var boundTo: String? = null
    private var generation = 0

    /** The delete waiting out its undo window, so leaving can send it at once. */
    private var undoTimer: Job? = null

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            ReviewRepositories(
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> ReviewRepositories?) {
        if (userId.isBlank() || userId == boundTo) return
        repositories?.close()
        generation++
        undoTimer?.cancel()
        undoTimer = null
        // A correction belongs to whoever typed it; anyone else starts clean.
        if (saved.get<String>(KEY_OWNER) != userId) {
            saved.keys().toList().forEach { saved.remove<Any>(it) }
            saved[KEY_OWNER] = userId
        }
        boundTo = userId
        _uiState.value = ReviewUiState()
        repositories = build() ?: return
        load()
    }

    override fun onCleared() {
        // Leaving for good still owes the server the delete it was given.
        flushPendingDelete()
        repositories?.close()
        repositories = null
    }

    // ── Reading the queue ───────────────────────────────────────────────

    /**
     * @param refresh re-reading after an action rather than opening the
     *   screen. The list stays up and keeps its place; only a first load earns
     *   the coin, and a refresh that fails keeps the rows it already had.
     */
    fun load(refresh: Boolean = false) {
        val repos = repositories ?: return
        val started = generation
        _uiState.update {
            it.copy(loading = !refresh, refreshing = refresh, loadFailed = false, errorKey = null)
        }
        viewModelScope.launch {
            try {
                val page = repos.transactions.review()
                val categories = orNull { repos.categories.list() }
                val locale = orNull { repos.capabilities.fetch() }?.locale.orEmpty()
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        rows = page.rows,
                        nextCursor = page.nextCursor,
                        categories = categories ?: it.categories,
                        locale = locale.ifEmpty { it.locale },
                        today = ManualEntry.today(),
                    )
                }
                restoreCorrection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update {
                    // A refresh that fails leaves what is on screen alone and
                    // says so; only a first load has nothing to fall back to.
                    it.copy(
                        loading = false,
                        refreshing = false,
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
        val cursor = _uiState.value.nextCursor ?: return
        if (!_uiState.value.canLoadMore) return
        val started = generation
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repos.transactions.review(cursor)
                if (started != generation) return@launch
                _uiState.update {
                    // Guarded against a row arriving twice: a cursor replayed
                    // after a retry would otherwise list it again.
                    val known = it.rows.mapTo(mutableSetOf()) { row -> row.id }
                    it.copy(
                        loadingMore = false,
                        rows = it.rows + page.rows.filterNot { row -> row.id in known },
                        nextCursor = page.nextCursor,
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

    fun dismissAnnouncements() = _uiState.update { it.copy(announcements = emptyList(), errorKey = null) }

    // ── Confirming ──────────────────────────────────────────────────────

    /**
     * Accept everything that can be accepted. Optimistic: the rows go at once
     * and come back if the request fails, because the common case is twenty
     * right rows and waiting on a spinner for them is the tax this screen
     * exists to avoid.
     */
    fun confirmAll() {
        val repos = repositories ?: return
        val state = _uiState.value
        val rows = state.confirmable
        if (!state.canConfirmAll) return
        val ids = rows.map { it.id }
        val started = generation
        val before = state.rows
        _uiState.update {
            it.copy(confirmingAll = true, rows = it.rows.filterNot { row -> row.id in ids.toSet() }, errorKey = null)
        }
        viewModelScope.launch {
            try {
                val outcome = repos.transactions.confirmAll(ids)
                LedgerChanged.announce()
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        confirmingAll = false,
                        announcements = listOf(ReviewQueue.confirmedMessage(outcome, ids.size)),
                    )
                }
                // The server may have kept rows that still need a category.
                // It said how many, not which, so the queue is re-read.
                if (outcome.confirmed != ids.size) load(refresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // Rolled back visibly: nothing was confirmed, so nothing goes.
                _uiState.update { it.copy(confirmingAll = false, rows = before, errorKey = e.messageKey) }
            }
        }
    }

    /** Accept one row as it is. A row with no category stays, asking for one. */
    fun confirm(id: String) {
        val repos = repositories ?: return
        val row = _uiState.value.rows.firstOrNull { it.id == id } ?: return
        if (_uiState.value.isBusy(id)) return
        val started = generation
        busy(id, true)
        viewModelScope.launch {
            try {
                val outcome = repos.transactions.confirm(id)
                LedgerChanged.announce()
                if (started != generation) return@launch
                applyOutcome(id, outcome.transaction, ReviewQueue.aftermath(outcome, row.merchant))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                failRow(id, e.messageKey)
            }
        }
    }

    // ── Correcting ──────────────────────────────────────────────────────

    fun edit(id: String) {
        val row = _uiState.value.rows.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(editing = row, draft = ReviewQueue.draftOf(row), editErrorKey = null, saving = false)
        }
        storeCorrection()
    }

    fun cancelEdit() {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(editing = null, draft = CorrectionDraft(), editErrorKey = null) }
        clearCorrection()
    }

    /**
     * Deletes the row the editor has open, the way the queue deletes any row:
     * gone from the list at once, with the few seconds' undo the queue gives.
     */
    fun deleteEditing() {
        val id = _uiState.value.editing?.id ?: return
        if (_uiState.value.saving) return
        cancelEdit()
        delete(id)
    }

    fun onDateChange(date: LocalDate) = editDraft { it.copy(occurredOn = date) }

    fun onAmountChange(value: String) = editDraft { it.copy(amount = value) }

    fun onDirectionChange(direction: TransactionDirection) = editDraft { it.copy(direction = direction) }

    fun onDescriptionChange(value: String) = editDraft { it.copy(description = value) }

    fun onCategoryChosen(id: String) = editDraft { it.copy(categoryId = id) }

    private fun editDraft(change: (CorrectionDraft) -> CorrectionDraft) {
        _uiState.update { it.copy(draft = change(it.draft), editErrorKey = null, today = ManualEntry.today()) }
        storeCorrection()
    }

    /**
     * Send the correction. The request comes from [ReviewQueue.correction],
     * which is null whenever Save would be disabled — so this cannot send what
     * the button refused, and never an empty patch.
     */
    fun saveCorrection() {
        val repos = repositories ?: return
        val state = _uiState.value
        val row = state.editing ?: return
        if (state.saving) return
        val patch = ReviewQueue.correction(row, state.draft, state.today) ?: return
        val started = generation
        _uiState.update { it.copy(saving = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                val outcome = repos.transactions.correct(row.id, patch)
                LedgerChanged.announce()
                if (started != generation) return@launch
                _uiState.update { it.copy(saving = false, editing = null, draft = CorrectionDraft()) }
                clearCorrection()
                applyOutcome(row.id, outcome.transaction, ReviewQueue.aftermath(outcome, row.merchant))
                // Other rows took the new category. The server said how many,
                // not which, so the queue is re-read — and only then.
                if (ReviewQueue.mustReload(outcome)) load(refresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(saving = false, editErrorKey = e.messageKey) }
            }
        }
    }

    // ── A category of their own ─────────────────────────────────────────

    fun openNewCategory() = _uiState.update { it.copy(newCategoryName = "", newCategoryErrorKey = null) }

    fun onNewCategoryName(name: String) = _uiState.update { it.copy(newCategoryName = name, newCategoryErrorKey = null) }

    fun cancelNewCategory() {
        if (_uiState.value.creatingCategory) return
        _uiState.update { it.copy(newCategoryName = null, newCategoryErrorKey = null) }
    }

    /** Adds the category and files this row into it — the reason it was added. */
    fun createCategory() {
        val repos = repositories ?: return
        val state = _uiState.value
        val name = state.newCategoryName?.trim().orEmpty()
        if (!state.canCreateCategory) return
        val started = generation
        _uiState.update { it.copy(creatingCategory = true, newCategoryErrorKey = null) }
        viewModelScope.launch {
            try {
                val made = repos.categories.create(name)
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        creatingCategory = false,
                        newCategoryName = null,
                        categories = it.categories + made,
                        draft = it.draft.copy(categoryId = made.id),
                    )
                }
                storeCorrection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.CategoryExists) {
                if (started != generation) return@launch
                // The name is taken by one they can already use, so use it:
                // asking them to think of another name would be busywork.
                _uiState.update {
                    it.copy(
                        creatingCategory = false,
                        newCategoryName = null,
                        draft = it.draft.copy(categoryId = e.categoryId ?: it.draft.categoryId),
                        // Resolved here: `announcements` holds finished text,
                        // and the screen draws it as it is. A key put in raw
                        // reaches the person as `review_category_exists`.
                        announcements = listOf(LocalizationRegistry.get(e.messageKey)),
                    )
                }
                storeCorrection()
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(creatingCategory = false, newCategoryErrorKey = e.messageKey) }
            }
        }
    }

    // ── Deleting, with a window to change their mind ────────────────────

    /**
     * Hide the row and start the undo window. **Nothing is sent yet**: the
     * server has no undo, so the only honest way to offer one is to wait.
     */
    fun delete(id: String) {
        val state = _uiState.value
        val row = state.rows.firstOrNull { it.id == id } ?: return
        // One at a time: deleting another row sends the one already waiting.
        flushPendingDelete()
        _uiState.update {
            it.copy(pendingDelete = PendingDelete(row, it.rows.indexOfFirst { r -> r.id == id }))
        }
        undoTimer = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            sendPendingDelete()
        }
    }

    /** Within the window, nothing was ever sent, so the row simply comes back. */
    fun undoDelete() {
        undoTimer?.cancel()
        undoTimer = null
        _uiState.update { it.copy(pendingDelete = null) }
    }

    /**
     * Leaving the screen still owes the server the delete. Sent now rather
     * than dropped: the person saw the row go, and a row that reappears next
     * time with no explanation is worse than one that goes.
     */
    fun flushPendingDelete() {
        undoTimer?.cancel()
        undoTimer = null
        if (_uiState.value.pendingDelete != null) sendPendingDelete()
    }

    private fun sendPendingDelete() {
        val repos = repositories ?: return
        val pending = _uiState.value.pendingDelete ?: return
        val started = generation
        _uiState.update {
            it.copy(pendingDelete = null, rows = it.rows.filterNot { row -> row.id == pending.row.id })
        }
        viewModelScope.launch {
            try {
                repos.transactions.delete(pending.row.id)
                LedgerChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // It is still on the server, so put it back where it was and
                // say so, rather than leave the list disagreeing with the API.
                _uiState.update {
                    val rows = it.rows.toMutableList()
                    rows.add(pending.at.coerceIn(0, rows.size), pending.row)
                    it.copy(rows = rows, errorKey = e.messageKey)
                }
            }
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────

    /** The row as the server now has it, plus whatever that change did elsewhere. */
    private fun applyOutcome(id: String, updated: Transaction, aftermath: List<String>) {
        _uiState.update { state ->
            val rows = if (updated.needsReview) {
                // Still waiting — now for a different question, most often a
                // category. It stays listed rather than vanishing.
                state.rows.map { if (it.id == id) updated else it }
            } else {
                state.rows.filterNot { it.id == id }
            }
            state.copy(
                rows = rows,
                busyRows = state.busyRows - id,
                rowErrors = state.rowErrors - id,
                announcements = aftermath,
            )
        }
    }

    private fun busy(id: String, busy: Boolean) = _uiState.update {
        it.copy(busyRows = if (busy) it.busyRows + id else it.busyRows - id, rowErrors = it.rowErrors - id)
    }

    private fun failRow(id: String, messageKey: String) = _uiState.update {
        it.copy(busyRows = it.busyRows - id, rowErrors = it.rowErrors + (id to messageKey))
    }

    private suspend fun <T> orNull(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }

    // ── A correction in progress, across process death ──────────────────

    private fun storeCorrection() {
        val state = _uiState.value
        val editing = state.editing
        if (editing == null) return clearCorrection()
        saved[KEY_EDITING] = editing.id
        saved[KEY_DATE] = state.draft.occurredOn?.toString()
        saved[KEY_AMOUNT] = state.draft.amount
        saved[KEY_DIRECTION] = state.draft.direction?.wire
        saved[KEY_DESCRIPTION] = state.draft.description
        saved[KEY_CATEGORY] = state.draft.categoryId
    }

    private fun clearCorrection() = listOf(KEY_EDITING, KEY_DATE, KEY_AMOUNT, KEY_DIRECTION, KEY_DESCRIPTION, KEY_CATEGORY)
        .forEach { saved.remove<Any>(it) }

    /**
     * Put the half-typed correction back, once the queue has loaded and the
     * row it belongs to is known to still be there. A row answered elsewhere
     * in the meantime takes its correction with it.
     */
    private fun restoreCorrection() {
        val id = saved.get<String>(KEY_EDITING) ?: return
        val row = _uiState.value.rows.firstOrNull { it.id == id } ?: return clearCorrection()
        val direction = saved.get<String>(KEY_DIRECTION)
            ?.let(TransactionDirection::fromWire)
            ?.takeIf { it != TransactionDirection.UNKNOWN }
        _uiState.update {
            it.copy(
                editing = row,
                draft = CorrectionDraft(
                    occurredOn = Dates.parse(saved[KEY_DATE]),
                    amount = saved[KEY_AMOUNT] ?: "",
                    direction = direction,
                    description = saved[KEY_DESCRIPTION] ?: "",
                    categoryId = saved[KEY_CATEGORY],
                ),
            )
        }
    }

    internal companion object {
        /** Long enough to notice and reach, short enough not to feel stuck. */
        const val UNDO_WINDOW_MS = 5_000L

        const val KEY_OWNER = "review.owner"
        const val KEY_EDITING = "review.editing"
        const val KEY_DATE = "review.occurred_on"
        const val KEY_AMOUNT = "review.amount"
        const val KEY_DIRECTION = "review.direction"
        const val KEY_DESCRIPTION = "review.description"
        const val KEY_CATEGORY = "review.category_id"
    }
}

/** The clients the queue needs, built together for one signed-in user. */
internal class ReviewRepositories(
    val transactions: TransactionsRepository,
    val categories: CategoriesRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        transactions.close()
        categories.close()
        capabilities.close()
    }
}
