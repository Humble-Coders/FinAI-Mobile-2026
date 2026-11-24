package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.BoundingBox
import com.humblesolutions.finai.model.ExtractedCell
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatementColumnsTest {

    /** A line from cells placed at [x] positions, ten units per character. */
    private fun line(vararg cells: Pair<String, Float>, gap: String = "   "): ExtractedLine {
        val placed = cells.map { (text, x) -> ExtractedCell(text, x, x + text.length * 10f) }
        return ExtractedLine(text = placed.joinToString(gap) { it.text }, box = BoundingBox(), cells = placed)
    }

    /** A PDF text layer gives words; a heading of two words is two cells close together. */
    private fun words(vararg cells: Pair<String, Float>): ExtractedLine = line(*cells, gap = " ")

    private fun document(vararg lines: ExtractedLine, source: SourceKind = SourceKind.PDF_TEXT) = ExtractedDocument(pages = listOf(ExtractedPage(index = 0, lines = lines.toList())), source = source)

    private fun texts(document: ExtractedDocument) = StatementColumns.tag(document).pages.flatMap { page -> page.lines.map { it.text } }

    // Date at 0, Details at 100, Withdrawals at 400, Deposits at 560, Balance at 700.
    private val heading = words("Date" to 0f, "Details" to 100f, "Withdrawals" to 400f, "Deposits" to 560f, "Balance" to 700f)

    @Test
    fun the_heading_is_found_and_only_its_column_names_are_kept() {
        val layout = StatementColumns.find(document(heading))
        assertEquals("Date | Details | Withdrawals | Deposits | Balance", layout?.label)
    }

    @Test
    fun a_two_word_heading_stays_one_column() {
        val hdfc = words(
            "Date" to 0f,
            "Narration" to 80f,
            "Withdrawal" to 400f,
            "Amt." to 505f,
            "Deposit" to 600f,
            "Amt." to 675f,
            "Closing" to 800f,
            "Balance" to 875f,
        )
        assertEquals("Date | Narration | Withdrawal Amt. | Deposit Amt. | Closing Balance", StatementColumns.find(document(hdfc))?.label)
    }

    @Test
    fun amounts_are_tagged_with_the_column_they_sit_under() {
        val doc = document(
            heading,
            line("14 Aug" to 0f, "UPI-SWIGGY" to 100f, "450.00" to 450f, "12,340.50" to 700f),
            line("15 Aug" to 0f, "NEFT SALARY" to 100f, "52,000.00" to 560f, "64,340.50" to 700f),
        )
        assertEquals(
            listOf(
                heading.text,
                "14 Aug   UPI-SWIGGY   450.00 [out]   12,340.50 [balance]",
                "15 Aug   NEFT SALARY   52,000.00 [in]   64,340.50 [balance]",
            ),
            texts(doc),
        )
    }

    @Test
    fun a_figure_between_two_headings_is_left_alone() {
        // 495..575 overlaps Withdrawals (400..510) and Deposits (560..640) by 15 each.
        val doc = document(heading, line("14 Aug" to 0f, "SHOP" to 100f, "1,234.56" to 495f))
        assertEquals("14 Aug   SHOP   1,234.56", texts(doc)[1])
    }

    @Test
    fun a_single_amount_column_tags_nothing_but_the_balance() {
        val single = words("Date" to 0f, "Description" to 100f, "Amount" to 500f, "Balance" to 700f)
        val doc = document(single, line("14 Aug" to 0f, "SHOP" to 100f, "-86.40" to 500f, "1,000.00" to 700f))
        assertEquals("14 Aug   SHOP   -86.40   1,000.00 [balance]", texts(doc)[1])
        assertEquals("Date | Description | Amount | Balance", StatementColumns.find(doc)?.label)
    }

    @Test
    fun a_line_with_anything_else_on_it_is_not_a_heading() {
        assertNull(StatementColumns.find(document(words("Jane" to 0f, "Doe" to 50f, "Balance" to 400f))))
        assertNull(StatementColumns.find(document(words("Account" to 0f, "0042" to 100f, "Balance" to 400f))))
        assertNull(StatementColumns.find(document(words("Date" to 0f, "Description" to 100f))), "no money column")
    }

    @Test
    fun nothing_is_tagged_without_a_heading() {
        val doc = document(line("14 Aug" to 0f, "SHOP" to 100f, "450.00" to 450f))
        assertEquals("14 Aug   SHOP   450.00", texts(doc).single())
    }

    @Test
    fun the_heading_carries_to_the_next_page_of_a_pdf_but_not_of_a_photo() {
        fun twoPages(source: SourceKind) = ExtractedDocument(
            pages = listOf(
                ExtractedPage(index = 0, lines = listOf(if (source == SourceKind.OCR) ocrHeading else heading)),
                ExtractedPage(index = 1, lines = listOf(line("16 Aug" to 0f, "ATM" to 100f, "2,000.00" to 420f))),
            ),
            source = source,
        )
        assertEquals("16 Aug   ATM   2,000.00 [out]", texts(twoPages(SourceKind.PDF_TEXT))[1])
        assertEquals("16 Aug   ATM   2,000.00", texts(twoPages(SourceKind.OCR))[1])
    }

    private val ocrHeading = line("Date" to 0f, "Details" to 100f, "Withdrawals" to 400f, "Deposits" to 560f, "Balance" to 700f)

    @Test
    fun ocr_fragments_are_headings_as_they_come() {
        val combined = line("Date" to 0f, "Debit/Credit" to 400f, "Balance" to 700f)
        val layout = StatementColumns.find(document(combined, source = SourceKind.OCR))
        assertEquals(
            listOf(StatementColumns.Kind.OTHER, StatementColumns.Kind.OTHER, StatementColumns.Kind.BALANCE),
            layout?.columns?.map { it.kind },
        )
    }

    @Test
    fun cr_and_dr_amounts_are_tagged_too() {
        val doc = document(heading, line("14 Aug" to 0f, "REFUND" to 100f, "86.40 Cr" to 560f))
        assertEquals("14 Aug   REFUND   86.40 Cr [in]", texts(doc)[1])
    }
}
