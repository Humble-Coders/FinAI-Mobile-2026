package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedRowsTest {

    private fun row(
        id: String = "t1",
        date: String = "2026-08-02",
        amount: String = "10.00",
        debit: Boolean = true,
        merchant: String? = "LOBLAWS",
        description: String? = "POS PURCHASE LOBLAWS",
        categoryId: String? = "c1",
        needsReview: Boolean = false,
    ) = Transaction(
        id = id,
        occurredOn = date,
        amount = amount,
        currency = "CAD",
        direction = if (debit) TransactionDirection.DEBIT else TransactionDirection.CREDIT,
        merchant = merchant,
        description = description,
        categoryId = categoryId,
        needsReview = needsReview,
    )

    @Test
    fun rows_are_grouped_by_the_day_on_the_statement() {
        val days = ImportedRows.byDate(
            listOf(
                row(id = "a", date = "2026-08-02"),
                row(id = "b", date = "2026-08-05"),
                row(id = "c", date = "2026-08-02"),
            ),
        )

        assertEquals(listOf("2026-08-05", "2026-08-02"), days.map { it.date })
        assertEquals(listOf("a", "c"), days.last().rows.map { it.id })
    }

    @Test
    fun the_newest_day_comes_first() {
        val days = ImportedRows.byDate(listOf(row(date = "2026-07-31"), row(date = "2026-08-01")))
        assertEquals("2026-08-01", days.first().date)
    }

    @Test
    fun the_order_within_a_day_is_the_order_it_was_read() {
        // The statement's own order. Re-sorting inside a day would stop the
        // list matching the page the person is holding.
        val days = ImportedRows.byDate(
            listOf(row(id = "first", amount = "99.00"), row(id = "second", amount = "1.00")),
        )
        assertEquals(listOf("first", "second"), days.single().rows.map { it.id })
    }

    @Test
    fun nothing_grouped_is_no_days_rather_than_one_empty_one() {
        assertTrue(ImportedRows.byDate(emptyList()).isEmpty())
    }

    @Test
    fun money_out_counts_only_what_left() {
        val rows = listOf(
            row(amount = "10.00", debit = true),
            row(amount = "5.50", debit = true),
            row(amount = "900.00", debit = false),
        )
        assertEquals("15.50", ImportedRows.totalOut(rows))
    }

    @Test
    fun money_in_counts_only_what_arrived() {
        val rows = listOf(
            row(amount = "10.00", debit = true),
            row(amount = "900.00", debit = false),
        )
        assertEquals("900.00", ImportedRows.totalIn(rows))
    }

    @Test
    fun a_direction_with_nothing_in_it_has_no_total_rather_than_zero() {
        // Zero would read as "nothing came in", which is a claim. Null lets
        // the screen leave the line out.
        val rows = listOf(row(amount = "10.00", debit = true))
        assertNull(ImportedRows.totalIn(rows))
        assertEquals("10.00", ImportedRows.totalOut(rows))
    }

    @Test
    fun the_waiting_count_is_the_rows_a_person_still_has_to_look_at() {
        val rows = listOf(
            row(id = "a", needsReview = true),
            row(id = "b", needsReview = false),
            row(id = "c", needsReview = true),
        )
        assertEquals(2, ImportedRows.waitingCount(rows))
    }

    @Test
    fun a_row_is_named_by_its_merchant_when_there_is_one() {
        assertEquals("LOBLAWS", ImportedRows.titleOf(row()))
    }

    @Test
    fun a_row_with_no_merchant_falls_back_to_its_description() {
        assertEquals(
            "PRE-AUTH DEBIT HYDRO",
            ImportedRows.titleOf(row(merchant = null, description = "PRE-AUTH DEBIT HYDRO")),
        )
        assertEquals(
            "PRE-AUTH DEBIT HYDRO",
            ImportedRows.titleOf(row(merchant = "  ", description = "PRE-AUTH DEBIT HYDRO")),
        )
    }

    @Test
    fun a_row_with_neither_is_empty_rather_than_null() {
        assertEquals("", ImportedRows.titleOf(row(merchant = null, description = null)))
    }

    @Test
    fun a_category_is_resolved_by_id() {
        val groceries = Category(id = "c1", name = "Groceries")
        assertEquals(groceries, ImportedRows.categoryOf(row(categoryId = "c1"), listOf(groceries)))
    }

    @Test
    fun a_row_nothing_filed_has_no_category() {
        // The most useful row on the screen: the one needing a person.
        assertNull(ImportedRows.categoryOf(row(categoryId = null), listOf(Category(id = "c1"))))
    }

    @Test
    fun a_category_the_client_does_not_know_resolves_to_nothing_rather_than_crashing() {
        assertNull(ImportedRows.categoryOf(row(categoryId = "gone"), listOf(Category(id = "c1"))))
    }

    @Test
    fun a_filed_row_is_never_called_unfiled_because_the_names_did_not_load() {
        val filed = Transaction(id = "a", categoryId = "groceries-id")
        val unfiled = Transaction(id = "b")
        val groceries = Category(id = "groceries-id", name = "Groceries")

        assertEquals("Groceries", ImportedRows.categoryLabel(filed, listOf(groceries), "No category yet"))
        assertNull(ImportedRows.categoryLabel(filed, emptyList(), "No category yet"), "names missing: say nothing")
        assertEquals("No category yet", ImportedRows.categoryLabel(unfiled, emptyList(), "No category yet"))
        assertTrue(ImportedRows.isFiled(filed))
        assertTrue(!ImportedRows.isFiled(unfiled))
    }
}
