package com.humblesolutions.finai.usecase

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Writes the year into a screenshot's dates, on the device, before anything
 * is sent.
 *
 * A banking app prints "Friday, October 2" and no year anywhere. Leaving the
 * year to the model — with the year ending today as context — went wrong in
 * the one way that matters: it took the year from the start of that window
 * and saved October 2026's rows as October 2025, where nobody looked for
 * them. Which year a screenshot means is not a judgement call, so it is not
 * left to one: it is the most recent October 2 that is not in the future.
 *
 * Only dates that name their month in words. `10/02` is October 2 or
 * February 10 depending on who wrote it, and guessing the year of a date
 * whose month is itself a guess compounds the error; those are left as they
 * are. "Today" and "Yesterday" headings become the dates they mean.
 */
object YearlessDates {

    private const val MONTH =
        """(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"""

    // `Oct 2` / `October 2nd`, or `2 Oct` / `2nd October`, each with the year
    // that may follow it captured, so a date that has one is left alone.
    private val DATE = Regex(
        """\b(?:$MONTH\.?\s+(\d{1,2})(?:st|nd|rd|th)?|(\d{1,2})(?:st|nd|rd|th)?\s+$MONTH\.?)\b(,?\s*\d{4})?""",
        RegexOption.IGNORE_CASE,
    )

    private val TODAY = Regex("""^\s*today\s*$""", RegexOption.IGNORE_CASE)
    private val YESTERDAY = Regex("""^\s*yesterday\s*$""", RegexOption.IGNORE_CASE)

    // What may not follow a day, because then it was never a day: `Mar 12.50`
    // is an amount beside a word, and `Mar 12,50` the same in another hand.
    private val NOT_A_DAY = Regex("""^[.,]\d""")

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun complete(text: String, today: LocalDate): String = text.lines().joinToString("\n") { line ->
        when {
            TODAY.matches(line) -> today.toString()
            YESTERDAY.matches(line) -> today.minus(DatePeriod(days = 1)).toString()
            else -> withYears(line, today)
        }
    }

    private fun withYears(line: String, today: LocalDate): String = DATE.replace(line) { match ->
        val (monthFirst, dayAfter, dayFirst, monthAfter, year) = match.destructured
        val rest = line.substring(match.range.last + 1)
        if (year.isNotEmpty() || NOT_A_DAY.containsMatchIn(rest)) return@replace match.value
        val month = MONTHS.indexOf((monthFirst.ifEmpty { monthAfter }).take(3).lowercase()) + 1
        val day = (dayAfter.ifEmpty { dayFirst }).toInt()
        val resolved = mostRecent(month, day, today) ?: return@replace match.value
        if (monthFirst.isNotEmpty()) "${match.value}, ${resolved.year}" else "${match.value} ${resolved.year}"
    }

    /**
     * The latest [month]/[day] on or before today — a day's grace, for a
     * transaction stamped in the bank's time zone ahead of the phone's.
     * Null when there is no such date (February 30).
     */
    private fun mostRecent(month: Int, day: Int, today: LocalDate): LocalDate? {
        val thisYear = runCatching { LocalDate(today.year, month, day) }.getOrNull()
            ?: return runCatching { LocalDate(today.year - 1, month, day) }.getOrNull()
        return if (thisYear > today.plus(DatePeriod(days = 1))) {
            runCatching { LocalDate(today.year - 1, month, day) }.getOrNull()
        } else {
            thisYear
        }
    }
}
