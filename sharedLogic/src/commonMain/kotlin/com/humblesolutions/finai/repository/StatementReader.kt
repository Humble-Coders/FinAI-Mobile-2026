package com.humblesolutions.finai.repository

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ExtractedDocument
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads a statement off the device. The one genuinely platform-specific piece.
 *
 * PDFKit and Vision on iOS, PdfBox and ML Kit on Android — native SDK calls, so
 * they live in the platform modules. Everything decided about the result
 * afterwards is shared (kmp-arch-v2).
 *
 * Nothing here ever sends anything. The document does not leave the phone; only
 * [com.humblesolutions.finai.usecase.StatementRedactor]'s output does.
 */
interface StatementReader {

    /**
     * @param source where the file is, in whatever form the platform names it.
     * @param password for an encrypted PDF. Used on the device and never sent.
     * @param onPage progress, page by page — a twenty-page scan is a long wait
     *        and a bare spinner reads as a hang.
     */
    @Throws(StatementReadException::class, CancellationException::class)
    suspend fun read(
        source: String,
        password: String? = null,
        onPage: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ExtractedDocument

    /**
     * Releases whatever the platform reader holds open — on Android an ML Kit
     * recogniser, which owns native resources and leaks without this. Called
     * when the screen that made the reader goes away, as with
     * [StatementImportRepository.close] (kmp-arch-v2).
     */
    fun close()
}

/**
 * Why a statement could not be read, in terms a screen can act on.
 *
 * Deliberately not [ApiException]: nothing here has spoken to a server.
 */
sealed class StatementReadException(message: String) : Exception(message) {
    /** The file is encrypted and needs a password, or the one given is wrong. */
    class PasswordRequired(val wrongPassword: Boolean = false) : StatementReadException("password required")

    /** A format we cannot open at all. */
    class Unsupported(val detail: String = "") : StatementReadException("unsupported")

    /** Opened, but nothing that reads as text came out. */
    class NothingReadable : StatementReadException("nothing readable")

    /**
     * More pages than the API will accept, refused **before** any page is read.
     *
     * This has to live here rather than only at the point of sending. By the
     * time a document reaches
     * [com.humblesolutions.finai.usecase.ImportStatement] every page has
     * already been rendered and put through OCR, which on a long scan is
     * minutes — so refusing there saves a round trip and none of the waiting.
     */
    class TooManyPages(
        val pages: Int,
        val limit: Int = StatementLimits.MAX_PAGES,
    ) : StatementReadException("too many pages")
}
