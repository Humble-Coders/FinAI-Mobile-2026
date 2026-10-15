package com.humblesolutions.finai.usecase

import kotlinx.datetime.LocalDate

/**
 * Which months the budget's calendar offers (Budget tab).
 *
 * Shared so the two apps' calendars cannot disagree about what can be picked:
 * Android draws its own month grid, iOS uses the system's year-and-month
 * picker bounded by [earliest] and the current month.
 *
 * **No month that has not begun:** the server refuses one
 * (`month_in_future`), so the calendar never offers it. **No further back than
 * [YEARS_BACK] whole years:** opening a month generates and saves its budget,
 * and a calendar running back decades would invite exactly that for months
 * nobody has data for.
 */
object MonthPicker {

    /** How many years before the current one the calendar reaches, to January. */
    const val YEARS_BACK = 4

    /** The first month offered: January, [YEARS_BACK] years ago. */
    fun earliest(current: LocalDate): LocalDate = LocalDate(current.year - YEARS_BACK, 1, 1)

    /** The years offered, oldest first. */
    fun years(current: LocalDate): List<Int> = ((current.year - YEARS_BACK)..current.year).toList()

    /** Whether [month] (1–12) of [year] can be chosen. */
    fun isSelectable(year: Int, month: Int, current: LocalDate): Boolean {
        if (month !in 1..12) return false
        if (year < current.year - YEARS_BACK || year > current.year) return false
        return year < current.year || month <= current.month.ordinal + 1
    }

    /** `YYYY-MM` for [month] of [year], the form the budget endpoint takes. */
    fun wire(year: Int, month: Int): String = DashboardMonths.wire(LocalDate(year, month, 1))
}
