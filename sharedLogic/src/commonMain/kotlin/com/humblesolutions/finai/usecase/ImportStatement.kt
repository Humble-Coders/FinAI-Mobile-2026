package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.repository.StatementImportRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * A document the device has read in, transaction rows back — everything that
 * happens to a statement after the native read (PRD F2, rewritten 2026-09-21).
 *
 * The order is not arbitrary. The period is read **before** redaction because
 * the block that carries the year is the same block the redactor drops, and the
 * backend omits a row it cannot date rather than guessing one. Read it after,
 * and a whole statement comes back empty.
 *
 * **Takes a document, not a
 * [com.humblesolutions.finai.repository.StatementReader].** Reading is the one
 * genuinely native step — PDFKit and Vision, PdfBox and ML Kit — and a Kotlin
 * `suspend` interface is awkward to conform to from Swift. Were the reader a
 * dependency here, iOS could not use this class at all and would reimplement
 * the four steps below in Swift, which is precisely how the two platforms come
 * to disagree about what gets redacted. So the platform reads, and hands the
 * result here; every decision after that is made once (kmp-arch-v2).
 *
 * Only [StatementUpload] leaves the device. The document, its lines, and the
 * password never reach this class's output — the password never reaches this
 * class at all.
 */
class ImportStatement(
    private val imports: StatementImportRepository,
) {

    /**
     * @param document what the platform reader produced. Never sent; only the
     *        redaction of it is.
     * @param accountId the account these rows belong to, when the user has
     *        already chosen one.
     * @param keepTextForDiagnostics the person's explicit yes to keeping this
     *        import's redacted text so the parser can be fixed — asked only
     *        after an import the server read and failed on (#31). Never
     *        defaulted to yes, never remembered.
     * @param onRedacted the redaction and what it discarded, before the
     *        request goes out. A dropped line is the one outcome that is
     *        otherwise invisible — the import simply comes up short — and a
     *        count is safe to surface where the text is not.
     *
     * @throws StatementTooLong the redacted text is over what the API accepts;
     *         thrown **before** any request, so the user is not made to wait
     *         through a round trip to be refused.
     */
    @Throws(
        StatementTooLong::class,
        StatementTooManyPages::class,
        StatementHasNothingToSend::class,
        ApiException::class,
        CancellationException::class,
    )
    suspend fun execute(
        document: ExtractedDocument,
        accountId: String? = null,
        keepTextForDiagnostics: Boolean = false,
        onRedacted: (StatementRedactor.Redaction) -> Unit = {},
    ): ParsedStatement {
        val period = StatementPeriod.find(document)
        val redaction = StatementRedactor.of(document)
        val text = redaction.text
        onRedacted(redaction)

        tooManyPages(document.pages.size)?.let { throw it }
        tooLong(text)?.let { throw it }
        // Everything was dropped. The server refuses empty text with a
        // validation error that means nothing to a person, so say the useful
        // thing here instead — and it is knowable here, which is the point of
        // counting what redaction threw away.
        if (text.isEmpty()) throw StatementHasNothingToSend(redaction.droppedLines)

        return imports.parse(
            StatementUpload(
                sourceKind = document.source.wire,
                pageCount = document.pages.size,
                text = text,
                accountId = accountId,
                statementPeriodStart = period?.start,
                statementPeriodEnd = period?.end,
                keepTextForDiagnostics = keepTextForDiagnostics,
            ),
        )
    }

    companion object {
        /**
         * Why this text cannot be sent, or null — the single source both the
         * throw above and any screen that wants to warn earlier read from, so
         * a greyed-out button and the refusal can never disagree
         * (kmp-arch-v2, blocking reasons).
         */
        fun tooLong(text: String): StatementTooLong? = if (text.length > StatementLimits.MAX_TEXT_CHARS) {
            StatementTooLong(text.length)
        } else {
            null
        }

        /** The companion to [tooLong], read the same way and by the same screens. */
        fun tooManyPages(pages: Int): StatementTooManyPages? = if (pages > StatementLimits.MAX_PAGES) {
            StatementTooManyPages(pages)
        } else {
            null
        }
    }
}

/**
 * Why the device did not send a statement, in a form a screen can act on.
 *
 * One type to catch for every local refusal, so a screen cannot handle two of
 * them and forget the third.
 */
interface StatementRefusal {
    /** The string to show. Never a character count quoted at the user. */
    val messageKey: String
}

/**
 * The redacted text is longer than `POST /statements/parse` accepts.
 *
 * Carries lengths, never the text. [messageKey] is the string a screen shows;
 * it tells the user what to do about it rather than quoting a character count
 * at them.
 */
class StatementTooLong(
    val characters: Int,
    val limit: Int = StatementLimits.MAX_TEXT_CHARS,
) : Exception("statement too long"),
    StatementRefusal {
    override val messageKey: String get() = Strings.statement_too_long
}

/**
 * More pages than the endpoint accepts.
 *
 * Checked before the request and, deliberately, before the text length: a
 * statement can fail both, and pages is the one a person can act on by
 * importing a shorter range.
 */
class StatementTooManyPages(
    val pages: Int,
    val limit: Int = StatementLimits.MAX_PAGES,
) : Exception("statement has too many pages"),
    StatementRefusal {
    override val messageKey: String get() = Strings.statement_too_many_pages
}

/**
 * Redaction left nothing to send — no line on the statement read as a
 * transaction.
 *
 * Carries [droppedLines] because the count is the only thing that explains it,
 * and a count is safe to show where the text is not.
 */
class StatementHasNothingToSend(
    val droppedLines: Int = 0,
) : Exception("nothing to send"),
    StatementRefusal {
    override val messageKey: String get() = Strings.statement_no_transactions
}
