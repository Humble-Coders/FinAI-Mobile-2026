package com.humblesolutions.finai.ui.review

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.ui.edit.TransactionEditorState
import com.humblesolutions.finai.usecase.CorrectionBlock
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.usecase.ImportedRows
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ReviewQueue
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** A row the person deleted, held back until the undo window closes. */
data class PendingDelete(val row: Transaction, val at: Int)

/**
 * Everything the review screen shows (#32).
 *
 * The gates are computed here from the shared rules, never decided in a
 * composable, so a test asserts them with a plain constructor (kmp-arch-v2).
 */
data class ReviewUiState(
    val rows: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** The language figures are written in, from capabilities. Blank formats as English. */
    val locale: String = "",
    val today: LocalDate = ManualEntry.today(),
    /** The first load, while the app's coin loader covers the screen. */
    val loading: Boolean = true,
    /** Another page is on its way; the list shows a footer, not the coin. */
    val loadingMore: Boolean = false,
    /**
     * The queue is being re-read after an action, not opened. The list stays
     * on screen: a refresh that blanks the screen and shows the coin reads as
     * the app starting over.
     */
    val refreshing: Boolean = false,
    val nextCursor: String? = null,
    val loadFailed: Boolean = false,
    val errorKey: String? = null,
    /** A bulk confirm is in flight: the button waits, the rows do not. */
    val confirmingAll: Boolean = false,
    /** Rows with a request of their own in flight, by id. */
    val busyRows: Set<String> = emptySet(),
    /** What went wrong on one row, by id — shown on the row, not over the list. */
    val rowErrors: Map<String, String> = emptyMap(),
    /**
     * What the last action came to: what was confirmed, what a correction
     * moved. **Said once** — each action replaces the last, rather than
     * stacking lines above the button for the length of the queue.
     *
     * Finished text, never a string key: the screen draws these as they are.
     */
    val announcements: List<String> = emptyList(),
    /** The row being corrected, with the draft as far as the person has got. */
    val editing: Transaction? = null,
    val draft: CorrectionDraft = CorrectionDraft(),
    val saving: Boolean = false,
    val editErrorKey: String? = null,
    /** A new category being added from the picker. */
    val newCategoryName: String? = null,
    val creatingCategory: Boolean = false,
    val newCategoryErrorKey: String? = null,
    /** Deleted, waiting out its undo window. Nothing has been sent yet. */
    val pendingDelete: PendingDelete? = null,
) {
    /** What the list shows: the queue, minus anything waiting out an undo. */
    val visibleRows: List<Transaction>
        get() = pendingDelete?.let { pending -> rows.filterNot { it.id == pending.row.id } } ?: rows

    /** Grouped by the date on the statement, newest first — the order the queue comes in. */
    val byDate: List<Pair<String, List<Transaction>>>
        get() = visibleRows.groupBy { it.occurredOn }.toList()

    /** The rows Confirm all would actually clear. */
    val confirmable: List<Transaction> get() = ReviewQueue.confirmable(visibleRows)

    val confirmAllLabel: String get() = ReviewQueue.confirmAllLabel(visibleRows)

    val canConfirmAll: Boolean get() = !confirmingAll && confirmable.isNotEmpty()

    val isEmpty: Boolean get() = !loading && !loadFailed && visibleRows.isEmpty()

    val canLoadMore: Boolean get() = nextCursor != null && !loadingMore && !loading

    fun isBusy(id: String): Boolean = id in busyRows

    fun errorFor(id: String): String? = rowErrors[id]

    fun categoryNameFor(row: Transaction): String? = ReviewQueue.categoryName(categories, row.categoryId)

    fun reasonKeyFor(row: Transaction): String = ReviewQueue.reasonKey(row)

    fun duplicateTextFor(row: Transaction): String? = row.duplicateOf?.let { ReviewQueue.duplicateOf(it, row.currency, locale) }

    /** The amount as a person reads it, through the shared formatter. */
    fun amountFor(row: Transaction): String = Money.format(row.amount, row.currency, locale)

    // ── The correction sheet ────────────────────────────────────────────

    val editBlock: CorrectionBlock?
        get() = editing?.let { ReviewQueue.blockingReason(it, draft, today) }

    val canSaveCorrection: Boolean get() = editing != null && !saving && editBlock == null

    /**
     * The notice under Save. "Nothing has changed yet" is not worth saying
     * before the person has touched anything — the sheet has only just opened.
     */
    val editNotice: CorrectionBlock?
        get() = editBlock?.takeIf { it != CorrectionBlock.NOTHING_CHANGED }

    val editCategoryName: String? get() = ReviewQueue.categoryName(categories, draft.categoryId)

    /** The categories to choose from: the household's own first, then the shared. */
    val pickableCategories: List<Pair<Category, String>>
        get() = categories
            .map { it to ReviewQueue.categoryName(it) }
            .sortedWith(compareBy({ it.first.isSystem }, { it.second.lowercase() }))

    val canCreateCategory: Boolean
        get() = !creatingCategory && !newCategoryName.isNullOrBlank()

    /** The editor sheet's view of the correction in progress, or null when none is. */
    val editor: TransactionEditorState?
        get() = editing?.let { row ->
            TransactionEditorState(
                row = row,
                draft = draft,
                notice = editNotice,
                errorKey = editErrorKey,
                canSave = canSaveCorrection,
                saving = saving,
                categoryName = editCategoryName,
                pickableCategories = pickableCategories,
                today = today,
                newCategoryName = newCategoryName,
                newCategoryErrorKey = newCategoryErrorKey,
                canCreateCategory = canCreateCategory,
                creatingCategory = creatingCategory,
                deleteSummary = ImportedRows.summary(row, locale),
            )
        }
}
