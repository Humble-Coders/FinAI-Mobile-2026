package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.StatementImportSummary
import com.humblesolutions.finai.util.Dates
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
    }

    /**
     * The months worth offering, newest first.
     *
     * Taken from the months the statements actually cover rather than a
     * rolling window: offering twelve months to somebody with one statement is
     * eleven taps that lead to an empty screen. The current month is always
     * included, because a transaction typed in today belongs to no statement.
     */
    fun monthsFrom(imports: List<StatementImportSummary>, today: LocalDate): List<LocalDate> {
        val fromStatements = imports.mapNotNull { Dates.parse(it.createdAt.take(10)) }
            .map { DashboardMonths.first(it) }
        return (fromStatements + DashboardMonths.first(today))
            .distinct()
            .sortedDescending()
    }

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
}
