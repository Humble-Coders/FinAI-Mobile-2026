package com.humblesolutions.finai.ui.transactions

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.StatementImportSummary
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.edit.TransactionEditorState
import com.humblesolutions.finai.usecase.CategoryIcon
import com.humblesolutions.finai.usecase.CategoryIcons
import com.humblesolutions.finai.usecase.CorrectionBlock
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.ImportedRows
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ReviewQueue
import com.humblesolutions.finai.usecase.TransactionBrowsing
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * Everything the household has, as the screen reads it (#F3).
 *
 * Rows are grouped and named by the same shared [ImportedRows] the import
 * result screen uses, so one list of transactions looks like another wherever
 * it is shown.
 */
data class TransactionsUiState(
    val mode: TransactionBrowsing.Mode = TransactionBrowsing.Mode.BY_MONTH,
    val statements: List<StatementImportSummary> = emptyList(),
    val months: List<LocalDate> = emptyList(),
    val statementId: String? = null,
    val month: LocalDate = DashboardMonths.current(),

    val rows: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val nextCursor: String? = null,
    val locale: String = "en",

    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val errorKey: String? = null,

    // ── Editing one row ─────────────────────────────────────────────────
    val editing: Transaction? = null,
    val draft: CorrectionDraft = CorrectionDraft(),
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val editErrorKey: String? = null,
    val today: LocalDate = ManualEntry.today(),
    val newCategoryName: String? = null,
    val creatingCategory: Boolean = false,
    val newCategoryErrorKey: String? = null,
) {
    private val currency: String get() = rows.firstOrNull()?.currency.orEmpty()

    private val digits: Int get() = Money.fractionDigits(currency)

    private fun text(key: String, vararg args: String): String = if (args.isEmpty()) {
        LocalizationRegistry.get(key, locale)
    } else {
        LocalizationRegistry.format(key, args.toList(), locale)
    }

    /**
     * The rows to draw. By month, only the month being looked at's — whatever
     * the server sent; see [TransactionBrowsing.inMonth].
     */
    val visibleRows: List<Transaction>
        get() = if (mode == TransactionBrowsing.Mode.BY_MONTH) TransactionBrowsing.inMonth(rows, month) else rows

    /** The days in view, newest first — the same arrangement as the import result. */
    val days: List<ImportedRows.Day> get() = ImportedRows.byDate(visibleRows)

    val totalOut: String? get() = ImportedRows.totalOut(visibleRows, digits)?.let { money(it) }

    val totalIn: String? get() = ImportedRows.totalIn(visibleRows, digits)?.let { money(it) }

    private fun money(amount: String): String = Money.format(amount, currency, locale)

    fun amountLabel(row: Transaction): String = Money.format(row.amount, row.currency, locale)

    fun dateLabel(iso: String): String = Dates.parse(iso)?.let { Dates.display(it) } ?: iso

    fun titleOf(row: Transaction): String = ImportedRows.titleOf(row)

    fun categoryLabel(row: Transaction): String = ImportedRows.categoryOf(row, categories)?.name ?: text(Strings.import_extracted_uncategorised)

    fun isFiled(row: Transaction): Boolean = ImportedRows.categoryOf(row, categories) != null

    /** One row as a sentence, so a screen reader hears it once. */
    fun rowDescription(row: Transaction): String = text(Strings.import_extracted_row, titleOf(row), categoryLabel(row), amountLabel(row))

    /** `Aug 2026`, for a month in the picker. */
    fun monthLabel(month: LocalDate): String = text(Strings.dashboard_month_display, Dates.monthShort(month, locale), month.year.toString())

    /**
     * A statement as a thing to pick: when it was imported, how many rows, and
     * how many still want a person — which is the one that decides whether to
     * open it.
     */
    fun statementLabel(statement: StatementImportSummary): String {
        val on = Dates.parse(statement.createdAt.take(10))?.let { Dates.display(it) }
            ?: statement.createdAt.take(10)
        return if (statement.needsReview > 0) {
            text(
                Strings.transactions_statement_waiting,
                on,
                statement.saved.toString(),
                statement.needsReview.toString(),
            )
        } else {
            text(Strings.transactions_statement_option, on, statement.saved.toString())
        }
    }

    /** The rows under a heading per month, newest first. */
    val monthGroups: List<TransactionBrowsing.MonthGroup> get() = TransactionBrowsing.byMonth(visibleRows)

    val browsingByCategory: Boolean get() = mode == TransactionBrowsing.Mode.BY_CATEGORY

    /** Every row under its category, most money first, unfiled last. */
    val categoryGroups: List<TransactionBrowsing.CategoryGroup>
        get() = TransactionBrowsing.byCategory(visibleRows, categories, digits)

    /** A category heading's name: the category's, or "Not filed yet". */
    fun categoryHeading(group: TransactionBrowsing.CategoryGroup): String = categories.firstOrNull { it.id == group.categoryId }?.name ?: text(Strings.import_extracted_uncategorised)

    /**
     * The list in sections, whichever way it is sliced: by category, one per
     * category with its total; otherwise one per month.
     */
    val sections: List<Section>
        get() = if (browsingByCategory) {
            categoryGroups.map { Section(categoryHeading(it), categoryTotal(it), it.headlineIsIn, it.rows) }
        } else {
            monthGroups.map { Section(monthHeading(it.month), null, false, it.rows) }
        }

    /** A category heading's figure, in the list's currency. */
    fun categoryTotal(group: TransactionBrowsing.CategoryGroup): String = money(group.headline)

    /** The picture for a category heading. */
    fun categoryIcon(group: TransactionBrowsing.CategoryGroup): CategoryIcon = CategoryIcons.forSlug(categories.firstOrNull { it.id == group.categoryId }?.slug)

    /** "Aug 2026", for a heading. */
    fun monthHeading(month: String): String = Dates.parse(month)?.let { monthLabel(it) } ?: month

    /** The picture for a row: its category's, or the unfiled mark. */
    fun iconFor(row: Transaction): CategoryIcon = CategoryIcons.forSlug(ImportedRows.categoryOf(row, categories)?.slug)

    fun isCredit(row: Transaction): Boolean = row.direction == TransactionDirection.CREDIT

    /** A statement's chip: just when it was imported. The sheet says the rest. */
    fun statementChip(statement: StatementImportSummary): String = Dates.parse(statement.createdAt.take(10))?.let { Dates.display(it) } ?: statement.createdAt.take(10)

    val browsingByStatement: Boolean get() = mode == TransactionBrowsing.Mode.BY_STATEMENT

    /** True once the server has answered and the slice holds nothing. */
    val showsEmpty: Boolean get() = !loading && !loadFailed && visibleRows.isEmpty()

    /**
     * What an empty slice says. By statement it names the statement; by month
     * the month — "nothing here" alone leaves somebody unsure whether the
     * filter or the data is at fault.
     */
    val emptyMessage: String get() = when {
        browsingByStatement && statements.isEmpty() -> text(Strings.transactions_no_statements)
        browsingByStatement -> text(Strings.transactions_empty_statement)
        browsingByCategory -> text(Strings.transactions_empty_category)
        else -> text(Strings.transactions_empty_month)
    }

    val canLoadMore: Boolean get() = nextCursor != null && !loadingMore && !loading

    /** Read aloud for a row, which opens the editor when tapped. */
    fun editLabel(row: Transaction): String = text(Strings.transactions_edit_hint, titleOf(row))

    /**
     * The editor sheet's view of the edit in progress. The same sheet and the
     * same shared rules as the review queue's: one ledger, one way to fix it.
     */
    val editor: TransactionEditorState?
        get() = editing?.let { row ->
            val block = ReviewQueue.blockingReason(row, draft, today)
            TransactionEditorState(
                row = row,
                draft = draft,
                // "Nothing has changed" is not worth saying before a touch.
                notice = block?.takeIf { it != CorrectionBlock.NOTHING_CHANGED },
                errorKey = editErrorKey,
                canSave = !saving && block == null,
                saving = saving,
                categoryName = ReviewQueue.categoryName(categories, draft.categoryId),
                pickableCategories = categories
                    .map { it to ReviewQueue.categoryName(it) }
                    .sortedWith(compareBy({ it.first.isSystem }, { it.second.lowercase() })),
                today = today,
                newCategoryName = newCategoryName,
                newCategoryErrorKey = newCategoryErrorKey,
                canCreateCategory = !creatingCategory && !newCategoryName.isNullOrBlank(),
                creatingCategory = creatingCategory,
                titleKey = Strings.transactions_edit_title,
                deleting = deleting,
                deleteSummary = ImportedRows.summary(row, locale),
            )
        }
}

/** One heading of the list and the rows under it. */
data class Section(
    val title: String,
    /** A category's total; null under a month heading. */
    val total: String?,
    /** The total is money in, drawn green. */
    val totalIsIn: Boolean,
    val rows: List<Transaction>,
)
