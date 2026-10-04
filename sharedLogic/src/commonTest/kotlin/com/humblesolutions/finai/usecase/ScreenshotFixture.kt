package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.BoundingBox
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.SourceKind

/**
 * A banking app's transaction list as OCR hands it over: fragments, not rows,
 * and in ML Kit's order — the left column top to bottom, then the right. Dates
 * head each day; the rows under them carry a merchant and an amount, no date.
 */
internal object ScreenshotFixture {

    private fun at(text: String, left: Float, top: Float, height: Float = 22f) = ExtractedLine(text = text, box = BoundingBox(left = left, top = top, right = left + text.length * 11f, bottom = top + height))

    val leftColumn = listOf(
        at("6:01", 40f, 18f),
        at("Chequing 06012-5004321", 40f, 110f),
        at("Aug 14", 40f, 260f, 18f),
        at("Loblaws", 40f, 300f),
        at("Groceries", 40f, 326f, 16f),
        at("Payroll Acme Corp", 40f, 370f),
        at("Aug 13", 40f, 440f, 18f),
        at("Uber trip", 40f, 480f),
    )

    val rightColumn = listOf(
        at("59", 330f, 18f),
        at("-$86.40", 300f, 301f),
        at("+$2,600.00", 280f, 371f),
        at("-$42.10", 300f, 479f),
    )

    fun document(fromImage: Boolean = true, lines: List<ExtractedLine> = leftColumn + rightColumn) = ExtractedDocument(
        pages = listOf(ExtractedPage(index = 0, lines = lines)),
        source = SourceKind.OCR,
        fromImage = fromImage,
    )
}
