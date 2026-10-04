package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.BoundingBox
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.SourceKind
import kotlin.math.abs

/**
 * Puts an OCR read back into the rows a person sees.
 *
 * Neither engine returns rows. Vision returns each run of text it found — the
 * merchant, and separately the amount at the far right of the same row — and
 * ML Kit groups by *block*, so a table arrives as every merchant, then every
 * amount. Nothing downstream can pair them after that: the redactor looks for
 * a date and an amount on one line, and the parser reads lines.
 *
 * The positions say which fragments share a row, so this rebuilds them: sorted
 * top to bottom, a fragment whose middle sits within half a line of the row's
 * joins it, and a row reads left to right. Cells are kept apart by a wide gap
 * rather than run together, so `Loblaws` and `-$86.40` stay two columns. A
 * text-layer PDF is already in rows and passes through untouched.
 */
object OcrRows {

    private const val COLUMN_GAP = "   "

    fun of(document: ExtractedDocument): ExtractedDocument = if (document.source != SourceKind.OCR) {
        document
    } else {
        document.copy(pages = document.pages.map { it.copy(lines = rows(it.lines)) })
    }

    fun rows(lines: List<ExtractedLine>): List<ExtractedLine> {
        val placed = lines.filter { it.text.isNotBlank() }
        // No positions to go on: the order given is the best there is.
        if (placed.all { it.box == BoundingBox() }) return placed

        val rows = mutableListOf<MutableList<ExtractedLine>>()
        for (line in placed.sortedBy { centre(it.box) }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, line)) row += line else rows += mutableListOf(line)
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.box.left }
            ExtractedLine(
                text = ordered.joinToString(COLUMN_GAP) { it.text.trim() },
                box = BoundingBox(
                    left = ordered.minOf { it.box.left },
                    top = ordered.minOf { it.box.top },
                    right = ordered.maxOf { it.box.right },
                    bottom = ordered.maxOf { it.box.bottom },
                ),
            )
        }
    }

    /**
     * Within half a line of the row's middle, measured by the shorter of the
     * two: a merchant and its amount share a middle to a pixel or two, while
     * the smaller "Groceries · Aug 14" printed under the merchant sits a whole
     * line lower and starts a row of its own.
     */
    private fun sameRow(row: List<ExtractedLine>, line: ExtractedLine): Boolean {
        val rowCentre = row.map { centre(it.box) }.average().toFloat()
        val rowHeight = row.map { height(it.box) }.average().toFloat()
        return abs(centre(line.box) - rowCentre) <= minOf(rowHeight, height(line.box)) / 2
    }

    private fun centre(box: BoundingBox): Float = (box.top + box.bottom) / 2

    private fun height(box: BoundingBox): Float = (box.bottom - box.top).coerceAtLeast(1f)
}
