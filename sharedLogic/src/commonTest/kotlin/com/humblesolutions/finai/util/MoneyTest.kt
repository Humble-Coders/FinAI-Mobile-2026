package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The money contract, tested hard: every amount in the app passes through here,
 * and a wrong answer is a wrong figure in someone's finances — so the edge
 * cases matter more than the happy path.
 */
class MoneyTest {

    @Test
    fun `what people type becomes what the api wants`() {
        assertEquals("1200.00", Money.normalize("1200"))
        assertEquals("1200.50", Money.normalize(" 1200.5 "))
        assertEquals("1200.00", Money.normalize("1,200"))
        assertEquals("1200.50", Money.normalize("1,200.50"))
        assertEquals("1200.50", Money.normalize("1 200,50"))
        assertEquals("0.99", Money.normalize(".99"))
        assertEquals("7.00", Money.normalize("007"))
        assertEquals("1234567.00", Money.normalize("1,234,567"))
    }

    @Test
    fun `blank is unanswered rather than zero`() {
        assertNull(Money.normalize(""))
        assertNull(Money.normalize("   "))
    }

    @Test
    fun `what cannot be money is refused`() {
        // "1.234" is refused on purpose: thousands or three decimals cannot be
        // told apart, and guessing is out by a factor of a thousand.
        listOf("abc", "12a", "-5", "1.2.3", "$5", "1e6", "1.234", "1,200.").forEach {
            assertNull(Money.normalize(it), it)
        }
    }

    @Test
    fun `a figure too large to store is refused`() {
        assertEquals("999999999999.00", Money.normalize("999999999999"))
        assertNull(Money.normalize("1000000000000"))
    }

    @Test
    fun `zero is money and is not positive`() {
        assertEquals("0.00", Money.normalize("0"))
        assertTrue(Money.isMoney("0"))
        assertFalse(Money.isPositive("0"))
        assertTrue(Money.isPositive("0.01"))
    }

    @Test
    fun `the same amount written differently compares equal`() {
        assertEquals(0, Money.compare("1200", "1200.00"))
        assertEquals(0, Money.compare("1,200.00", "1200"))
        assertTrue(Money.compare("999", "1000") < 0)
        assertTrue(Money.compare("1000", "999") > 0)
        assertTrue(Money.compare("12.10", "12.09") > 0)
    }

    @Test
    fun `zero-decimal currencies keep no decimals`() {
        assertEquals(0, Money.fractionDigits("JPY"))
        assertEquals(2, Money.fractionDigits("CAD"))
        assertEquals("1200", Money.normalize("1200", fractionDigits = 0))
    }

    @Test
    fun `display follows the currency and the language the server sent`() {
        assertEquals("$1,200.50", Money.format("1200.5", "CAD"))
        assertEquals("₹1,200.00", Money.format("1200", "INR"))
        assertEquals("1 200,50 $", Money.format("1200.5", "CAD", locale = "fr-CA"))
        assertEquals("1,200.00\u00A0ZZZ", Money.format("1200", "ZZZ"))
    }

    @Test
    fun `the symbol beside a field follows the currency`() {
        assertEquals("$", Money.symbol("CAD"))
        assertEquals("₹", Money.symbol("inr"))
        assertEquals("ZZZ", Money.symbol("ZZZ"))
    }

    @Test
    fun `an amount that is not money formats to nothing rather than guessing`() {
        assertEquals("", Money.format("abc", "CAD"))
    }

    @Test
    fun `amounts add as decimals rather than as floating point`() {
        // The case that gives binary floating point away: 0.1 + 0.2 there is
        // 0.30000000000000004, which is not a figure to show anyone.
        assertEquals("0.30", Money.add("0.10", "0.20"))
        assertEquals("2200.50", Money.add("1,500", "700.50"))
        assertEquals("1000.00", Money.add("999.99", "0.01"))
        assertEquals("2400", Money.add("1200", "1200", fractionDigits = 0))
    }

    @Test
    fun `adding something that is not money refuses rather than reading it as zero`() {
        // A total that silently drops an unreadable figure is worse than no
        // total: it looks right, so nobody checks it.
        assertNull(Money.add("1200", ""))
        assertNull(Money.add("1200", "abc"))
        assertNull(Money.add("", ""))
    }
}
