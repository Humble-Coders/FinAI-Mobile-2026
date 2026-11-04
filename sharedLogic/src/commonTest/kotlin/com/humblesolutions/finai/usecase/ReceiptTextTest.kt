package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptTextTest {

    private fun receipt(vararg lines: String) = ExtractedDocument(pages = listOf(ExtractedPage(0, lines.map { ExtractedLine(it) })))

    @Test
    fun `every line is kept with its amounts and the card number masked`() {
        val text = ReceiptText.of(receipt("THE COFFEE HOUSE", "Cappuccino 180.00", "Total 745.50", "Card 4111 1111 1111 1234", "Call 98765 43210"))

        assertTrue(text.startsWith("THE COFFEE HOUSE"), "the shop's name is not the person's")
        assertTrue(text.contains("Cappuccino 180.00"))
        assertTrue(text.contains("Total 745.50"))
        assertFalse(text.contains("4111 1111 1111"), text)
        assertFalse(text.contains("98765"), text)
    }
}
