package com.humblesolutions.finai.model

/**
 * A statement as the device read it, before anything is redacted.
 *
 * **This never leaves the phone.** It holds the document's own words — the name
 * and address at the top of page 1 included — and exists only long enough for
 * [com.humblesolutions.finai.usecase.StatementRedactor] to turn it into
 * something that can be sent (PRD F2, 2026-09-21).
 *
 * Lines carry their [BoundingBox] because a statement is a table, and reading
 * order alone loses which column a number was in. Nothing uses the coordinates
 * yet; they are kept because throwing them away here means re-reading the whole
 * document to get them back.
 */
data class ExtractedDocument(
    val pages: List<ExtractedPage> = emptyList(),
    val source: SourceKind = SourceKind.PDF_TEXT,
    /**
     * A photo or screenshot, rather than a PDF. Always [SourceKind.OCR], but
     * not every OCR read is one: a scanned PDF is a statement, with a header
     * and a period; a screenshot of a banking app is a list, with neither.
     */
    val fromImage: Boolean = false,
) {
    val isEmpty: Boolean get() = pages.all { it.lines.isEmpty() }

    /** Every line, page by page — the order a person reads them in. */
    fun allLines(): List<ExtractedLine> = pages.flatMap { it.lines }
}

data class ExtractedPage(
    /** Zero-based. Page 1 is special: its header block is where the name lives. */
    val index: Int = 0,
    val lines: List<ExtractedLine> = emptyList(),
)

data class ExtractedLine(
    val text: String = "",
    val box: BoundingBox = BoundingBox(),
)

/** Page coordinates, origin top-left, in the page's own units. */
data class BoundingBox(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/**
 * How the text was obtained, which is worth recording.
 *
 * [PDF_TEXT] is the document's own text layer: exact, and what a statement
 * downloaded from a bank actually contains. [OCR] is a reading of pixels and
 * can be wrong in ways that look entirely plausible. When a parse comes back
 * strange, this is the first thing to check — and the backend stores it for
 * that reason.
 */
enum class SourceKind(val wire: String) {
    PDF_TEXT("pdf_text"),
    OCR("ocr"),
}
