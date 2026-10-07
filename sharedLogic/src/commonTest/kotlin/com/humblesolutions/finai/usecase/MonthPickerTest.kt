package com.humblesolutions.finai.usecase

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonthPickerTest {

    private val october = LocalDate(2026, 10, 7)

    @Test
    fun the_current_month_and_earlier_can_be_chosen_but_not_a_later_one() {
        assertTrue(MonthPicker.isSelectable(2026, 10, october))
        assertTrue(MonthPicker.isSelectable(2026, 1, october))
        assertFalse(MonthPicker.isSelectable(2026, 11, october), "not begun: the server refuses it")
        assertFalse(MonthPicker.isSelectable(2027, 1, october))
    }

    @Test
    fun it_reaches_back_to_january_four_years_ago_and_no_further() {
        assertEquals(LocalDate(2022, 1, 1), MonthPicker.earliest(october))
        assertEquals(listOf(2022, 2023, 2024, 2025, 2026), MonthPicker.years(october))
        assertTrue(MonthPicker.isSelectable(2022, 1, october))
        assertFalse(MonthPicker.isSelectable(2021, 12, october))
    }

    @Test
    fun a_month_out_of_range_is_never_selectable() {
        assertFalse(MonthPicker.isSelectable(2026, 0, october))
        assertFalse(MonthPicker.isSelectable(2026, 13, october))
    }

    @Test
    fun the_wire_form_is_year_dash_month() {
        assertEquals("2026-03", MonthPicker.wire(2026, 3))
        assertEquals("2022-11", MonthPicker.wire(2022, 11))
    }
}
