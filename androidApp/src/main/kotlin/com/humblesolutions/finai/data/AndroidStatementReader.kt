package com.humblesolutions.finai.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.humblesolutions.finai.model.BoundingBox
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.SourceKind
import com.humblesolutions.finai.repository.StatementReader
import com.humblesolutions.finai.repository.StatementReadException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException

/**
 * Reads a statement on the device, and nowhere else.
 *
 * Three paths, in the order they are worth trying:
 *
 *  1. **The PDF's own text layer.** What a statement downloaded from a bank
 *     actually contains. Exact — the characters are in the file, with their
 *     positions. No model, no guessing.
 *  2. **A PDF with no text layer** — a scan. Render each page and read the
 *     pixels.
 *  3. **An image** — a photo or screenshot. Read the pixels directly.
 *
 * PdfBox does both the reading and the drawing. The platform's `PdfRenderer`
 * can draw a page but has no text API at all, and — the reason it is not used
 * even for (2) — it cannot open an encrypted PDF, so a locked scanned
 * statement could never reach the OCR path through it.
 */
class AndroidStatementReader(private val context: Context) : StatementReader {

    // Held as the `Lazy` itself, not just its value, so [close] can ask
    // whether OCR ever ran. Most statements are text-layer PDFs that never
    // touch it, and closing a recogniser that was never built would construct
    // one purely to throw it away.
    private val lazyRecognizer = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val recognizer by lazyRecognizer

    // Below this, a "text layer" is page furniture — a header, a page number —
    // and the document is really a scan.
    private val meaningfulCharacters = 40

    override suspend fun read(
        source: String,
        password: String?,
        onPage: (completed: Int, total: Int) -> Unit,
    ): ExtractedDocument = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        if (looksLikeAnImage(uri)) return@withContext readImage(uri, onPage)

        PDFBoxResourceLoader.init(context.applicationContext)
        // Opened once, with the password, and used for both paths. Reopening
        // the file for the scan instead would drop the password — and
        // `PdfRenderer`, which used to draw the pages here, cannot open an
        // encrypted PDF at all, so a locked scanned statement died on a
        // `SecurityException` that is not even the declared exception type.
        // iOS never had the bug because it keeps the unlocked document.
        openPdf(uri, password).use { pdf ->
            readTextLayer(pdf, onPage) ?: readScannedPdf(pdf, onPage)
        }
    }

    private fun looksLikeAnImage(uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("image/") == true

    /** The password is used here and never leaves the device. */
    private fun openPdf(uri: Uri, password: String?): PDDocument =
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw StatementReadException.Unsupported("cannot open")
            stream.use { PDDocument.load(it, password ?: "") }
        } catch (wrongPassword: InvalidPasswordException) {
            // Caught by type, not by looking for "password" in the message:
            // a message match breaks on a library upgrade or a translation,
            // and when it breaks the screen says "we cannot read this file"
            // instead of asking again — leaving the user no way through.
            throw StatementReadException.PasswordRequired(wrongPassword = password != null)
        } catch (alreadyOurs: StatementReadException) {
            throw alreadyOurs
        } catch (broken: Exception) {
            // Deliberately Exception and not IOException. A damaged PDF makes
            // PdfBox throw from wherever it lost its footing —
            // IllegalArgumentException, IndexOutOfBoundsException, an NPE off a
            // malformed xref — and none of those are IOException. Narrowing
            // this to IOException let them escape as themselves, which is not
            // the type this interface promises and not one the screen catches.
            throw StatementReadException.Unsupported(broken::class.simpleName.orEmpty())
        }

    /** Path 1 — or null when the file carries no usable text. */
    private fun readTextLayer(
        pdf: PDDocument,
        onPage: (Int, Int) -> Unit,
    ): ExtractedDocument? {
        val pages = mutableListOf<ExtractedPage>()
        var characters = 0
        for (index in 0 until pdf.numberOfPages) {
            val lines = linesOnPage(pdf, index + 1)
            characters += lines.sumOf { it.text.length }
            pages += ExtractedPage(index = index, lines = lines)
        }
        // No per-page progress from here. This pass does not know yet whether
        // it is the answer, and when it is not, the OCR pass starts counting
        // from one again — the bar filled, reset, and crawled, which reads as
        // a fault. Reading a text layer is the fast path, so it reports once,
        // on success; the slow path is the one that needs a running count.
        return if (characters >= meaningfulCharacters) {
            onPage(pdf.numberOfPages, pdf.numberOfPages)
            ExtractedDocument(pages = pages, source = SourceKind.PDF_TEXT)
        } else {
            null
        }
    }

    /** Groups glyphs into lines, keeping where each one sat on the page. */
    private fun linesOnPage(document: PDDocument, page: Int): List<ExtractedLine> {
        val lines = mutableListOf<ExtractedLine>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: List<TextPosition>) {
                if (text.isBlank() || positions.isEmpty()) return
                lines += ExtractedLine(
                    text = text.trim(),
                    box = BoundingBox(
                        left = positions.minOf { it.xDirAdj },
                        top = positions.minOf { it.yDirAdj },
                        right = positions.maxOf { it.xDirAdj + it.widthDirAdj },
                        bottom = positions.maxOf { it.yDirAdj + it.heightDir },
                    ),
                )
            }
        }
        stripper.startPage = page
        stripper.endPage = page
        stripper.getText(document)
        return lines
    }

    /** Path 2 — draw each page of the document already open, then read it. */
    private suspend fun readScannedPdf(
        pdf: PDDocument,
        onPage: (Int, Int) -> Unit,
    ): ExtractedDocument {
        val renderer = PDFRenderer(pdf)
        val pages = mutableListOf<ExtractedPage>()
        for (index in 0 until pdf.numberOfPages) {
            // Twice nominal size: OCR on a 72-dpi render of small print reads
            // plausible nonsense rather than failing, and a wrong amount that
            // looks right is the worst thing this feature can produce.
            val bitmap = try {
                renderer.renderImage(index, 2f)
            } catch (broken: IOException) {
                throw StatementReadException.Unsupported(broken::class.simpleName.orEmpty())
            }
            pages += ExtractedPage(index = index, lines = recognise(bitmap))
            bitmap.recycle()
            onPage(index + 1, pdf.numberOfPages)
        }
        if (pages.all { it.lines.isEmpty() }) {
            throw StatementReadException.NothingReadable()
        }
        return ExtractedDocument(pages = pages, source = SourceKind.OCR)
    }

    /** Path 3. */
    private suspend fun readImage(
        uri: Uri,
        onPage: (Int, Int) -> Unit,
    ): ExtractedDocument {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (broken: IOException) {
            throw StatementReadException.Unsupported(broken::class.simpleName.orEmpty())
        }
        val lines = recogniseImage(image)
        onPage(1, 1)
        if (lines.isEmpty()) throw StatementReadException.NothingReadable()
        return ExtractedDocument(
            pages = listOf(ExtractedPage(index = 0, lines = lines)),
            source = SourceKind.OCR,
        )
    }

    private suspend fun recognise(bitmap: Bitmap): List<ExtractedLine> =
        recogniseImage(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recogniseImage(image: InputImage): List<ExtractedLine> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    continuation.resume(
                        result.textBlocks
                            .flatMap { it.lines }
                            .map { line ->
                                val box = line.boundingBox
                                ExtractedLine(
                                    text = line.text,
                                    box = BoundingBox(
                                        left = box?.left?.toFloat() ?: 0f,
                                        top = box?.top?.toFloat() ?: 0f,
                                        right = box?.right?.toFloat() ?: 0f,
                                        bottom = box?.bottom?.toFloat() ?: 0f,
                                    ),
                                )
                            },
                    )
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    /**
     * Releases the OCR recogniser, which holds native resources.
     *
     * Safe to call when no page was ever scanned, and safe to call twice.
     */
    override fun close() {
        if (lazyRecognizer.isInitialized()) lazyRecognizer.value.close()
    }
}
