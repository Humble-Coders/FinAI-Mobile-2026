package com.humblesolutions.finai.usecase

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Moving between the months the dashboard can show (#F3).
 *
 * Shared because "which month comes before this one" is a decision, and two
 * implementations of it disagree at a year boundary — reliably, every
 * December, on one platform only.
 *
 * The wire form is `YYYY-MM`; the server names a month by its first day, so
 * what comes back is `YYYY-MM-01`. Both are accepted here.
 */
object DashboardMonths {

    /** The wire form the API expects: `2026-08`. */
    fun wire(month: LocalDate): String = month.year.toString().padStart(4, '0') + "-" + month.monthNumber.toString().padStart(2, '0')

    /** `2026-08` or `2026-08-01` as a date, or null when it is neither. */
    fun parse(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val full = if (text.length == 7) "$text-01" else text
        return runCatching { LocalDate.parse(full) }.getOrNull()?.let { first(it) }
    }

    /** The first of [date]'s month, which is how a month is identified. */
    fun first(date: LocalDate): LocalDate = LocalDate(date.year, date.monthNumber, 1)

    /** The month before [month]. December rolls the year, which is the whole point. */
    fun previous(month: LocalDate): LocalDate {
        val start = first(month)
        return if (start.monthNumber == 1) {
            LocalDate(start.year - 1, 12, 1)
        } else {
            LocalDate(start.year, start.monthNumber - 1, 1)
        }
    }

    /** The month after [month]. */
    fun next(month: LocalDate): LocalDate {
        val start = first(month)
        return if (start.monthNumber == 12) {
            LocalDate(start.year + 1, 1, 1)
        } else {
            LocalDate(start.year, start.monthNumber + 1, 1)
        }
    }

    /** Today's month in the device's own zone, which is where "this month" means something. */
    fun current(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = first(Clock.System.todayIn(timeZone))

    /**
     * Whether [month] is the one running now.
     *
     * The forward control is hidden on it rather than disabled: a month that
     * has not happened holds nothing, and offering to look at it is offering a
     * screen of zeroes.
     */
    fun isCurrent(month: LocalDate, today: LocalDate = current()): Boolean = first(month) == first(today)

    /** Whether the user may step forward from [month] at all. */
    fun canGoForward(month: LocalDate, today: LocalDate = current()): Boolean = first(month) < first(today)
}
