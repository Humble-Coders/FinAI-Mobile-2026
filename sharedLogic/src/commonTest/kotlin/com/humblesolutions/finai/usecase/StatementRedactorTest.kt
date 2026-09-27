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

    @Test
    fun aSocialInsuranceNumberIsMasked() {
        // Printed 3-3-3, which a rule written only for 4-4-4-4 card numbers
        // walks straight past. This is the worst thing that can reach the API.
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  GOVT DEPOSIT 123 456 789          20.00")),
        )

        assertFalse(text.contains("123 456 789"), "a SIN reached the wire: $text")
        assertNoLongDigitRun(text)
    }

    @Test
    fun anIdentifierWrittenWithDecimalsIsStillMasked() {
        // Two decimal places do not make something an amount. Seven digits
        // before the point is nobody's grocery bill.
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  WIRE REF 1234567.89               50.00")),
        )

        assertFalse(text.contains("1234567"), "the reference reached the wire: $text")
        assertTrue(text.contains("50.00"), "the real amount was lost: $text")
    }

    @Test
    fun anAccountNumberIsNotPartlySavedByATrailingAmount() {
        // `5004321.00` used to be read as an amount, so only the `06012`
        // prefix was masked and the identifying digits went out intact.
        val text = StatementRedactor.redact(
            document(listOf("14 Aug  ACCT 06012-5004321.00             10.00")),
        )

        assertFalse(text.contains("5004321"), "the account number survived: $text")
        assertTrue(text.contains("10.00"), "the real amount was lost: $text")
    }

    /**
     * The wire claim, asserted the way it is meant — and deliberately NOT by
     * stripping anything that looks like an amount first.
     *
     * That is how the leak hid: the helper removed `\d[\d,]*\.\d{2}` before
     * looking, so `WIRE REF 1234567.89` was erased by the strip and the check
     * reported clean while seven digits went out on the wire. The assertion
     * could not see the one thing it exists to catch.
     *
     * So it mirrors the masking rules instead: no run of five or more digits
     * inside a token, and no number written in spaced groups. A price is
     * neither — `123456.78` is one token that is an amount, and a masked tail
     * printed beside an amount ("••••4567 10.99") is not one long number.
     */
    private fun assertNoLongDigitRun(text: String) {
        val amount =
            Regex("""\(?-?\$?(?:\d{1,3}(?:,\d{3})*|\d{1,6})\.\d{2}\)?-?""")
        val offenders = Regex("""\S+""").findAll(text)
            .filterNot { amount.matches(it.value) }
            .flatMap { Regex("""\d(?:-?\d){4,}""").findAll(it.value) }
            .map { it.value }
            .toList() +
            Regex("""\b\d{3,5}(?:[ -]\d{3,5}){2,}\b""").findAll(text).map { it.value }

        assertTrue(offenders.isEmpty(), "unmasked digits: $offenders in: $text")
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
