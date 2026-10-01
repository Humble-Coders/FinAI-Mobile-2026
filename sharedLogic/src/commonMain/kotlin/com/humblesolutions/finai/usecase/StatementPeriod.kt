package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument

/**
 * The dates a statement covers, found **before** anything is redacted.
 *
 * Order is the whole point. Statements print `14 Aug` on every transaction line
 * and the year exactly once, at the top: `Statement period: 1 Aug 2026 to 31 Aug
 * 2026`. That line sits inside the block [StatementRedactor] deletes, because it
 * is the same block the name and address are in.
 *
 * So if the period is looked for afterwards it is not there, every date we send
 * is a day and a month with no year, and the backend — built to omit a row it
 * cannot date rather than guess one — returns an empty statement. Read it first,
 * send it as its own field. A date range is not personal data.
 *
 * Do not weaken the redactor to keep the header instead. That block is where the
 * name is.
 */
object StatementPeriod {

    /** ISO dates, because that is what the parse request carries. */
    data class Range(val start: String, val end: String)

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    // `1 Aug 2026 to 31 Aug 2026`, `August 1, 2026 - August 31, 2026`,
    // `2026-08-01 to 2026-08-31`. Two dates on one line, in that order.
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val DAY_MONTH_YEAR =
        Regex("""(\d{1,2})\s+([A-Za-z]{3,})\.?,?\s+(\d{4})""")
    private val MONTH_DAY_YEAR =
        Regex("""([A-Za-z]{3,})\.?\s+(\d{1,2}),?\s+(\d{4})""")

    /**
     * The first line that names two dates, scanned from the top.
     *
     * Only the first few lines of page 1 are considered: a transaction line can
     * carry two dates too (posted and purchased), and taking one of those would
     * hand the backend a period of a single day.
     */
    fun find(document: ExtractedDocument, maxLines: Int = 20): Range? {
        val header = document.pages.firstOrNull()?.lines.orEmpty().take(maxLines)
        for (line in header) {
            // A transaction line names two dates as well — posted and
            // purchased — and a statement with a short header puts one inside
            // this window. What separates them is money: a period declaration
            // has dates and no amount. Taking a transaction's pair would hand
            // the backend a period one day wide, which is worse than none.
            if (StatementRedactor.looksLikeATransaction(line.text)) continue
            val dates = datesIn(line.text)
            if (dates.size >= 2) return ordered(dates[0], dates[1])
        }
        return null
    }

    /**
     * The two dates, earliest first.
     *
     * They are taken in the order they appear on the line, and that order is
     * not always the period's. `Statement date: 5 Sep 2026  Period: 1 Aug 2026
     * to 31 Aug 2026` opens with a date that is not the start, and `Closing …
     * opening …` prints them backwards outright. A reversed period is worse
     * than none: the backend uses it to supply the year the redactor strips,
     * so the statement is dated wrong or comes back empty — the very failure
     * this field exists to prevent.
     *
     * ISO strings, so comparing them as text compares them as dates.
     */
    private fun ordered(first: String, second: String): Range = if (first <= second) Range(first, second) else Range(second, first)

    private fun datesIn(text: String): List<String> {
        val found = mutableListOf<String>()
        ISO.findAll(text).forEach { match ->
            val (year, month, day) = match.destructured
            if (isReal(year, month.toInt(), day.toInt())) found += match.value
        }
        if (found.size >= 2) return found

        DAY_MONTH_YEAR.findAll(text).forEach { match ->
            val (day, month, year) = match.destructured
            iso(year, month, day)?.let { found += it }
        }
        if (found.size >= 2) return found

        MONTH_DAY_YEAR.findAll(text).forEach { match ->
            val (month, day, year) = match.destructured
            iso(year, month, day)?.let { found += it }
        }
        return found
    }

    private fun iso(year: String, month: String, day: String): String? {
        val number = MONTHS[month.lowercase().take(3)] ?: return null
        val numberedDay = day.toIntOrNull() ?: return null
        // `32 Aug 2026` is not a date. Unchecked it became "2026-08-32" and
        // went to the API as a period bound, where it can only be rejected or
        // misread — and a statement whose period will not parse imports empty.
        if (!isReal(year, number, numberedDay)) return null
        return "$year-${number.toString().padStart(2, '0')}-${day.padStart(2, '0')}"
    }

    private fun isReal(year: String, month: Int, day: Int): Boolean {
        val numberedYear = year.toIntOrNull() ?: return false
        if (month !in 1..12 || day < 1) return false
        return day <= daysIn(month, numberedYear)
    }

    private fun daysIn(month: Int, year: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    }
}
