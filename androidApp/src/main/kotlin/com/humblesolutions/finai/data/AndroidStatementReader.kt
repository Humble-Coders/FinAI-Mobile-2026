package com.humblesolutions.finai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 * `PdfRenderer` appears here only to *draw* pages for (2); it has no text API
 * at all, which is why PdfBox is a dependency.
 */
class AndroidStatementReader(private val context: Context) : StatementReader {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

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
        val textLayer = readTextLayer(uri, password, onPage)
        if (textLayer != null) return@withContext textLayer

        readScannedPdf(uri, onPage)
    }

    private fun looksLikeAnImage(uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("image/") == true

    /** Path 1 — or null when the file carries no usable text. */
    private fun readTextLayer(
        uri: Uri,
        password: String?,
        onPage: (Int, Int) -> Unit,
    ): ExtractedDocument? {
        val document = try {
            context.contentResolver.openInputStream(uri).use { stream ->
                PDDocument.load(stream, password ?: "")
            }
        } catch (error: Exception) {
            // PdfBox reports a wrong or missing password as a load failure.
            // The password is used here and never leaves the device.
            if (isPasswordProblem(error)) {
                throw StatementReadException.PasswordRequired(wrongPassword = password != null)
            }
            throw StatementReadException.Unsupported(error::class.simpleName.orEmpty())
        }

        document.use { pdf ->
            val pages = mutableListOf<ExtractedPage>()
            var characters = 0
            for (index in 0 until pdf.numberOfPages) {
                val lines = linesOnPage(pdf, index + 1)
                characters += lines.sumOf { it.text.length }
                pages += ExtractedPage(index = index, lines = lines)
                onPage(index + 1, pdf.numberOfPages)
            }
            return if (characters >= meaningfulCharacters) {
                ExtractedDocument(pages = pages, source = SourceKind.PDF_TEXT)
            } else {
                null
            }
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

    /** Path 2 — draw each page, then read the pixels. */
    private suspend fun readScannedPdf(
        uri: Uri,
        onPage: (Int, Int) -> Unit,
    ): ExtractedDocument {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw StatementReadException.Unsupported("cannot open")
        descriptor.use { file ->
            PdfRenderer(file).use { renderer ->
                val pages = mutableListOf<ExtractedPage>()
                for (index in 0 until renderer.pageCount) {
                    val bitmap = renderPage(renderer, index)
                    pages += ExtractedPage(index = index, lines = recognise(bitmap))
                    bitmap.recycle()
                    onPage(index + 1, renderer.pageCount)
                }
                if (pages.all { it.lines.isEmpty() }) {
                    throw StatementReadException.NothingReadable()
                }
                return ExtractedDocument(pages = pages, source = SourceKind.OCR)
            }
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap =
        renderer.openPage(index).use { page ->
            // Twice the page's nominal size: OCR on a 72-dpi render of small
            // print reads plausible nonsense rather than failing.
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }

    /** Path 3. */
    private suspend fun readImage(
        uri: Uri,
        onPage: (Int, Int) -> Unit,
    ): ExtractedDocument {
        val image = InputImage.fromFilePath(context, uri)
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

    private fun isPasswordProblem(error: Exception): Boolean {
        val text = (error.message ?: "") + error::class.simpleName.orEmpty()
        return text.contains("password", ignoreCase = true) ||
            text.contains("InvalidPassword", ignoreCase = true)
    }
}

private inline fun <T> ParcelFileDescriptor.use(block: (ParcelFileDescriptor) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
