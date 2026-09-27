package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementPeriodTest {

    private fun document(vararg lines: String) = ExtractedDocument(
        pages = listOf(
            ExtractedPage(index = 0, lines = lines.map { ExtractedLine(text = it) }),
        ),
    )

    @Test
    fun readsTheRbcShape() {
        val period = StatementPeriod.find(
            document("ROYAL BANK OF CANADA", "Statement period: 1 Aug 2026 to 31 Aug 2026"),
        )

        assertEquals(StatementPeriod.Range("2026-08-01", "2026-08-31"), period)
    }

    @Test
    fun aStatementDateBeforeThePeriodDoesNotInvertIt() {
        // The first date on the line is not the period's start. Taken in the
        // order matched this produced start=2026-09-05 end=2026-08-01.
        val period = StatementPeriod.find(
            document("Statement date: 5 Sep 2026   Period: 1 Aug 2026 to 31 Aug 2026"),
        )

        val found = assertNotNull(period)
        assertTrue(found.start <= found.end, "period runs backwards: $found")
    }

    @Test
    fun aBackwardsPeriodIsPutRightWayRound() {
        val period = StatementPeriod.find(
            document("Closing 31 Aug 2026 opening 1 Aug 2026"),
        )

        assertEquals(StatementPeriod.Range("2026-08-01", "2026-08-31"), period)
    }

    @Test
    fun aDayThatCannotExistIsNotAPeriod() {
        assertNull(StatementPeriod.find(document("Period 32 Aug 2026 to 45 Aug 2026")))
    }

    @Test
    fun theThirtiethOfFebruaryIsNotAPeriod() {
        assertNull(StatementPeriod.find(document("Period 30 Feb 2026 to 31 Mar 2026")))
    }

    @Test
    fun aLeapDayIsAPeriod() {
        val period = StatementPeriod.find(
            document("Period 29 Feb 2024 to 31 Mar 2024"),
        )

        assertEquals(StatementPeriod.Range("2024-02-29", "2024-03-31"), period)
    }

    @Test
    fun readsMonthFirstDates() {
        val period = StatementPeriod.find(
            document("Statement Period August 1, 2026 - August 31, 2026"),
        )

        assertEquals(StatementPeriod.Range("2026-08-01", "2026-08-31"), period)
    }

    @Test
    fun readsIsoDates() {
        val period = StatementPeriod.find(document("Period 2026-08-01 to 2026-08-31"))

        assertEquals(StatementPeriod.Range("2026-08-01", "2026-08-31"), period)
    }

    @Test
    fun aStatementWithNoPeriodReportsNothingRatherThanGuessing() {
        assertNull(StatementPeriod.find(document("ROYAL BANK OF CANADA", "Your statement")))
    }

    @Test
    fun aTransactionLineDeepInThePageIsNotMistakenForThePeriod() {
        // A transaction can carry two dates — posted and purchased. Taking one
        // would hand the backend a period one day wide.
        val lines = buildList {
            add("ROYAL BANK OF CANADA")
            repeat(30) { add("14 Aug 2026  POSTED 15 Aug 2026  COFFEE  5.00") }
        }

        assertNull(StatementPeriod.find(document(*lines.toTypedArray())))
    }

    @Test
    fun thePeriodSurvivesTheRedactionThatDestroysItsLine() {
        // The whole reason this is a separate step. The line the period comes
        // from is inside the block the redactor deletes, because that block is
        // where the name and address are.
        val statement = document(
            "JORDAN A MCKENZIE",
            "Statement period: 1 Aug 2026 to 31 Aug 2026",
            "01 Aug  PAYROLL DEP        2410.00",
        )

        val period = StatementPeriod.find(statement)
        val text = StatementRedactor.redact(statement)

        assertNotNull(period, "the period must be read before redaction")
        assertEquals("2026-08-01", period.start)
        assertFalse(text.contains("2026"), "the header survived redaction")
        assertFalse(text.contains("MCKENZIE"))
    }
}
