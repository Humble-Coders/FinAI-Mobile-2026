package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedCell
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.SourceKind

/**
 * Which column of a statement's table each amount was printed in.
 *
 * A statement with separate Withdrawals and Deposits columns loses them the
 * moment it becomes text: `14 Aug  UPI-SWIGGY  450.00  12,340.50` says nothing
 * about which column `450.00` sat under, the empty cell beside it disappears,
 * and the heading that named the columns sits in the block above the first
 * transaction that [StatementRedactor] drops with the name and address. The
 * reader was left to guess from the description, and money out came back as
 * money in.
 *
 * Two things fix that, both decided here so the apps cannot disagree:
 *
 * - [find] reads the heading line **before** redaction and returns only its
 *   column names — words from a fixed vocabulary, never anything else on the
 *   line — to go on the wire as `statement_columns`.
 * - [tag] marks each amount with the column it sits under, by position:
 *   `450.00 [out]`, `52,000.00 [in]`, `12,340.50 [balance]`. The server's
 *   reader is told a tag outranks everything else.
 *
 * **Conservative by design.** A wrong tag is worse than none, because it is
 * trusted: an amount is tagged only when it sits clearly under one heading
 * and not another. Anything unclear is left as it was.
 */
object StatementColumns {

    /** What a column holds, and the tag its amounts get. */
    enum class Kind(val tag: String?) {
        OUT("out"),
        IN("in"),
        BALANCE("balance"),
        OTHER(null),
    }

    /** One heading and where it sits across the page. */
    data class Column(val label: String, val kind: Kind, val left: Float, val right: Float) {
        val centre: Float get() = (left + right) / 2
        val width: Float get() = (right - left).coerceAtLeast(1f)
    }

    /** A table's headings, left to right. */
    data class Layout(val columns: List<Column>) {
        /** What goes on the wire: "Date | Details | Withdrawals | Deposits | Balance". */
        val label: String
            get() = columns.map { it.label }.filter { it.isNotEmpty() }.joinToString(" | ").take(MAX_LABEL)

        /** Whether any heading says which way money went or where the balance is. */
        val tells: Boolean get() = columns.any { it.kind.tag != null }
    }

    private const val MAX_LABEL = 200

    // Spaces inside one heading ("Withdrawal Amt.") are narrower than the gap
    // between two headings; a gap wider than this many characters starts a
    // new one. Only for a PDF's text layer, which gives words — OCR already
    // returns each heading as its own fragment.
    private const val HEADING_GAP_CHARS = 1.5f

    private val OUT_WORDS = setOf(
        "withdrawal", "withdrawals", "withdrawn", "debit", "debits", "dr", "out", "charges", "purchases", "spent",
        "retrait", "retraits", "débit", "débits",
    )
    private val IN_WORDS = setOf(
        "deposit", "deposits", "credit", "credits", "cr", "in", "received",
        "dépôt", "dépôts", "crédit", "crédits",
    )
    private val BALANCE_WORDS = setOf("balance", "solde")

    // Everything else a heading may say. A line with any word outside the
    // three sets and this one is not a heading — and nothing from it is sent.
    private val OTHER_WORDS = setOf(
        "date", "value", "posting", "post", "posted", "trans", "transaction", "transactions", "txn", "dt",
        "description", "details", "particulars", "narration", "reference", "ref", "no", "number",
        "chq", "cheque", "check", "type", "merchant", "activity", "category", "location", "mode", "remarks",
        "sr", "s", "amount", "amt", "paid", "money", "closing", "opening", "running", "available",
        "of", "and", "the", "to", "from", "cad", "inr", "usd", "rs",
        "opération", "opérations", "détails", "montant", "de", "du", "des", "la", "le",
    )
    private val ALLOWED = OUT_WORDS + IN_WORDS + BALANCE_WORDS + OTHER_WORDS

    private val NOT_A_LETTER = Regex("""[^\p{L}]+""")
    private val LABEL_DROP = Regex("""[^\p{L} .,&/()'-]""")
    private val SPACES = Regex("""\s+""")

    // A whole cell that is an amount: optional sign, symbol or brackets, the
    // figure grouped in threes or the Indian way, cents, and an optional
    // CR / DR.
    private val AMOUNT = Regex(
        """^\(?[-−]?(?:[$₹]|Rs\.?)?\s?(?:\d{1,2}(?:,\d{2})+,\d{3}|\d{1,3}(?:,\d{3})*|\d+)\.\d{2}\)?-?(?:\s?(?:CR|DR|Cr|Dr|cr|dr)\.?)?$""",
    )

    /** The first table heading in the document, or null when there is none. */
    fun find(document: ExtractedDocument): Layout? = document.pages
        .asSequence()
        .flatMap { it.lines }
        .firstNotNullOfOrNull { headingOf(it, document.source) }

    /**
     * The document with each amount that sits clearly under a heading tagged
     * with that column. A heading applies to the lines below it on its page —
     * and, for a PDF's text layer, to the next pages until another appears;
     * a photo of each page is its own picture, at its own scale.
     */
    fun tag(document: ExtractedDocument): ExtractedDocument {
        var carried: Layout? = null
        val pages = document.pages.map { page ->
            var layout: Layout? = if (document.source == SourceKind.PDF_TEXT) carried else null
            val lines = page.lines.map { line ->
                val heading = headingOf(line, document.source)
                if (heading != null) {
                    layout = heading
                    carried = heading
                    line
                } else {
                    layout?.takeIf { it.tells }?.let { tagged(line, it) } ?: line
                }
            }
            page.copy(lines = lines)
        }
        return document.copy(pages = pages)
    }

    /** The headings on [line], or null when it is not a table heading. */
    internal fun headingOf(line: ExtractedLine, source: SourceKind): Layout? {
        if (line.cells.size < 2 || line.text.any { it.isDigit() }) return null
        val phrases = if (source == SourceKind.OCR) line.cells.sortedBy { it.left } else phrases(line.cells)
        if (phrases.size < 2) return null
        val columns = phrases.map { cell ->
            val words = cell.text.lowercase().split(NOT_A_LETTER).filter { it.isNotEmpty() }
            if (words.isEmpty() || words.any { it !in ALLOWED }) return null
            Column(label = cleanLabel(cell.text), kind = kindOf(words), left = cell.left, right = cell.right)
        }
        // A heading names at least one money column: a direction, a balance,
        // or a single amount column.
        val money = columns.any { column ->
            column.kind != Kind.OTHER || column.label.lowercase().split(NOT_A_LETTER).any { it == "amount" || it == "amt" || it == "montant" }
        }
        return if (money) Layout(columns) else null
    }

    private fun kindOf(words: List<String>): Kind {
        if (words.any { it in BALANCE_WORDS }) return Kind.BALANCE
        val out = words.any { it in OUT_WORDS }
        val into = words.any { it in IN_WORDS }
        return when {
            out && !into -> Kind.OUT

            into && !out -> Kind.IN

            // "Debit/Credit": one column, either way — its amounts say which.
            else -> Kind.OTHER
        }
    }

    /** A PDF's words joined back into headings, by the gaps between them. */
    private fun phrases(words: List<ExtractedCell>): List<ExtractedCell> {
        val sorted = words.filter { it.text.isNotBlank() }.sortedBy { it.left }
        if (sorted.isEmpty()) return emptyList()
        val charWidth = sorted.sumOf { (it.right - it.left).toDouble() }.toFloat() /
            sorted.sumOf { it.text.length }.coerceAtLeast(1)
        val gap = charWidth * HEADING_GAP_CHARS
        val out = mutableListOf(sorted.first())
        for (word in sorted.drop(1)) {
            val last = out.last()
            if (word.left - last.right <= gap) {
                out[out.lastIndex] = ExtractedCell("${last.text} ${word.text}", last.left, maxOf(last.right, word.right))
            } else {
                out += word
            }
        }
        return out
    }

    private fun cleanLabel(text: String): String = text.replace(LABEL_DROP, " ").replace(SPACES, " ").trim()

    /** [line] with each amount that clearly sits under a telling heading tagged. */
    private fun tagged(line: ExtractedLine, layout: Layout): ExtractedLine {
        val tags = line.cells.mapNotNull { cell ->
            val text = cell.text.trim()
            if (!AMOUNT.matches(text)) return@mapNotNull null
            columnOf(cell, layout)?.kind?.tag?.let { text to it }
        }
        if (tags.isEmpty()) return line
        val out = StringBuilder()
        var cursor = 0
        for ((amount, tag) in tags) {
            val at = occurrence(line.text, amount, cursor) ?: continue
            val end = at + amount.length
            out.append(line.text, cursor, end).append(" [").append(tag).append(']')
            cursor = end
        }
        out.append(line.text.substring(cursor))
        return line.copy(text = out.toString())
    }

    /** Where [amount] stands on its own in [text] from [from], not inside a longer figure. */
    private fun occurrence(text: String, amount: String, from: Int): Int? {
        var at = text.indexOf(amount, from)
        while (at >= 0) {
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + amount.length)
            val alone = (before == null || !(before.isDigit() || before == ',' || before == '.')) &&
                (after == null || !after.isDigit())
            if (alone) return at
            at = text.indexOf(amount, at + 1)
        }
        return null
    }

    /**
     * The heading [cell] sits under, or null when that is not clear. Clear
     * means it overlaps one heading at least twice as much as any other or,
     * overlapping none, its edge is within one heading's width of a single
     * nearest heading — figures are usually aligned to one edge of the
     * heading above them, not centred under it.
     */
    private fun columnOf(cell: ExtractedCell, layout: Layout): Column? {
        val overlaps = layout.columns
            .map { it to (minOf(cell.right, it.right) - maxOf(cell.left, it.left)).coerceAtLeast(0f) }
            .sortedByDescending { it.second }
        val (best, most) = overlaps.first()
        val second = overlaps.getOrNull(1)?.second ?: 0f
        if (most > 0f) return best.takeIf { most >= second * 2 }

        val distances = layout.columns
            .map { it to minOf(kotlin.math.abs(cell.right - it.right), kotlin.math.abs(cell.left - it.left)) }
            .sortedBy { it.second }
        val (nearest, distance) = distances.first()
        val next = distances.getOrNull(1)?.second ?: Float.MAX_VALUE
        return nearest.takeIf { distance <= nearest.width && distance * 2 <= next }
    }
}
