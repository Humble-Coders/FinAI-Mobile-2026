package com.humblesolutions.finai.usecase

/**
 * Whether a statement is plainly in a currency other than its account's.
 *
 * Every row an import saves is recorded in the account's currency — the
 * parser returns a number, not a currency — so a rupee receipt imported into
 * a dollar account becomes dollars: `₹70` saved as `$70.00`, a wrong figure
 * that looks right. This catches it before anything is sent.
 *
 * Only marks that name one currency count. `$` is CAD, USD and a dozen
 * others, so it says nothing and is never a reason to refuse.
 */
object StatementCurrency {

    private val MARKS = listOf(
        Regex("""₹|\bRs\.?\s?\d|\bINR\b""", RegexOption.IGNORE_CASE) to "INR",
        Regex("""€|\bEUR\b""") to "EUR",
        Regex("""£|\bGBP\b""") to "GBP",
    )

    /** The other currency [text] is written in, or null when nothing says so. */
    fun foreign(text: String, accountCurrency: String?): String? {
        val own = accountCurrency?.trim()?.uppercase().orEmpty()
        if (own.isEmpty()) return null
        return MARKS.firstOrNull { (mark, code) -> code != own && mark.containsMatchIn(text) }?.second
    }
}
