package com.humblesolutions.finai.util

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import kotlinx.datetime.LocalDate

/**
 * Calendar dates as people read them, written once so the two apps can never
 * show one transaction on two different-looking days.
 */
object Dates {

    /**
     * `2026-09-12` becomes `"Sep 12, 2026"`. The order of the parts is the
     * translation's to decide (`date_display`), not the code's.
     */
    fun display(date: LocalDate): String = LocalizationRegistry.format(
        Strings.date_display,
        listOf(LocalizationRegistry.get(MONTHS[date.month.ordinal]), date.day.toString(), date.year.toString()),
    )

    /**
     * `Aug` for August — the month's own name, without a day.
     *
     * The dashboard labels a month, and [display] would add a 1st that is not
     * part of what is being said.
     */
    fun monthShort(date: LocalDate, language: String = "en"): String = LocalizationRegistry.get(MONTHS[date.month.ordinal], language)

    /** An ISO date from the wire or from saved state, or null when it is not one. */
    fun parse(iso: String?): LocalDate? = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private val MONTHS = listOf(
        Strings.month_short_1, Strings.month_short_2, Strings.month_short_3, Strings.month_short_4,
        Strings.month_short_5, Strings.month_short_6, Strings.month_short_7, Strings.month_short_8,
        Strings.month_short_9, Strings.month_short_10, Strings.month_short_11, Strings.month_short_12,
    )
}
