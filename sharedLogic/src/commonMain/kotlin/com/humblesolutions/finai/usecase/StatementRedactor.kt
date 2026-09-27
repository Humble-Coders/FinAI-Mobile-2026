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
 * Both directions are failures. Leaving an account number in breaks a promise;
 * dropping a transaction line means the user's statement imports short and
 * nothing says so. The tests assert both.
 */
object StatementRedactor {

    // Seven digits is long enough to be an account or card number and long
    // enough that no amount reaches it — `1234.56` is six.
    private val LONG_DIGIT_RUN = Regex("""(?:\d[ -]?){6,}\d""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val PHONE =
        Regex("""\b(?:\+?1[ -]?)?(?:\(\d{3}\)|\d{3})[ -]\d{3}[ -]\d{4}\b""")

    // The trailing boundary matters: without it this also matches the `P3A4B5`
    // inside `SPOTIFY P3A4B5C6`, which is a transaction, not an address.
    private val POSTAL_CODE = Regex("""\b[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d\b""")

    private val AMOUNT = Regex("""\d[\d,]*\.\d{2}""")

    // A date that need not carry a year: `14 Aug`, `AUG 14`, `08/14`. `\s?`
    // rather than `\s*` on purpose — a word and a number separated by a column
    // of spaces is "Opening balance      2,184.63", not a dated amount.
    private val DATE =
        Regex("""\b(\d{1,2}[/-]\d{1,2}|\d{1,2}\s?[A-Za-z]{3,}|[A-Za-z]{3,}\s?\d{1,2})\b""")

    /** Whether a line reads as a transaction: a date and an amount together. */
    fun looksLikeATransaction(line: String): Boolean =
        AMOUNT.containsMatchIn(line) && DATE.containsMatchIn(line)

    /**
     * The text that goes on the wire, and nothing else.
     *
     * Call [StatementPeriod.find] on the same document **first** — the header
     * block this drops is where the year is.
     */
    fun redact(document: ExtractedDocument): String {
        val kept = mutableListOf<String>()

        for (page in document.pages) {
            val lines = page.lines.map { it.text }
            // Page 1 only: everything above the first transaction is the block
            // holding the name and address. We never learn the user's name at
            // signup, so there is nothing to match on — position is the only
            // signal there is. Later pages repeat a short bank header, which the
            // identifier rules below handle on their own.
            val start =
                if (page.index == 0) lines.indexOfFirst { looksLikeATransaction(it) }
                else 0

            if (start < 0) continue

            for (line in lines.drop(start)) {
                if (EMAIL.containsMatchIn(line)) continue
                if (PHONE.containsMatchIn(line)) continue
                if (POSTAL_CODE.containsMatchIn(line)) continue
                kept += maskLongDigits(line)
            }
        }
        return kept.joinToString("\n").trim()
    }

    /** `06012-5004321` becomes `••••4321`: enough to recognise, not to use. */
    private fun maskLongDigits(line: String): String =
        LONG_DIGIT_RUN.replace(line) { match ->
            val digits = match.value.filter { it.isDigit() }
            "••••" + digits.takeLast(4)
        }
}
