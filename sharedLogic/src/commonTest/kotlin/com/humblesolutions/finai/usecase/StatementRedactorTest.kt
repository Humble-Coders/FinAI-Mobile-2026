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
        assertNoLongDigitRun(StatementRedactor.redact(statement))
    }

    @Test
    fun aFiveDigitReferenceIsMasked() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  E-TRANSFER 12345                  25.00")),
        )

        assertFalse(text.contains("12345"), "a five-digit run reached the wire: $text")
        assertNoLongDigitRun(text)
    }

    @Test
    fun aSixDigitReferenceIsMasked() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  PURCHASE REF 123456               10.99")),
        )

        assertFalse(text.contains("123456"), "a six-digit run reached the wire: $text")
        assertNoLongDigitRun(text)
    }

    @Test
    fun anAmountBesideAChequeNumberIsNotRewritten() {
        // One space, not a column of them. The old rule let the digit run cross
        // it and swallow the amount's leading digits, turning 10.99 into
        // 6710.99 — a wrong number that reads as a right one.
        val text = StatementRedactor.redact(
            document(listOf("14 Aug CHQ 1234567 10.99")),
        )

        // Asserted whole, not with `endsWith("10.99")`: the bug produced
        // "••••6710.99", which ends with "10.99" and is still wrong.
        assertEquals("14 Aug CHQ ••••4567 10.99", text)
    }

    @Test
    fun aLargeAmountIsNotMaskedAsAnIdentifier() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  PROPERTY TRANSFER            123456.78")),
        )

        assertTrue(text.contains("123456.78"), "a real amount was masked: $text")
    }

    @Test
    fun aSpacedCardNumberIsMasked() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  PAYMENT  10.00", "Card 4510 1234 5678 9012")),
        )

        assertFalse(text.contains("5678"), "the card number survived: $text")
        assertTrue(text.contains("\u2022\u2022\u2022\u20229012"), "got: $text")
    }

    @Test
    fun aTransactionIsNotDroppedForLookingLikeAnAddress() {
        // `A1B2C3` is a merchant reference shaped exactly like a postal code.
        // Dropping the row hides money; masking the token does not.
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  TIM HORTONS A1B2C3                 4.25")),
        )

        assertTrue(text.contains("TIM HORTONS"), "a transaction was dropped: $text")
        assertTrue(text.contains("4.25"), "its amount went with it: $text")
        assertFalse(text.contains("A1B2C3"), "the postal-shaped token survived: $text")
    }

    @Test
    fun aTransactionIsNotDroppedForNamingAnEmail() {
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  E-TRANSFER TO jane@example.com     50.00")),
        )

        assertTrue(text.contains("E-TRANSFER"), "a transaction was dropped: $text")
        assertTrue(text.contains("50.00"))
        assertFalse(text.contains("jane@example.com"), "the email survived: $text")
    }

    @Test
    fun whatWasDroppedIsCounted() {
        // Seven lines sit above the first transaction on page 1: the bank, the
        // name, two address lines, the account number, the period, and the
        // opening balance — which carries an amount but no date, so it is not
        // a transaction.
        val redaction = StatementRedactor.of(statement)

        assertEquals(7, redaction.droppedLines)
        assertEquals(StatementRedactor.redact(statement), redaction.text)
    }

    @Test
    fun aPageWithNoTransactionCountsEveryLineItDropped() {
        val redaction = StatementRedactor.of(
            document(listOf("ROYAL BANK OF CANADA", "JORDAN A MCKENZIE")),
        )

        assertEquals("", redaction.text)
        assertEquals(2, redaction.droppedLines)
    }

    /**
     * The wire claim, asserted the way it is meant.
     *
     * Amounts come out first: `123456.78` is a price, not an identifier, and
     * the run is measured inside a token because a masked tail printed beside
     * an amount ("••••4567 10.99") is not a seven-digit number.
     */
    private fun assertNoLongDigitRun(text: String) {
        val withoutAmounts = Regex("""\d[\d,]*\.\d{2}""").replace(text, " ")
        val runs = Regex("""\d(?:-?\d){4,}""").findAll(withoutAmounts).toList()
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
