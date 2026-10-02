package com.humblesolutions.finai.util

/**
 * Money as a **decimal string**, end to end (PRD §4.4, CLAUDE.md → Money).
 *
 * Never `Double`, never cents in client code: the API carries decimal strings
 * and the backend alone converts to minor units. Everything here is string
 * arithmetic, so nothing can drift by a rounding step.
 *
 * What counts as money is deliberately narrow — the server refuses anything
 * else, and a client that accepts more only produces a rejection the user
 * cannot act on. What counts as *typing* is deliberately broad: people paste
 * "1,200", type a stray space, or use a comma for the decimal point.
 */
object Money {

    /** The scale Postgres stores amounts at: more digits than this cannot round-trip. */
    const val MAX_WHOLE_DIGITS = 12

    /** Currencies whose smallest unit is the unit itself, so no decimals are shown. */
    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "CLP", "ISK", "XOF", "XAF", "XPF")

    /** How many decimal places [currency] is written with. */
    fun fractionDigits(currency: String): Int = if (currency.uppercase() in ZERO_DECIMAL) 0 else 2

    /**
     * What was typed, as the API wants it — `"1,200"` becomes `"1200.00"` — or
     * null when it is not money at all.
     *
     * Blank is null rather than zero: an empty optional field means the
     * question was not answered, and zero is an answer (#29).
     */
    fun normalize(raw: String, fractionDigits: Int = 2): String? {
        val cleaned = raw.trim().replace("\u00A0", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (cleaned.any { it !in '0'..'9' && it != '.' && it != ',' }) return null

        val separators = cleaned.indices.filter { cleaned[it] == '.' || cleaned[it] == ',' }
        if (separators.isEmpty()) return assemble(cleaned, "", fractionDigits)

        // Every separator but the last has to be grouping three digits, and the
        // run before the first has to be a group too, or this is not a number.
        separators.zipWithNext().forEach { (left, right) -> if (right - left - 1 != 3) return null }
        if (separators.size > 1 && cleaned.take(separators.first()).length !in 1..3) return null

        val last = separators.last()
        val after = cleaned.length - last - 1
        return when {
            // "1,200." — nothing follows, so nothing was meant by it.
            after == 0 -> null

            // Few enough digits follow to be the decimal part, whichever
            // character was used: people type both.
            after <= fractionDigits -> assemble(cleaned.take(last), cleaned.drop(last + 1), fractionDigits)

            // "1,200" groups thousands; "1.234" could be either that or too many
            // decimal places, and a wrong guess there is out by a thousand — so
            // it is refused and the person can say which they meant.
            after == 3 && cleaned[last] == ',' -> assemble(cleaned, "", fractionDigits)

            after == 3 && separators.size > 1 -> assemble(cleaned, "", fractionDigits)

            else -> null
        }
    }

    /** Digits in, canonical decimal string out, or null when it cannot be stored. */
    private fun assemble(whole: String, fraction: String, fractionDigits: Int): String? {
        if (fraction.any { !it.isDigit() }) return null
        val digits = whole.filter { it.isDigit() }
        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        if (trimmed.length > MAX_WHOLE_DIGITS) return null
        if (fractionDigits == 0) return trimmed
        return trimmed + "." + fraction.padEnd(fractionDigits, '0').take(fractionDigits)
    }

    /** Whether [raw] is something this app may send as an amount. */
    fun isMoney(raw: String, fractionDigits: Int = 2): Boolean = normalize(raw, fractionDigits) != null

    /**
     * `a + b`, both as decimal strings and the answer as one — the only
     * arithmetic this app does on money.
     *
     * Digit-by-digit on the normalized strings, because that is the one way
     * this cannot drift: `0.1 + 0.2` in binary floating point is not `0.3`, and
     * a financial app that shows `0.30000000000000004` has lost the user.
     * Anything that is not money reads as `"0"`, so a half-typed row adds
     * nothing rather than breaking the total.
     *
     * Null when either side is not money at this scale. A total must never
     * quietly come out smaller because one figure could not be read — a wrong
     * total is worse than no total, because nobody checks it.
     *
     * For display only. A sum wider than [MAX_WHOLE_DIGITS] is beyond what the
     * server stores, and no sum of real balances reaches it.
     */
    fun add(a: String, b: String, fractionDigits: Int = 2): String? {
        val left = normalize(a, fractionDigits) ?: return null
        val right = normalize(b, fractionDigits) ?: return null
        // Both are normalized to the same scale, so dropping the point leaves
        // two integers of the same units that can simply be added.
        val sum = addDigits(left.filter { it.isDigit() }, right.filter { it.isDigit() })
        if (fractionDigits == 0) return sum
        val padded = sum.padStart(fractionDigits + 1, '0')
        return padded.dropLast(fractionDigits) + "." + padded.takeLast(fractionDigits)
    }

    /**
     * `a - b`, **signed**, as a decimal string — the one subtraction this app
     * does.
     *
     * Separate from [add] and deliberately signed, because the only thing that
     * needs it is a comparison between two months and one of them can be the
     * larger. Everything else here stays unsigned: a price, a balance and a
     * payment cannot go below zero, and a subtraction that clamped would turn
     * "£200 worse than last month" into "£0 worse", which is a wrong number
     * that reads as reassurance.
     *
     * Null when either side is not money at this scale, for [add]'s reason: a
     * figure that could not be read must not come out as a difference of zero.
     */
    fun difference(a: String, b: String, fractionDigits: Int = 2): String? {
        val leftSign = signOf(a, fractionDigits)
        val rightSign = signOf(b, fractionDigits)
        val left = magnitudeOf(a, fractionDigits) ?: return null
        val right = magnitudeOf(b, fractionDigits) ?: return null

        // Opposite sides of zero: the magnitudes add, and the answer takes the
        // sign of whichever side `a` is on. Zero counts as the same side as
        // whatever it is compared with, so `0 - (-5)` is `5`.
        if (leftSign != 0 && rightSign != 0 && leftSign != rightSign) {
            val total = add(left, right, fractionDigits) ?: return null
            return if (leftSign < 0) "-$total" else total
        }

        val negative = leftSign < 0 || rightSign < 0
        val order = compare(left, right, fractionDigits)
        if (order == 0) return scaled("0", fractionDigits)
        val magnitude = if (order > 0) {
            subtractDigitsAt(left, right, fractionDigits)
        } else {
            subtractDigitsAt(right, left, fractionDigits)
        } ?: return null
        // On the negative side the larger magnitude is the smaller number, so
        // the result's sign flips relative to which magnitude won.
        val resultIsNegative = if (negative) order > 0 else order < 0
        return if (resultIsNegative) "-$magnitude" else magnitude
    }

    /** `larger - smaller`, both unsigned and normalized, re-pointed at [fractionDigits]. */
    private fun subtractDigitsAt(larger: String, smaller: String, fractionDigits: Int): String? {
        val left = normalize(larger, fractionDigits) ?: return null
        val right = normalize(smaller, fractionDigits) ?: return null
        val difference = subtractDigits(left.filter { it.isDigit() }, right.filter { it.isDigit() })
        if (fractionDigits == 0) return difference
        val padded = difference.padStart(fractionDigits + 1, '0')
        return padded.dropLast(fractionDigits) + "." + padded.takeLast(fractionDigits)
    }

    /** `"0"` written at [fractionDigits], e.g. `"0.00"`. */
    private fun scaled(raw: String, fractionDigits: Int): String = normalize(raw, fractionDigits) ?: raw

    /** Schoolbook subtraction, right to left. [a] must be the larger. */
    private fun subtractDigits(a: String, b: String): String {
        val width = maxOf(a.length, b.length)
        val left = a.padStart(width, '0')
        val right = b.padStart(width, '0')
        val digits = StringBuilder()
        var borrow = 0
        for (index in width - 1 downTo 0) {
            var step = (left[index] - '0') - (right[index] - '0') - borrow
            if (step < 0) {
                step += 10
                borrow = 1
            } else {
                borrow = 0
            }
            digits.append(('0' + step))
        }
        return digits.reverse().toString().trimStart('0').ifEmpty { "0" }
    }

    /** Schoolbook addition of two digit strings, right to left. */
    private fun addDigits(a: String, b: String): String {
        val width = maxOf(a.length, b.length)
        val left = a.padStart(width, '0')
        val right = b.padStart(width, '0')
        val digits = StringBuilder()
        var carry = 0
        for (index in width - 1 downTo 0) {
            val sum = (left[index] - '0') + (right[index] - '0') + carry
            digits.append(('0' + sum % 10))
            carry = sum / 10
        }
        if (carry > 0) digits.append(('0' + carry))
        return digits.reverse().toString().trimStart('0').ifEmpty { "0" }
    }

    /** Negative on a < b, zero on equal, positive on a > b. Normalized first, so `"7"` equals `"7.00"`. */
    fun compare(a: String, b: String, fractionDigits: Int = 2): Int {
        val left = normalize(a, fractionDigits) ?: "0"
        val right = normalize(b, fractionDigits) ?: "0"
        val leftWhole = left.substringBefore('.')
        val rightWhole = right.substringBefore('.')
        if (leftWhole.length != rightWhole.length) return leftWhole.length - rightWhole.length
        return left.compareTo(right)
    }

    /** True when [raw] is money and greater than nothing at all. */
    fun isPositive(raw: String, fractionDigits: Int = 2): Boolean = isMoney(raw, fractionDigits) && compare(raw, "0", fractionDigits) > 0

    /**
     * `-1` below zero, `0` at zero or unreadable, `1` above — the one place the
     * sign of an amount is decided.
     *
     * Everything else here is unsigned on purpose: a price, a balance and a
     * payment cannot be negative, and [normalize] rejects a leading `-` so a
     * stray one is caught rather than silently changing a total. But a month's
     * net genuinely can be below zero, so the sign has to be readable
     * somewhere, and that somewhere is here rather than re-derived per screen.
     *
     * Unreadable reads as `0`, for the same reason blank does elsewhere: a
     * figure we cannot parse must not be drawn as a loss.
     */
    fun signOf(raw: String, fractionDigits: Int = 2): Int {
        val trimmed = raw.trim()
        val negative = trimmed.startsWith('-')
        val magnitude = normalize(trimmed.removePrefix("-"), fractionDigits) ?: return 0
        if (compare(magnitude, "0", fractionDigits) == 0) return 0
        return if (negative) -1 else 1
    }

    /**
     * [raw] without its sign, or null when it is not money either way. The
     * companion to [signOf]: read the sign once, then work with the magnitude
     * through the unsigned functions above.
     */
    fun magnitudeOf(raw: String, fractionDigits: Int = 2): String? = normalize(raw.trim().removePrefix("-"), fractionDigits)

    /**
     * For display: `"1200.5"` in CAD becomes `"$1,200.50"`.
     *
     * The separators follow the language the server sent, not the device, so
     * two people in one household read the same figures the same way. Currencies
     * the table does not know are written with their code, which is honest and
     * never wrong.
     */
    fun format(amount: String, currency: String, locale: String = "en"): String {
        val digits = fractionDigits(currency)
        // The sign is carried around the formatting, not through it: grouping
        // "-1200" would put a separator inside the run the minus starts.
        // Leading in every locale this app ships — a trailing minus and
        // parentheses are accounting conventions, and neither is what somebody
        // reading their own month expects.
        val negative = signOf(amount, digits) < 0
        val normalized = magnitudeOf(amount, digits) ?: return ""
        val french = locale.take(2).lowercase() == "fr"
        val group = if (french) " " else ","
        val point = if (french) "," else "."

        val whole = normalized.substringBefore('.')
        val fraction = normalized.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(group).reversed()
        val body = if (fraction.isEmpty()) grouped else grouped + point + fraction

        val symbol = SYMBOLS[currency.uppercase()]
        val written = when {
            symbol == null -> body + " " + currency.uppercase()
            french -> body + " " + symbol
            else -> symbol + body
        }
        return if (negative) "-" + written else written
    }

    /**
     * What to draw beside an amount field, e.g. `"$"`. Currencies the table
     * does not know show their code, which is honest and never wrong.
     */
    fun symbol(currency: String): String = SYMBOLS[currency.uppercase()] ?: currency.uppercase()

    private val SYMBOLS = mapOf(
        "CAD" to "$",
        "USD" to "$",
        "EUR" to "€",
        "GBP" to "£",
        "INR" to "₹",
        "AUD" to "$",
        "NZD" to "$",
        "JPY" to "¥",
    )
}
