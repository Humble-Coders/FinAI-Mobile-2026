package com.humblesolutions.finai.util

/**
 * A percentage a person typed — an interest rate, the goal share, the rate
 * line — judged by the server's rule (`financial_setup.rate_bps`): 0 to 100,
 * at most two decimal places.
 *
 * On [Money]'s digits rather than a `Double`, so `"20.99"` is exactly that and
 * the comparison with 100 cannot round its way past the limit.
 */
object Percent {

    enum class Problem {
        BLANK,
        NOT_A_PERCENT,
        TOO_PRECISE,
        OUT_OF_RANGE,
    }

    private const val DIGITS = 2
    private const val MAX = "100"

    /** [raw] without its `%` and spaces — people type both. */
    private fun cleaned(raw: String): String = raw.trim().removeSuffix("%").trim()

    /** What is wrong with [raw], or null when it is a percentage the server accepts. */
    fun problem(raw: String): Problem? {
        val text = cleaned(raw)
        if (text.isEmpty()) return Problem.BLANK
        // A minus is a figure, just out of range: say that, not "not a percentage".
        if (text.startsWith("-")) return if (Money.normalize(text.removePrefix("-"), WIDE) != null) Problem.OUT_OF_RANGE else Problem.NOT_A_PERCENT
        // A comma reads as a thousands separator to Money; in a percentage it is never one.
        if (',' in text) return Problem.NOT_A_PERCENT
        val normalized = Money.normalize(text, DIGITS)
            ?: return if (Money.normalize(text, WIDE) != null) Problem.TOO_PRECISE else Problem.NOT_A_PERCENT
        if (Money.compare(normalized, MAX, DIGITS) > 0) return Problem.OUT_OF_RANGE
        return null
    }

    /** [raw] as it is sent — `"6"` becomes `"6.00"` — or null when it has a [problem]. */
    fun normalize(raw: String): String? = if (problem(raw) == null) Money.normalize(cleaned(raw), DIGITS) else null

    /** Whether two percentages are the same figure: `"6"` and `"6.00"` are. */
    fun same(a: String?, b: String?): Boolean = normalizeOrNull(a) == normalizeOrNull(b)

    private fun normalizeOrNull(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let { normalize(it) ?: it }

    /** `"20.99"` → `"20.99%"`; `"6.00"` → `"6%"`: as a person says a rate. */
    fun display(percent: String): String {
        val text = normalize(percent) ?: return percent
        return text.removeSuffix(".00").let { if ('.' in it) it.trimEnd('0') else it } + "%"
    }

    private const val WIDE = 9
}
