package com.humblesolutions.finai.util

/**
 * What is wrong with an amount a person typed, if anything — the half of
 * every money field's blocking reason that does not depend on the field.
 *
 * Shared by the budget editor (#47) and goals (#52), which each map these to
 * their own reason and wording. It exists so the subtle part is written once:
 * [Money.normalize] declines `"500.999"` and `"five hundred"` with the same
 * null, and a person who typed one digit too many deserves to be told that,
 * not that they typed nonsense.
 */
object MoneyInput {

    enum class Problem {
        /** Nothing typed. */
        BLANK,

        /** A minus sign. Money fields here take amounts, not signed balances. */
        NEGATIVE,

        /** Not a figure at all. */
        NOT_MONEY,

        /** A real figure with more decimal places than the currency has. */
        TOO_PRECISE,
    }

    /**
     * A scale wide enough that anything typed by hand fits inside it, used to
     * tell "too many decimal places" apart from "not a number": a figure that
     * is money at this scale and not at the currency's had too much precision.
     */
    private const val WIDE_SCALE = 9

    /**
     * The problem with [raw] as an amount in [currency], or null when it is
     * an amount — zero included; whether zero is acceptable is the field's
     * call, not this one's.
     *
     * Negative is judged on the raw text, before [Money.normalize], which
     * treats a minus sign as "not money at all" — true, but useless next to a
     * field where the person has plainly just typed a minus.
     */
    fun problem(raw: String, currency: String): Problem? {
        if (raw.isBlank()) return Problem.BLANK
        if (raw.trim().startsWith("-")) return Problem.NEGATIVE
        if (Money.normalize(raw, Money.fractionDigits(currency)) != null) return null
        return if (isTooPrecise(raw)) Problem.TOO_PRECISE else Problem.NOT_MONEY
    }

    /**
     * Whether [raw] is a real figure that simply has too many decimal places —
     * as opposed to not being a figure at all. Decided by [Money] rather than
     * by counting characters: whether the dot in `"1.200"` is a decimal point
     * or a thousands separator is exactly the judgement [Money.normalize]
     * already makes, and a second opinion here would eventually disagree.
     * A currency with no cents is covered by the same question: `"60000.5"`
     * yen is money at a wider scale and not at zero.
     */
    private fun isTooPrecise(raw: String): Boolean = Money.normalize(raw, WIDE_SCALE) != null
}
