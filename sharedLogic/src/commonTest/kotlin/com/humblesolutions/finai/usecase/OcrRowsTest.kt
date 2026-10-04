package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import kotlin.test.Test
import kotlin.test.assertEquals

class OcrRowsTest {

    private fun texts(document: ExtractedDocument) = OcrRows.of(document).pages.single().lines.map { it.text }

    @Test
    fun a_merchant_and_its_amount_become_one_row_whatever_order_they_arrived_in() {
        val rows = texts(ScreenshotFixture.document())

        assertEquals("Loblaws   -$86.40", rows.single { it.startsWith("Loblaws") })
        assertEquals("Payroll Acme Corp   +$2,600.00", rows.single { it.startsWith("Payroll") })
        assertEquals("Uber trip   -$42.10", rows.single { it.startsWith("Uber") })
    }

    @Test
    fun rows_read_top_to_bottom_and_a_smaller_line_underneath_stays_its_own() {
        val rows = texts(ScreenshotFixture.document())

        assertEquals(
            listOf("6:01   59", "Chequing 06012-5004321", "Aug 14", "Loblaws   -$86.40", "Groceries"),
            rows.take(5),
        )
    }

    @Test
    fun a_text_layer_pdf_is_left_exactly_as_it_was() {
        val pdf = ScreenshotFixture.document().copy(source = com.humblesolutions.finai.model.SourceKind.PDF_TEXT)
        assertEquals(pdf, OcrRows.of(pdf))
    }

    @Test
    fun lines_with_no_positions_keep_the_order_given() {
        val lines = listOf(ExtractedLine("b"), ExtractedLine("a"))
        assertEquals(listOf("b", "a"), OcrRows.rows(lines).map { it.text })
    }

    @Test
    fun blank_fragments_are_dropped() {
        val document = ScreenshotFixture.document(lines = ScreenshotFixture.leftColumn + ExtractedLine(" "))
        assertEquals(ScreenshotFixture.leftColumn.size, OcrRows.of(document).pages.single().lines.size)
    }
}
