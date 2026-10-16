package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ParsedRow
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.TransactionDirection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportCheckTest {

    private val today = LocalDate(2026, 10, 14)

    private val parsed = ParsedStatement(
        importId = "imp-1",
        currency = "CAD",
        rows = listOf(
            ParsedRow("2026-09-02", "LOBLAWS 1042", "86.40", "debit", 95),
            ParsedRow("2026-09-05", "PAYROLL ACME", "2500.00", "credit", 98),
            ParsedRow("2026-09-09", "TIM HORT0NS", "5.25", "debit", 52),
        ),
    )

    private val rows = ImportCheck.rowsOf(parsed, accountId = "acc-1")

    private fun draft(of: CheckRow, amount: String? = null, description: String? = null) = ReviewQueue.draftOf(of.row).let {
        it.copy(amount = amount ?: it.amount, description = description ?: it.description)
    }

    @Test
    fun every_parsed_row_is_offered_for_checking_in_the_order_read() {
        assertEquals(listOf("check-0", "check-1", "check-2"), rows.map { it.row.id })
        assertEquals(listOf("acc-1"), rows.map { it.row.accountId }.distinct())
        assertEquals(listOf("CAD"), rows.map { it.row.currency }.distinct())
        assertEquals(TransactionDirection.CREDIT, rows[1].row.direction)
        assertNull(rows[0].row.categoryId, "the server files rows when they are saved")
        assertEquals(listOf(false, false, false), rows.map { it.edited })
    }

    @Test
    fun only_rows_read_with_doubt_ask_for_a_look() {
        assertEquals(listOf(false, false, true), rows.map(ImportCheck::needsCheck))
        assertEquals(1, ImportCheck.doubtCount(rows))
    }

    @Test
    fun the_line_is_the_servers_review_line() {
        val at = ImportCheck.rowsOf(
            parsed.copy(rows = listOf(ParsedRow("2026-09-02", "A", "1.00", "debit", ImportCheck.CHECK_BELOW))),
            "acc-1",
        )
        val under = ImportCheck.rowsOf(
            parsed.copy(rows = listOf(ParsedRow("2026-09-02", "A", "1.00", "debit", ImportCheck.CHECK_BELOW - 1))),
            "acc-1",
        )
        assertFalse(ImportCheck.needsCheck(at.single()))
        assertTrue(ImportCheck.needsCheck(under.single()))
    }

    @Test
    fun an_edit_changes_the_row_marks_it_and_stops_asking_for_a_look() {
        val edited = assertNotNull(
            ImportCheck.edit(rows, "check-2", draft(rows[2], amount = "5.5", description = " TIM HORTONS "), today),
        )

        val row = edited[2]
        assertTrue(row.edited)
        assertEquals("5.50", row.row.amount, "kept in the form the server expects")
        assertEquals("TIM HORTONS", row.row.description)
        assertFalse(ImportCheck.needsCheck(row))
        assertEquals(rows.take(2), edited.take(2), "the others are untouched")
    }

    @Test
    fun an_edit_the_editor_would_refuse_is_refused_here_too() {
        assertNull(ImportCheck.edit(rows, "check-0", draft(rows[0], amount = "0"), today))
        assertNull(ImportCheck.edit(rows, "check-0", draft(rows[0], description = "  "), today))
        assertNull(ImportCheck.edit(rows, "check-0", draft(rows[0]), today), "nothing changed")
        assertNull(
            ImportCheck.edit(rows, "check-0", draft(rows[0]).copy(occurredOn = LocalDate(2026, 10, 15)), today),
            "a future date",
        )
        assertNull(ImportCheck.edit(rows, "nope", draft(rows[0], amount = "1.00"), today))
    }

    @Test
    fun a_removed_row_is_not_saved_and_an_empty_import_cannot_be() {
        val left = ImportCheck.remove(rows, "check-1")
        assertEquals(listOf("check-0", "check-2"), left.map { it.row.id })
        assertTrue(ImportCheck.canSave(left))

        val none = rows.fold(rows) { acc, r -> ImportCheck.remove(acc, r.row.id) }
        assertFalse(ImportCheck.canSave(none))
    }

    @Test
    fun saving_sends_the_rows_as_they_stand_and_edited_ones_as_confirmed() {
        val edited = assertNotNull(ImportCheck.edit(rows, "check-2", draft(rows[2], description = "TIM HORTONS"), today))
        val request = ImportCheck.rowsToSave("acc-1", ImportCheck.remove(edited, "check-1"))

        assertEquals("acc-1", request.accountId)
        assertEquals(listOf("LOBLAWS 1042", "TIM HORTONS"), request.rows.map { it.description })
        assertEquals(listOf(95, ImportCheck.CONFIRMED), request.rows.map { it.confidence })
        assertEquals(listOf("debit", "debit"), request.rows.map { it.direction })
        assertEquals(listOf("86.40", "5.25"), request.rows.map { it.amount })
    }

    @Test
    fun the_count_and_the_button_say_how_many() {
        assertEquals("3 transactions", ImportCheck.countLabel(rows))
        assertEquals("Save 3 transactions", ImportCheck.saveLabel(rows))
        assertEquals("1 transaction", ImportCheck.countLabel(rows.take(1)))
        assertEquals("Save 1 transaction", ImportCheck.saveLabel(rows.take(1)))
    }

    // ── Categories before saving ────────────────────────────────────────

    private val dining = Category(id = "c-dining", slug = "dining", name = "Dining out")
    private val groceries = Category(id = "c-groceries", slug = "groceries", name = "Groceries")
    private val income = Category(id = "c-income", slug = "income", name = "Income")
    private val transfers = Category(id = "c-transfers", slug = "transfers", name = "Transfers")
    private val categories = listOf(dining, groceries, income, transfers)

    private val suggested = ImportCheck.rowsOf(
        ParsedStatement(
            importId = "imp-2",
            currency = "CAD",
            rows = listOf(
                ParsedRow("2026-09-02", "LOBLAWS 1042", "86.40", "debit", 95, categoryId = "c-groceries"),
                ParsedRow("2026-09-03", "SAFEWAY", "13.60", "debit", 95, categoryId = "c-groceries"),
                ParsedRow("2026-09-04", "TIM HORTONS", "5.25", "debit", 95, categoryId = "c-dining"),
                ParsedRow("2026-09-05", "PAYROLL ACME", "2500.00", "credit", 98, categoryId = "c-income"),
                ParsedRow("2026-09-06", "VISA PAYMENT", "400.00", "debit", 95, categoryId = "c-transfers"),
                ParsedRow("2026-09-07", "E-TRANSFER", "50.00", "debit", 90),
            ),
        ),
        accountId = "acc-1",
    )

    @Test
    fun a_suggested_category_is_on_the_row_and_remembered_as_suggested() {
        assertEquals("c-groceries", suggested[0].row.categoryId)
        assertEquals("c-groceries", suggested[0].suggestedCategoryId)
        assertNull(suggested[5].row.categoryId, "no suggestion: no category yet")
    }

    @Test
    fun changing_only_the_category_is_an_edit_and_is_sent_with_the_suggestion() {
        val edited = assertNotNull(
            ImportCheck.edit(suggested, "check-2", ReviewQueue.draftOf(suggested[2].row).copy(categoryId = "c-groceries"), today),
        )
        val request = ImportCheck.rowsToSave("acc-1", edited)

        assertTrue(request.categorised)
        assertEquals("c-groceries", request.rows[2].categoryId)
        assertEquals("c-dining", request.rows[2].suggestedCategoryId, "so the server learns the correction")
        assertEquals(request.rows[0].categoryId, request.rows[0].suggestedCategoryId, "accepted as suggested")
    }

    @Test
    fun the_breakdown_counts_money_in_and_out_by_category_largest_first() {
        val breakdown = ImportCheck.breakdown(suggested, categories)

        assertEquals(
            listOf(Slice("c-groceries", "Groceries", "100.00"), Slice(null, null, "50.00"), Slice("c-dining", "Dining out", "5.25")),
            breakdown.moneyOut,
        )
        assertEquals(listOf(Slice("c-income", "Income", "2500.00")), breakdown.moneyIn)
        assertEquals("155.25", breakdown.totalOut, "the card payment is not spending")
        assertEquals("2500.00", breakdown.totalIn)
        assertEquals("400.00", breakdown.betweenAccounts)
    }

    @Test
    fun the_breakdown_follows_a_changed_category() {
        val moved = assertNotNull(
            ImportCheck.edit(suggested, "check-4", ReviewQueue.draftOf(suggested[4].row).copy(categoryId = "c-groceries"), today),
        )

        val breakdown = ImportCheck.breakdown(moved, categories)

        assertNull(breakdown.betweenAccounts)
        assertEquals("555.25", breakdown.totalOut)
    }

    @Test
    fun a_category_whose_name_has_not_loaded_still_has_its_own_slice() {
        val breakdown = ImportCheck.breakdown(suggested, categories = emptyList())

        // Without the names nothing is known to be a transfer, so the card
        // payment counts as money out — which is why the screens show the
        // breakdown only once the categories have loaded.
        assertEquals(listOf("c-transfers", "c-groceries", null, "c-dining"), breakdown.moneyOut.map { it.categoryId })
        assertEquals(listOf<String?>(null, null, null, null), breakdown.moneyOut.map { it.name })
        assertNull(breakdown.betweenAccounts)
    }
}
