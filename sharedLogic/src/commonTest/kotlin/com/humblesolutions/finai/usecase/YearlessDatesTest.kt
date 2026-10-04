package com.humblesolutions.finai.usecase

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class YearlessDatesTest {

    private val today = LocalDate(2026, 10, 4)

    private fun complete(text: String) = YearlessDates.complete(text, today)

    @Test
    fun a_day_heading_this_month_is_this_year() {
        // The reported bug: saved as October 2025.
        assertEquals("Friday, October 2, 2026", complete("Friday, October 2"))
        assertEquals("Wednesday, September 30, 2026", complete("Wednesday, September 30"))
    }

    @Test
    fun a_date_later_in_the_year_than_today_is_last_year() {
        assertEquals("Dec 3, 2025", complete("Dec 3"))
        assertEquals("14 Nov 2025", complete("14 Nov"))
    }

    @Test
    fun tomorrow_is_still_this_year_for_a_bank_ahead_of_the_phone() {
        assertEquals("Oct 5, 2026", complete("Oct 5"))
        assertEquals("Oct 6, 2025", complete("Oct 6"))
    }

    @Test
    fun a_date_that_has_its_year_is_left_alone() {
        assertEquals("4 Oct 2026, 6:27pm", complete("4 Oct 2026, 6:27pm"))
        assertEquals("October 2, 2025", complete("October 2, 2025"))
    }

    @Test
    fun a_date_inside_a_row_gets_its_year_and_the_amount_is_untouched() {
        assertEquals("Oct 2, 2026   Maple Leaf Grocers   -$86.40", complete("Oct 2   Maple Leaf Grocers   -$86.40"))
    }

    @Test
    fun a_month_word_beside_an_amount_is_not_a_date() {
        assertEquals("Mar 12.50", complete("Mar 12.50"))
    }

    @Test
    fun today_and_yesterday_become_dates() {
        assertEquals("2026-10-04\nCorner Coffee   -$5.25\n2026-10-03", complete("Today\nCorner Coffee   -$5.25\nYesterday"))
    }

    @Test
    fun a_numeric_date_is_left_alone_because_its_month_is_a_guess() {
        assertEquals("10/02   Coffee   5.25", complete("10/02   Coffee   5.25"))
    }

    @Test
    fun a_day_no_month_has_is_left_alone() {
        assertEquals("Feb 30", complete("Feb 30"))
    }
}
