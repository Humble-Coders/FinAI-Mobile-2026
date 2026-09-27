package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both directions are failures.
 *
 * Leaving an account number in breaks the promise the whole architecture rests
 * on. Dropping a transaction line means somebody's statement imports short and
 * nothing anywhere says so — which is worse, because it looks like success.
 */
class StatementRedactorTest {

    private fun document(vararg pages: List<String>) = ExtractedDocument(
        pages = pages.mapIndexed { index, lines ->
            ExtractedPage(index = index, lines = lines.map { ExtractedLine(text = it) })
        },
    )

    private val statement = document(
        listOf(
            "ROYAL BANK OF CANADA",
            "JORDAN A MCKENZIE",
            "144 RIDEAU ST APT 12",
            "OTTAWA ON  K1N 5X6",
            "Account number: 06012-5004321",
            "Statement period: 1 Aug 2026 to 31 Aug 2026",
            "Opening balance                          2,184.63",
            "01 Aug  PAYROLL DEP NORTHWIND              2,410.00",
            "02 Aug  TIM HORTONS #4821 OTTAWA ON            5.25",
            "07 Aug  SPOTIFY P3A4B5C6                      11.29",
        ),
        listOf(
            "ROYAL BANK OF CANADA - Account number: 06012-5004321 - page 2",
            "24 Aug  COSTCO WHOLESALE W1284               212.34",
        ),
    )

    @Test
    fun theNameAndAddressBlockIsGone() {
        val text = StatementRedactor.redact(statement)

        assertFalse(text.contains("MCKENZIE"), "the account holder's name survived")
        assertFalse(text.contains("RIDEAU ST"), "the address survived")
        assertFalse(text.contains("K1N"), "the postal code survived")
    }

    @Test
    fun everyTransactionLineSurvives() {
        val text = StatementRedactor.redact(statement)

        assertTrue(text.contains("PAYROLL DEP NORTHWIND"))
        assertTrue(text.contains("TIM HORTONS"))
        assertTrue(text.contains("COSTCO WHOLESALE"))
    }

    @Test
    fun noDigitRunLongerThanFourReachesTheWire() {
        val text = StatementRedactor.redact(statement)

        // Not `text.replace(" ", "")` first: collapsing the whitespace joins
        // "W1284" and "212.34" into "1284212", a run that exists nowhere on the
        // statement. The claim is about digits as printed.
        val runs = Regex("""\d(?:[ -]?\d){4,}""").findAll(text).toList()
        assertTrue(runs.isEmpty(), "unmasked digits: ${runs.map { it.value }}")
    }

    @Test
    fun anAccountNumberKeepsOnlyItsLastFour() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  TRANSFER 06012-5004321   50.00")),
        )

        assertTrue(text.contains("••••4321"), "got: $text")
        assertFalse(text.contains("5004321"))
    }

    @Test
    fun amountsAreNotMistakenForAccountNumbers() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  RENT PAYMENT              1234.56")),
        )

        assertEquals(true, text.contains("1234.56"), "an amount was masked: $text")
    }

    @Test
    fun aLineCarryingAnEmailIsDropped() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  COFFEE  5.00", "Questions? write to help@rbc.com")),
        )

        assertFalse(text.contains("help@rbc.com"))
        assertTrue(text.contains("COFFEE"))
    }

    @Test
    fun aLineCarryingAPhoneNumberIsDropped() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  COFFEE  5.00", "Call 1-800-769-2511 for help")),
        )

        assertFalse(text.contains("769-2511"))
    }

    @Test
    fun aMerchantCodeIsNotMistakenForAPostalCode() {
        // `SPOTIFY P3A4B5C6` contains `P3A4B5`, which matches a postal code
        // without its trailing boundary. Over-redaction loses a transaction.
        val text = StatementRedactor.redact(statement)

        assertTrue(text.contains("SPOTIFY"), "a merchant was read as an address")
    }

    @Test
    fun thePageTwoHeaderKeepsItsAccountNumberMasked() {
        val text = StatementRedactor.redact(statement)

        assertFalse(text.contains("5004321"), "page 2 leaked the account number")
    }

    @Test
    fun aDocumentWithNoTransactionsProducesNothing() {
        val text = StatementRedactor.redact(document(listOf("JORDAN A MCKENZIE", "OTTAWA ON")))

        assertEquals("", text)
    }
}
