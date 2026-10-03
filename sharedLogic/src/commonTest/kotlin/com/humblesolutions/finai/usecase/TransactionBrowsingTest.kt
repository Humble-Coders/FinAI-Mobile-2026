package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.StatementImportSummary
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionBrowsingTest {

    private fun import(
        id: String = "imp-1",
        createdAt: String = "2026-08-14T09:30:00Z",
        saved: Int = 24,
    ) = StatementImportSummary(id = id, createdAt = createdAt, saved = saved)

    @Test
    fun by_statement_asks_for_that_statement_and_nothing_else() {
        val query = TransactionBrowsing.query(
            TransactionBrowsing.Mode.BY_STATEMENT,
            statementImportId = "imp-7",
            month = LocalDate(2026, 8, 1),
        )

        assertEquals("imp-7", query.statementImportId)
        // Sending both is legal — the server ANDs them — but a month narrowed
        // to a statement shows fewer rows than the header claims.
        assertNull(query.month)
    }

    @Test
    fun by_month_asks_for_that_month_and_nothing_else() {
        val query = TransactionBrowsing.query(
            TransactionBrowsing.Mode.BY_MONTH,
            statementImportId = "imp-7",
            month = LocalDate(2026, 8, 1),
        )

        assertEquals("2026-08", query.month)
        assertNull(query.statementImportId)
    }

    @Test
    fun a_mode_with_nothing_chosen_filters_by_nothing() {
        // Everything, which is the honest answer to "no statement picked yet".
        val statement = TransactionBrowsing.query(TransactionBrowsing.Mode.BY_STATEMENT, null, null)
        assertNull(statement.statementImportId)
        assertNull(statement.month)

        val month = TransactionBrowsing.query(TransactionBrowsing.Mode.BY_MONTH, null, null)
        assertNull(month.month)
    }

    @Test
    fun the_months_offered_come_from_the_statements_that_exist() {
        // Not a rolling twelve: offering a year to somebody with one statement
        // is eleven taps that lead to an empty screen.
        val months = TransactionBrowsing.monthsFrom(
            listOf(
                import(id = "a", createdAt = "2026-08-14T09:30:00Z"),
                import(id = "b", createdAt = "2026-06-02T09:30:00Z"),
            ),
            today = LocalDate(2026, 10, 3),
        )

        assertEquals(
            listOf(LocalDate(2026, 10, 1), LocalDate(2026, 8, 1), LocalDate(2026, 6, 1)),
            months,
        )
    }

    @Test
    fun the_current_month_is_always_offered() {
        // A transaction typed in today belongs to no statement.
        val months = TransactionBrowsing.monthsFrom(emptyList(), today = LocalDate(2026, 10, 3))
        assertEquals(listOf(LocalDate(2026, 10, 1)), months)
    }

    @Test
    fun two_statements_in_one_month_offer_that_month_once() {
        val months = TransactionBrowsing.monthsFrom(
            listOf(
                import(id = "a", createdAt = "2026-08-02T09:30:00Z"),
                import(id = "b", createdAt = "2026-08-27T09:30:00Z"),
            ),
            today = LocalDate(2026, 10, 3),
        )

        assertEquals(listOf(LocalDate(2026, 10, 1), LocalDate(2026, 8, 1)), months)
    }

    @Test
    fun a_statement_imported_this_month_does_not_duplicate_it() {
        val months = TransactionBrowsing.monthsFrom(
            listOf(import(createdAt = "2026-10-01T09:30:00Z")),
            today = LocalDate(2026, 10, 3),
        )

        assertEquals(listOf(LocalDate(2026, 10, 1)), months)
    }

    @Test
    fun an_unreadable_date_is_dropped_rather_than_offered_as_a_month() {
        val months = TransactionBrowsing.monthsFrom(
            listOf(import(createdAt = "not a date")),
            today = LocalDate(2026, 10, 3),
        )

        assertEquals(listOf(LocalDate(2026, 10, 1)), months)
    }

    @Test
    fun a_statement_that_saved_nothing_is_not_offered() {
        // A tap that leads to an empty screen, with no way to tell whether
        // that is the import or the filter.
        val offered = TransactionBrowsing.statementsFrom(
            listOf(import(id = "full", saved = 24), import(id = "empty", saved = 0)),
        )

        assertEquals(listOf("full"), offered.map { it.id })
    }

    @Test
    fun nothing_to_offer_when_no_statement_produced_rows() {
        assertFalse(TransactionBrowsing.hasAnythingToOffer(emptyList()))
        assertFalse(TransactionBrowsing.hasAnythingToOffer(listOf(import(saved = 0))))
        assertTrue(TransactionBrowsing.hasAnythingToOffer(listOf(import(saved = 1))))
    }
}
