package com.humblesolutions.finai.usecase

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Month arithmetic. Shared because the year boundary is where two
 * implementations disagree — reliably, every December, on one platform only.
 */
class DashboardMonthsTest {

    @Test
    fun january_steps_back_into_the_previous_year() {
        assertEquals(LocalDate(2025, 12, 1), DashboardMonths.previous(LocalDate(2026, 1, 1)))
    }

    @Test
    fun december_steps_forward_into_the_next_year() {
        assertEquals(LocalDate(2027, 1, 1), DashboardMonths.next(LocalDate(2026, 12, 1)))
    }

    @Test
    fun stepping_within_a_year_keeps_it() {
        assertEquals(LocalDate(2026, 7, 1), DashboardMonths.previous(LocalDate(2026, 8, 1)))
        assertEquals(LocalDate(2026, 9, 1), DashboardMonths.next(LocalDate(2026, 8, 1)))
    }

    @Test
    fun a_day_inside_a_month_is_normalised_to_its_first() {
        assertEquals(LocalDate(2026, 8, 1), DashboardMonths.first(LocalDate(2026, 8, 31)))
        assertEquals(LocalDate(2026, 7, 1), DashboardMonths.previous(LocalDate(2026, 8, 31)))
    }

    @Test
    fun the_wire_form_is_padded() {
        assertEquals("2026-08", DashboardMonths.wire(LocalDate(2026, 8, 1)))
        assertEquals("2026-01", DashboardMonths.wire(LocalDate(2026, 1, 1)))
    }

    @Test
    fun both_the_wire_form_and_the_servers_first_day_parse() {
        assertEquals(LocalDate(2026, 8, 1), DashboardMonths.parse("2026-08"))
        assertEquals(LocalDate(2026, 8, 1), DashboardMonths.parse("2026-08-01"))
        assertEquals(LocalDate(2026, 8, 1), DashboardMonths.parse("2026-08-17"))
    }

    @Test
    fun nonsense_parses_to_nothing_rather_than_to_a_guess() {
        assertNull(DashboardMonths.parse(null))
        assertNull(DashboardMonths.parse(""))
        assertNull(DashboardMonths.parse("august"))
        assertNull(DashboardMonths.parse("2026-13"))
    }

    @Test
    fun a_round_trip_through_the_wire_form_changes_nothing() {
        val month = LocalDate(2026, 1, 1)
        assertEquals(month, DashboardMonths.parse(DashboardMonths.wire(month)))
    }

    @Test
    fun the_current_month_is_recognised_whatever_day_it_is() {
        val today = LocalDate(2026, 8, 17)
        assertTrue(DashboardMonths.isCurrent(LocalDate(2026, 8, 1), today))
        assertFalse(DashboardMonths.isCurrent(LocalDate(2026, 7, 1), today))
    }

    @Test
    fun there_is_nowhere_forward_to_go_from_the_current_month() {
        // A month that has not happened holds nothing, and offering to look at
        // it is offering a screen of zeroes.
        val today = LocalDate(2026, 8, 17)
        assertFalse(DashboardMonths.canGoForward(LocalDate(2026, 8, 1), today))
        assertTrue(DashboardMonths.canGoForward(LocalDate(2026, 7, 1), today))
    }

    @Test
    fun a_future_month_offers_no_way_further_forward_either() {
        val today = LocalDate(2026, 8, 17)
        assertFalse(DashboardMonths.canGoForward(LocalDate(2026, 9, 1), today))
    }

    @Test
    fun walking_back_twelve_months_lands_a_year_earlier() {
        var month = LocalDate(2026, 8, 1)
        repeat(12) { month = DashboardMonths.previous(month) }
        assertEquals(LocalDate(2025, 8, 1), month)
    }
}
