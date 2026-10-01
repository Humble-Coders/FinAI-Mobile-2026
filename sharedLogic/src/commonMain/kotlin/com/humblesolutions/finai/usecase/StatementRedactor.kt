package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument

/**
 * Turns a statement the device read into the only thing we are willing to send.
 *
 * The product's central claim is that the document never leaves the phone (PRD
 * F2, 2026-09-21). What leaves is this function's output: the transactions,
 * without the identifiers around them.
 *
 * **Shared, not per-platform, and that is deliberate.** Reading a PDF is a
 * native SDK call; deciding what counts as an identifier is a decision. Two
 * implementations of a decision drift, and this drift would be invisible until
 * somebody's address reached a model.
 *
 * Three ways to fail, and the tests assert all three. Leaving an account number
 * in breaks a promise. Dropping a transaction line means the statement imports
 * short and nothing says so. **Altering an amount is the worst of the three** —
 * a wrong number that looks right is not caught by anyone downstream.
 */
object StatementRedactor {

    // Five digits is the bar, because that is what the wire is checked
    // against: nothing longer than four digits may survive. A hyphen inside a
    // token is allowed (`06012-5004321`); a SPACE is not, and that is the
    // whole point — `(?:\d[ -]?){6,}\d` once matched `1234567 10` in
    // `CHQ 1234567 10.99` and rewrote the amount as 6710.99.
    private val TOKEN_DIGIT_RUN = Regex("""\d(?:-?\d){4,}""")

    // A number written in spaced groups: a card as 4-4-4-4, a SIN as 3-3-3.
    // Three groups at least, each three to five digits — a cheque number
    // beside its amount is two groups and the second is two digits, so it can
    // never qualify. Narrowing this to four-digit groups once stopped SIN-
    // shaped numbers being masked at all.
    private val GROUPED_DIGIT_RUN = Regex("""\b\d{3,5}(?:[ -]\d{3,5}){2,}\b""")

    // What counts as an amount for the purpose of leaving it alone: a WHOLE
    // token, with at most six digits before the point. Both bounds matter.
    // Unbounded, `1234567.89` reads as an amount and a seven-digit identifier
    // goes out untouched; un-anchored, `06012-5004321.00` has its tail read as
    // an amount and only the `06012` gets masked.
    private val AMOUNT_TOKEN =
        Regex("""\(?-?\$?(?:\d{1,3}(?:,\d{3})*|\d{1,6})\.\d{2}\)?-?""")

    private val TOKEN = Regex("""\S+""")

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val PHONE =
        Regex("""\b(?:\+?1[ -]?)?(?:\(\d{3}\)|\d{3})[ -]\d{3}[ -]\d{4}\b""")

    // The trailing boundary matters: without it this also matches the `A4B5C6`
    // inside `SPOTIFY P3A4B5C6`, which is a transaction, not an address.
    private val POSTAL_CODE = Regex("""\b[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d\b""")

    private val AMOUNT = Regex("""\d[\d,]*\.\d{2}""")

    // A date that need not carry a year: `14 Aug`, `AUG 14`, `08/14`. `\s?`
    // rather than `\s*` on purpose — a word and a number separated by a column
    // of spaces is "Opening balance      2,184.63", not a dated amount.
    private val DATE =
        Regex("""\b(\d{1,2}[/-]\d{1,2}|\d{1,2}\s?[A-Za-z]{3,}|[A-Za-z]{3,}\s?\d{1,2})\b""")

    private const val MASK = "••••"

    /** Whether a line reads as a transaction: a date and an amount together. */
    fun looksLikeATransaction(line: String): Boolean = AMOUNT.containsMatchIn(line) && DATE.containsMatchIn(line)

    /**
     * What redaction produced, and what it cost.
     *
     * [droppedLines] exists because every other outcome of this function is
     * visible and a dropped line is not: the import simply comes up short. A
     * count is safe to log — it is not text (Appendix A.5 #7).
     */
    data class Redaction(
        val text: String = "",
        val droppedLines: Int = 0,
    )

    /**
     * The text that goes on the wire, and nothing else.
     *
     * Call [StatementPeriod.find] on the same document **first** — the header
     * block this drops is where the year is.
     */
    fun redact(document: ExtractedDocument): String = of(document).text

    /** [redact], plus the count of what it threw away. */
    fun of(document: ExtractedDocument): Redaction {
        val kept = mutableListOf<String>()
        var dropped = 0

        for (page in document.pages) {
            val lines = page.lines.map { it.text }
            // Page 1 only: everything above the first transaction is the block
            // holding the name and address. We never learn the user's name at
            // signup, so there is nothing to match on — position is the only
            // signal there is. Later pages repeat a short bank header, which the
            // identifier rules below handle on their own.
            val start =
                if (page.index == 0) {
                    lines.indexOfFirst { looksLikeATransaction(it) }
                } else {
                    0
                }

            if (start < 0) {
                dropped += lines.size
                continue
            }
            dropped += start

            for (line in lines.drop(start)) {
                // A transaction line is never dropped for carrying an
                // identifier — an e-transfer names an email, and a merchant
                // reference can be shaped exactly like a postal code. Losing
                // the row hides money; masking the identifier does not.
                if (!looksLikeATransaction(line) && carriesAnIdentifier(line)) {
                    dropped++
                    continue
                }
                kept += mask(line)
            }
        }
        return Redaction(text = kept.joinToString("\n").trim(), droppedLines = dropped)
    }

    private fun carriesAnIdentifier(line: String): Boolean = EMAIL.containsMatchIn(line) ||
        PHONE.containsMatchIn(line) ||
        POSTAL_CODE.containsMatchIn(line)

    /** `06012-5004321` becomes `••••4321`: enough to recognise, not to use. */
    private fun mask(line: String): String {
        // Anything spanning spaces goes first, while the spaces are still
        // there: an email, a phone number, a postal code, a grouped account or
        // card number.
        var masked = EMAIL.replace(line, MASK)
        masked = PHONE.replace(masked, MASK)
        masked = POSTAL_CODE.replace(masked, MASK)
        masked = GROUPED_DIGIT_RUN.replace(masked) { lastFour(it.value) }

        // Then token by token, so that an amount is recognised as a whole word
        // and everything else is masked. Deciding this per token is what keeps
        // a digit run from reaching across a space into the next column and
        // rewriting the amount there — `CHQ 1234567 10.99` once became
        // `CHQ ••••6710.99`, turning $10.99 into $6710.99.
        //
        // Per token rather than by regex lookaround on purpose: Kotlin/Native's
        // regex engine does not support lookbehind the way the JVM's does, and
        // CI builds iOS without running the shared tests on it, so a lookaround
        // that passed here could fail only on a user's phone.
        return TOKEN.replace(masked) { token ->
            if (AMOUNT_TOKEN.matches(token.value)) {
                token.value
            } else {
                TOKEN_DIGIT_RUN.replace(token.value) { lastFour(it.value) }
            }
        }
    }

    private fun lastFour(run: String): String = MASK + run.filter { it.isDigit() }.takeLast(4)
}
