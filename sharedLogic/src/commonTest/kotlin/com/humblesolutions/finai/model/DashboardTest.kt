package com.humblesolutions.finai.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules the dashboard screens read. The one worth staring at is
 * [nothing_here_adds_an_expectation_to_an_actual] — everything else is detail.
 */
class DashboardTest {

    @Test
    fun nothing_here_adds_an_expectation_to_an_actual() {
        // Rent typed at setup and then imported from the bank is one payment.
        // If any property below ever returns 5300 — 1800 expected plus 3500
        // spent — this is the double count, and the model has grown the bug
        // the server refuses to have.
        val month = Dashboard(
            currency = "CAD",
            net = "1500.00",
            income = Flow(actual = "5000.00", expected = "5200.00"),
            expenses = Flow(actual = "3500.00", expected = "3000.00"),
            commitments = listOf(Commitment(name = "Rent", expected = "1800.00")),
        )

        assertEquals("3500.00", month.expenses.actual)
        assertEquals("3000.00", month.expenses.expected)
        assertEquals("1500.00", month.net, "net is the server's, not re-derived here")
    }

    @Test
    fun a_month_that_kept_nothing_is_not_positive() {
        assertFalse(Dashboard(net = "0.00").netIsPositive)
        assertFalse(Dashboard(net = "-250.00").netIsPositive)
        assertTrue(Dashboard(net = "0.01").netIsPositive)
    }

    @Test
    fun the_comparison_is_unavailable_when_last_month_held_nothing() {
        // A rise from nothing is not a percentage, so the screen omits the line.
        assertFalse(Dashboard(net = "100.00", previousNet = null).changeLabelAvailable)
        assertTrue(Dashboard(net = "100.00", previousNet = "0.00").changeLabelAvailable)
    }

    @Test
    fun an_expectation_that_was_never_given_is_absent_not_zero() {
        assertFalse(Flow(actual = "10.00").hasExpectation)
        assertTrue(Flow(actual = "10.00", expected = "0.00").hasExpectation)
    }

    @Test
    fun spending_past_the_expectation_is_visible() {
        assertTrue(Flow(actual = "3500.00", expected = "3000.00").isOverExpected)
        assertFalse(Flow(actual = "3000.00", expected = "3000.00").isOverExpected)
        assertFalse(Flow(actual = "2000.00", expected = "3000.00").isOverExpected)
    }

    @Test
    fun a_flow_with_no_expectation_is_never_over_it() {
        assertFalse(Flow(actual = "9999.00").isOverExpected)
    }

    @Test
    fun a_commitment_with_no_match_was_not_seen() {
        val unseen = Commitment(name = "Car payment", expected = "400.00")
        assertFalse(unseen.wasSeen)

        val seen = Commitment(
            name = "Rent",
            expected = "1800.00",
            match = CommitmentMatch(occurredOn = "2026-08-02", amount = "1800.00"),
        )
        assertTrue(seen.wasSeen)
    }

    @Test
    fun a_payment_of_a_different_size_is_worth_pointing_at() {
        val hydro = Commitment(
            name = "Toronto Hydro",
            expected = "140.00",
            match = CommitmentMatch(amount = "151.00"),
        )
        assertTrue(hydro.paidADifferentAmount)

        val rent = Commitment(
            name = "Rent",
            expected = "1800.00",
            match = CommitmentMatch(amount = "1800.00"),
        )
        assertFalse(rent.paidADifferentAmount)
    }

    @Test
    fun an_unmatched_commitment_is_not_reported_as_a_different_amount() {
        assertFalse(Commitment(name = "Rent", expected = "1800.00").paidADifferentAmount)
    }

    @Test
    fun the_unseen_keep_the_order_they_were_set_up_in() {
        val month = Dashboard(
            commitments = listOf(
                Commitment(name = "Rent", expected = "1800.00", match = CommitmentMatch(amount = "1800.00")),
                Commitment(name = "Car payment", expected = "400.00"),
                Commitment(name = "Insurance", expected = "90.00"),
            ),
        )

        assertEquals(listOf("Car payment", "Insurance"), month.commitmentsNotSeen.map { it.name })
        assertEquals(1, month.commitmentsSeenCount)
    }

    @Test
    fun a_month_with_no_rows_at_all_is_empty() {
        assertTrue(Dashboard(currency = "CAD").isEmpty)
    }

    @Test
    fun a_month_with_money_in_it_is_not_empty() {
        assertFalse(Dashboard(income = Flow(actual = "10.00")).isEmpty)
        assertFalse(Dashboard(expenses = Flow(actual = "10.00")).isEmpty)
    }

    @Test
    fun a_month_with_commitments_is_not_empty_even_before_any_statement() {
        // Somebody who finished the wizard has something to look at: a
        // checklist of what has not been seen yet.
        assertFalse(Dashboard(commitments = listOf(Commitment(name = "Rent"))).isEmpty)
    }

    @Test
    fun a_month_whose_trend_holds_data_is_not_empty() {
        assertFalse(Dashboard(trend = listOf(MonthPoint(month = "2026-07-01", net = "10.00"))).isEmpty)
    }

    @Test
    fun a_trend_point_with_no_net_is_a_gap() {
        // Not a zero. A zero-height bar states a fact nobody observed.
        assertFalse(MonthPoint(month = "2026-07-01").hasData)
        assertTrue(MonthPoint(month = "2026-07-01", net = "0.00").hasData)
    }

    @Test
    fun a_stock_that_did_not_move_says_so() {
        assertFalse(Stock(balance = "40000.00", moved = "0.00").movedThisMonth)
        assertTrue(Stock(balance = "40000.00", moved = "500.00").movedThisMonth)
    }
}
