package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.StatementImportSummary
import com.humblesolutions.finai.model.Transaction
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

    // ── Headings by month ───────────────────────────────────────────────

    private fun row(id: String, date: String) = Transaction(id = id, occurredOn = date)

    @Test
    fun rows_are_headed_by_month_newest_first() {
        val groups = TransactionBrowsing.byMonth(
            listOf(row("a", "2026-10-02"), row("b", "2026-08-31"), row("c", "2026-08-22")),
        )

        assertEquals(listOf("2026-10-01", "2026-08-01"), groups.map { it.month })
        assertEquals(listOf("b", "c"), groups.last().rows.map { it.id })
    }

    @Test
    fun a_heading_never_reorders_what_is_under_it() {
        // The server's order within the month, even if it is not by date.
        val groups = TransactionBrowsing.byMonth(listOf(row("x", "2026-08-01"), row("y", "2026-08-30")))
        assertEquals(listOf("x", "y"), groups.single().rows.map { it.id })
    }

    @Test
    fun a_row_with_no_readable_date_is_left_out_of_the_headings() {
        assertEquals(emptyList(), TransactionBrowsing.byMonth(listOf(row("z", ""))))
    }

    // ── Category icons ──────────────────────────────────────────────────

    @Test
    fun seeded_categories_have_their_own_pictures() {
        assertEquals(CategoryIcon.HOME, CategoryIcons.forSlug("rent"))
        assertEquals(CategoryIcon.DINING, CategoryIcons.forSlug("dining"))
        assertEquals(CategoryIcon.TRANSFER, CategoryIcons.forSlug("transfers"))
    }

    @Test
    fun a_category_of_their_own_is_a_tag_and_nothing_filed_says_so() {
        assertEquals(CategoryIcon.TAG, CategoryIcons.forSlug("my-pets"))
        assertEquals(CategoryIcon.UNFILED, CategoryIcons.forSlug(null))
    }
}
