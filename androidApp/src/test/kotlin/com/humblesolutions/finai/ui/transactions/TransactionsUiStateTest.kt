package com.humblesolutions.finai.ui.transactions

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.StatementImportSummary
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.usecase.CategoryIcon
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the transactions screen draws from its state, with plain constructors. */
class TransactionsUiStateTest {

    private val rent = Category(id = "c1", slug = "rent", name = "Rent & mortgage")

    @Test
    fun a_row_is_drawn_with_its_category_s_picture() {
        val state = TransactionsUiState(categories = listOf(rent))
        assertEquals(CategoryIcon.HOME, state.iconFor(Transaction(id = "t", categoryId = "c1")))
    }

    @Test
    fun a_row_nothing_filed_is_drawn_as_unfiled() {
        assertEquals(CategoryIcon.UNFILED, TransactionsUiState().iconFor(Transaction(id = "t", categoryId = null)))
    }

    @Test
    fun the_list_is_headed_by_month_in_words() {
        val state = TransactionsUiState(
            rows = listOf(Transaction(id = "a", occurredOn = "2026-10-02"), Transaction(id = "b", occurredOn = "2026-08-31")),
        )
        assertEquals(listOf("Oct 2026", "Aug 2026"), state.monthGroups.map { state.monthHeading(it.month) })
    }

    @Test
    fun a_statement_chip_is_just_its_date() {
        val chip = TransactionsUiState().statementChip(StatementImportSummary(id = "s", createdAt = "2026-08-14T09:30:00Z", saved = 24))
        assertEquals("Aug 14, 2026", chip)
    }
}
