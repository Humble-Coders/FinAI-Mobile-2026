package com.humblesolutions.finai.util

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatesTest {

    @Test
    fun aDateReadsAsMonthDayYear() {
        assertEquals("Sep 2, 2026", Dates.display(LocalDate(2026, 9, 2)))
    }

    @Test
    fun theFirstAndLastMonthsAreNamedRight() {
        // The month table is indexed from zero; an off-by-one would show
        // January as February and December as a missing key.
        assertEquals("Jan 31, 2026", Dates.display(LocalDate(2026, 1, 31)))
        assertEquals("Dec 1, 2025", Dates.display(LocalDate(2025, 12, 1)))
    }

    @Test
    fun anIsoDateParses() {
        assertEquals(LocalDate(2026, 9, 12), Dates.parse("2026-09-12"))
    }

    @Test
    fun anythingElseIsNull() {
        assertNull(Dates.parse(null))
        assertNull(Dates.parse(""))
        assertNull(Dates.parse("12/09/2026"))
        assertNull(Dates.parse("2026-02-30"))
    }
}
