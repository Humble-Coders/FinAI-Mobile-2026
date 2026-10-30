package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.util.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatementBalanceTest {

    private fun document(vararg lines: String) = ExtractedDocument(
        pages = listOf(ExtractedPage(index = 0, lines = lines.map { ExtractedLine(text = it) })),
    )

    @Test
    fun `it reads the new balance and the statement date`() {
        val found = StatementBalance.find(
            document("VISA CLASSIC", "Statement date: Aug 31, 2026", "Previous balance $3,900.00", "New balance $4,012.37"),
            periodEnd = null,
            currency = "CAD",
        )

        assertEquals(StatementBalance.Found("4012.37", "2026-08-31"), found)
    }

    @Test
    fun `without a statement date the period end dates it`() {
        val found = StatementBalance.find(document("Closing balance 1,250.00"), periodEnd = "2026-08-31", currency = "CAD")

        assertEquals(StatementBalance.Found("1250.00", "2026-08-31"), found)
    }

    @Test
    fun `a figure on the line after its label is read`() {
        val found = StatementBalance.find(document("Balance due", "$812.40", "Payment due date Sep 25, 2026"), periodEnd = "2026-08-31", currency = "CAD")

        assertEquals("812.40", found?.balance)
    }

    @Test
    fun `the previous balance alone is not the closing balance`() {
        assertNull(StatementBalance.find(document("Previous balance $3,900.00"), periodEnd = "2026-08-31", currency = "CAD"))
    }

    @Test
    fun `a balance in credit is not offered`() {
        assertNull(StatementBalance.find(document("New balance $25.00 CR"), periodEnd = "2026-08-31", currency = "CAD"))
        assertNull(StatementBalance.find(document("New balance ($25.00)"), periodEnd = "2026-08-31", currency = "CAD"))
        assertNull(StatementBalance.find(document("New balance -$25.00"), periodEnd = "2026-08-31", currency = "CAD"))
    }

    @Test
    fun `an undated balance is not offered`() {
        assertNull(StatementBalance.find(document("New balance $400.00"), periodEnd = null, currency = "CAD"))
    }

    @Test
    fun `it is offered for the linked debt only when newer`() {
        val found = StatementBalance.Found("4012.37", "2026-08-31")
        val linked = DebtRecord(id = "d-visa", name = "Visa", accountId = "a-card", balanceAsOf = "2026-07-31")

        assertEquals(StatementBalance.Offer(found, "d-visa", "Visa"), StatementBalance.offer(found, listOf(linked), "a-card"))
        assertNull(StatementBalance.offer(found, listOf(linked), "a-other"), "no debt is linked to that account")
        assertNull(StatementBalance.offer(found, listOf(linked.copy(balanceAsOf = "2026-08-31")), "a-card"), "not newer: the server would keep its own")
        assertNull(StatementBalance.offer(null, listOf(linked), "a-card"))
    }

    @Test
    fun `the question names the figure the day and the debt`() {
        val offer = StatementBalance.Offer(StatementBalance.Found("4012.37", "2026-08-31"), "d-visa", "Visa")

        assertEquals(
            "This statement says you owed ${Money.format("4012.37", "CAD")} on Aug 31, 2026. Update Visa to this balance?",
            StatementBalance.question(offer, "CAD"),
        )
    }

    @Test
    fun `a rupee balance grouped in lakhs is read whole`() {
        // Read with the threes pattern, only the tail "000.00" matched: a balance of zero.
        val found = StatementBalance.find(
            document("HDFC BANK CREDIT CARD", "Statement Date: 05-Nov-2026", "Total Amount Due ₹1,23,456.78"),
            periodEnd = null,
            currency = "INR",
        )

        assertEquals(StatementBalance.Found("123456.78", "2026-11-05"), found)
    }

    @Test
    fun `a rupee balance marked Rs is read`() {
        val found = StatementBalance.find(document("Closing Balance Rs.2,50,000.00"), periodEnd = "2026-10-31", currency = "INR")

        assertEquals(StatementBalance.Found("250000.00", "2026-10-31"), found)
    }

    @Test
    fun `a rupee balance in credit is not offered`() {
        assertEquals(null, StatementBalance.find(document("Total Amount Due 1,500.00 Cr"), periodEnd = "2026-10-31", currency = "INR"))
    }
}
