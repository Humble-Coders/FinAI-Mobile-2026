package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The signed half of [Money]. Everything else there is deliberately unsigned —
 * a price cannot be negative — but a month's net can be, and the dashboard
 * shows it.
 */
class MoneySignTest {

    @Test
    fun a_plain_amount_is_positive() {
        assertEquals(1, Money.signOf("1500.00"))
        assertEquals(1, Money.signOf("0.01"))
    }

    @Test
    fun a_leading_minus_is_negative() {
        assertEquals(-1, Money.signOf("-1500.00"))
        assertEquals(-1, Money.signOf("-0.01"))
    }

    @Test
    fun zero_has_no_sign_however_it_is_written() {
        assertEquals(0, Money.signOf("0"))
        assertEquals(0, Money.signOf("0.00"))
        assertEquals(0, Money.signOf("-0.00"), "minus zero is still zero")
    }

    @Test
    fun something_unreadable_is_not_a_loss() {
        // The alternative is drawing a figure we could not parse as negative,
        // which is a wrong number that looks like a fact.
        assertEquals(0, Money.signOf("abc"))
        assertEquals(0, Money.signOf(""))
        assertEquals(0, Money.signOf("-"))
    }

    @Test
    fun the_magnitude_drops_the_sign() {
        assertEquals("1500.00", Money.magnitudeOf("-1500.00"))
        assertEquals("1500.00", Money.magnitudeOf("1500.00"))
        assertNull(Money.magnitudeOf("nonsense"))
    }

    @Test
    fun a_negative_formats_with_a_leading_minus() {
        assertEquals("-${'$'}1,500.00", Money.format("-1500.00", "CAD"))
    }

    @Test
    fun formatting_is_unchanged_for_everything_that_is_not_negative() {
        assertEquals("${'$'}1,500.00", Money.format("1500.00", "CAD"))
        assertEquals("${'$'}0.00", Money.format("0.00", "CAD"))
    }

    @Test
    fun grouping_survives_the_sign() {
        // The separator must land in the digits, not between the minus and the
        // first of them.
        assertEquals("-${'$'}1,234,567.89", Money.format("-1234567.89", "CAD"))
    }

    @Test
    fun a_zero_decimal_currency_keeps_its_sign_too() {
        assertEquals("-¥1,500", Money.format("-1500", "JPY"))
    }

    // ── difference ──────────────────────────────────────────────────────

    @Test
    fun a_bigger_figure_minus_a_smaller_one_is_positive() {
        assertEquals("400.00", Money.difference("1500.00", "1100.00"))
    }

    @Test
    fun a_smaller_figure_minus_a_bigger_one_goes_below_zero() {
        // The case a clamping subtraction would get wrong, turning "400 worse
        // than last month" into "0 worse".
        assertEquals("-400.00", Money.difference("1100.00", "1500.00"))
    }

    @Test
    fun two_equal_figures_differ_by_nothing() {
        assertEquals("0.00", Money.difference("1500.00", "1500.00"))
        assertEquals("0.00", Money.difference("-1500.00", "-1500.00"))
    }

    @Test
    fun crossing_zero_adds_the_two_distances() {
        // From -200 to 300 is a move of 500, not of 100.
        assertEquals("500.00", Money.difference("300.00", "-200.00"))
        assertEquals("-500.00", Money.difference("-200.00", "300.00"))
    }

    @Test
    fun both_below_zero_subtracts_the_right_way_round() {
        // -100 is better than -500, so the difference is positive.
        assertEquals("400.00", Money.difference("-100.00", "-500.00"))
        assertEquals("-400.00", Money.difference("-500.00", "-100.00"))
    }

    @Test
    fun zero_against_a_negative_is_a_gain() {
        assertEquals("500.00", Money.difference("0.00", "-500.00"))
        assertEquals("-500.00", Money.difference("-500.00", "0.00"))
    }

    @Test
    fun borrowing_across_columns_works() {
        assertEquals("0.01", Money.difference("1000.00", "999.99"))
        assertEquals("999.99", Money.difference("1000.00", "0.01"))
    }

    @Test
    fun a_difference_survives_thousands_separators() {
        assertEquals("400.00", Money.difference("1,500.00", "1,100.00"))
    }

    @Test
    fun something_unreadable_gives_no_difference_rather_than_zero() {
        // Zero would read as "the same as last month", which is a claim.
        assertNull(Money.difference("abc", "100.00"))
        assertNull(Money.difference("100.00", ""))
    }

    @Test
    fun a_zero_decimal_currency_differs_at_its_own_scale() {
        assertEquals("400", Money.difference("1500", "1100", fractionDigits = 0))
    }
}
