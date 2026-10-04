package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.StatementImportSummary
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * Browsing everything the household has, by statement or by month (#F3).
 *
 * Two ways of asking the same question, because people hold two different
 * things in mind: a statement they are checking against the paper, and a month
 * they are reasoning about. The server answers both through one endpoint; this
 * decides what each mode asks for.
 *
 * Shared because which filter a mode sends is a decision, and two apps sending
 * different filters for the same button is the kind of difference nobody finds
 * until someone compares two phones.
 */
object TransactionBrowsing {

    /** How the list is being sliced. */
    enum class Mode {
        /** One statement at a time, as imported. */
        BY_STATEMENT,

        /** One calendar month at a time, whatever it came from. */
        BY_MONTH,

        /** Everything, under a heading per category. */
        BY_CATEGORY,
    }

    /**
     * What a request should carry for [mode].
     *
     * Exactly one filter is ever set. Sending both would be legal — the server
     * ANDs them — but it is not what either mode means, and a screen that
     * quietly narrowed a month to a statement would show fewer rows than its
     * own header claims.
     */
    data class Query(
        val statementImportId: String? = null,
        val month: String? = null,
    )

    fun query(mode: Mode, statementImportId: String?, month: LocalDate?): Query = when (mode) {
        Mode.BY_STATEMENT -> Query(statementImportId = statementImportId)

        Mode.BY_MONTH -> Query(month = month?.let { DashboardMonths.wire(it) })

        // Every row: grouping by category is done here, over all of them.
        Mode.BY_CATEGORY -> Query()
    }

    /**
     * Only the rows of [month] — the month being looked at — and none from
     * another, whatever the server sent.
     *
     * The server filters by month too, once it has the filter (Finance-backend
     * #53). A server without it ignores the parameter and returns every
     * month, which drew the whole ledger under a single month's chip; this is
     * what keeps the list honest either way.
     */
    fun inMonth(rows: List<Transaction>, month: LocalDate): List<Transaction> {
        val prefix = DashboardMonths.wire(month)
        return rows.filter { it.occurredOn.startsWith(prefix) }
    }

    /** One category's rows, with what went out and came in under it. */
    data class CategoryGroup(
        /** Null for the rows nothing has filed yet. */
        val categoryId: String?,
        val rows: List<Transaction>,
        /** Money out, as a decimal string; "0" when none. */
        val totalOut: String,
        /** Money in, as a decimal string; "0" when none. */
        val totalIn: String,
    ) {
        /** Money out when there is any — what a category is mostly asked about — else money in. */
        val headline: String get() = if (Money.signOf(totalOut) != 0) totalOut else totalIn
        val headlineIsIn: Boolean get() = Money.signOf(totalOut) == 0 && Money.signOf(totalIn) != 0
    }

    /**
     * Every row under its category: spending categories first, the most spent
     * first; then categories of money in; then the rows nothing filed.
     *
     * Last rather than first: they are the ones still needing a person, but
     * the review queue is where that is done, and here they would push the
     * answer to "where did it go" below the fold. A category the household no
     * longer has is grouped as unfiled rather than dropped.
     */
    fun byCategory(rows: List<Transaction>, categories: List<Category>, fractionDigits: Int = 2): List<CategoryGroup> {
        val known = categories.map { it.id }.toSet()
        val grouped = rows.groupBy { row -> row.categoryId?.takeIf { it in known } }
        val groups = grouped.map { (id, inCategory) ->
            CategoryGroup(
                categoryId = id,
                rows = inCategory,
                totalOut = ImportedRows.totalOut(inCategory, fractionDigits) ?: "0",
                totalIn = ImportedRows.totalIn(inCategory, fractionDigits) ?: "0",
            )
        }
        val (filed, unfiled) = groups.partition { it.categoryId != null }
        val (incoming, outgoing) = filed.partition { it.headlineIsIn }
        val largestFirst = Comparator<CategoryGroup> { a, b -> Money.compare(b.headline, a.headline, fractionDigits) }
        // Spending first, which is what a category is mostly asked about;
        // income after it, so a salary does not top a list of where it went.
        return outgoing.sortedWith(largestFirst) + incoming.sortedWith(largestFirst) + unfiled
    }

    /**
     * The months worth offering, newest first: every month a transaction falls
     * in, by the date on the statement, and the current month.
     *
     * From the transactions themselves, not from when statements were
     * imported. An August statement imported in October has nothing to do
     * with October, and offering months by import date left a person whose
     * only import was this month with one chip — the current month, empty —
     * and no way to reach August at all. The current month is always there,
     * because a transaction typed in today belongs to it.
     */
    fun monthsWithRows(rows: List<Transaction>, today: LocalDate): List<LocalDate> = (monthsOf(rows) + DashboardMonths.first(today)).distinct().sortedDescending()

    /**
     * The month to open on: the newest one with anything in it, or the current
     * month when there is nothing at all. Opening on an empty current month
     * when last month is full reads as the import having gone missing.
     */
    fun startingMonth(rows: List<Transaction>, today: LocalDate): LocalDate = monthsOf(rows).maxOrNull() ?: DashboardMonths.first(today)

    /**
     * The month to show once the ledger has changed.
     *
     * Where the change landed, when it added rows: an import of October while
     * August is on screen opens October, because staying on August reads as
     * the import having gone missing — the new rows exist, behind a chip
     * nobody tapped. When nothing was added — a row corrected or deleted —
     * the month being looked at stays, so an edit never throws somebody to
     * another month.
     *
     * @param knownIds the rows seen before the change. Empty on a first load,
     *   or when there were none: then it is simply [startingMonth].
     */
    fun monthAfterChange(current: LocalDate?, knownIds: Set<String>, rows: List<Transaction>, today: LocalDate): LocalDate {
        if (current == null || knownIds.isEmpty()) return startingMonth(rows, today)
        return monthsOf(rows.filter { it.id !in knownIds }).maxOrNull() ?: current
    }

    /**
     * The statement to show once the list of them may have changed: one that
     * was not there before (the import just made), else the one on screen
     * while it is still offered, else the newest.
     */
    fun statementAfterChange(
        current: String?,
        before: List<StatementImportSummary>,
        imports: List<StatementImportSummary>,
    ): String? {
        val offered = statementsFrom(imports)
        val had = before.map { it.id }.toSet()
        return offered.firstOrNull { it.id !in had }?.id
            ?: current?.takeIf { id -> offered.any { it.id == id } }
            ?: offered.firstOrNull()?.id
    }

    private fun monthsOf(rows: List<Transaction>): List<LocalDate> = rows.mapNotNull { row -> Dates.parse(row.occurredOn)?.let { DashboardMonths.first(it) } }

    /**
     * The statements worth offering: the ones that produced rows.
     *
     * An import that saved nothing has nothing to show, and offering it is a
     * tap that leads to an empty screen with no way to tell whether that is
     * the import or the filter.
     */
    fun statementsFrom(imports: List<StatementImportSummary>): List<StatementImportSummary> = imports.filter { it.hasRows }

    /**
     * Whether there is anything to browse at all.
     *
     * A household with no statements can still have typed transactions in, so
     * this is not "no statements" — the month list always holds the current
     * month, and that is the one worth opening.
     */
    fun hasAnythingToOffer(imports: List<StatementImportSummary>): Boolean = statementsFrom(imports).isNotEmpty()

    /** One calendar month of the list, with its rows in the server's order. */
    data class MonthGroup(
        /** The first of the month, `YYYY-MM-01`, for a heading like "Aug 2026". */
        val month: String,
        val rows: List<Transaction>,
    )

    /**
     * The rows under a heading per month, newest month first.
     *
     * By the month on the statement, as browsing by month means. Within a
     * month the server's order is kept — newest first — so a heading never
     * reorders what is under it. A statement that spans two months shows two
     * headings, which is the point: it says where the month boundary fell.
     */
    fun byMonth(rows: List<Transaction>): List<MonthGroup> = rows
        .groupBy { it.occurredOn.take(7) }
        .filterKeys { it.length == 7 }
        .map { (month, inMonth) -> MonthGroup(month = "$month-01", rows = inMonth) }
        .sortedByDescending { it.month }
}
