package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.SourceKind
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.repository.StatementImportRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportStatementTest {

    /** Records what was sent, and whether anything was sent at all. */
    private class FakeImports : StatementImportRepository {
        var sent: StatementUpload? = null

        override suspend fun parse(upload: StatementUpload): ParsedStatement {
            sent = upload
            return ParsedStatement(importId = "import-1")
        }

        override fun close() = Unit
    }

    private fun statement(vararg lines: String, source: SourceKind = SourceKind.PDF_TEXT) =
        ExtractedDocument(
            pages = listOf(
                ExtractedPage(index = 0, lines = lines.map { ExtractedLine(text = it) }),
            ),
            source = source,
        )

    private val header = "Statement period: 1 Aug 2026 to 31 Aug 2026"
    private val transaction = "14 Aug  SPOTIFY P3A4B5C6  10.99"

    @Test
    fun sendsTheRedactedTextAndNothingElse() = runTest {
        val imports = FakeImports()

        ImportStatement(imports).execute(
            statement("JANE DOE", "55 Main St", header, transaction),
            accountId = "acct-1",
        )

        val sent = assertNotNull(imports.sent)
        assertTrue(sent.text.contains("SPOTIFY"), "the transaction should survive")
        assertFalse(sent.text.contains("JANE DOE"), "the page 1 header block should be gone")
        assertEquals("acct-1", sent.accountId)
        assertEquals(SourceKind.PDF_TEXT.wire, sent.sourceKind)
        assertEquals(1, sent.pageCount)
    }

    @Test
    fun sendsThePeriodEvenThoughItsLineIsRedactedAway() = runTest {
        val imports = FakeImports()

        ImportStatement(imports).execute(statement("JANE DOE", header, transaction))

        val sent = assertNotNull(imports.sent)
        assertEquals("2026-08-01", sent.statementPeriodStart)
        assertEquals("2026-08-31", sent.statementPeriodEnd)
        assertFalse(
            sent.text.contains("Statement period"),
            "the line the period came from must still be redacted",
        )
    }

    @Test
    fun textOverTheLimitIsRefusedBeforeAnyRequest() = runTest {
        val imports = FakeImports()
        // One long transaction line survives redaction whole, so the redacted
        // text is over the limit by construction.
        val long = "14 Aug  " + "SPOTIFY ".repeat(StatementLimits.MAX_TEXT_CHARS / 8) + " 10.99"

        val refused = assertFailsWith<StatementTooLong> {
            ImportStatement(imports).execute(statement(header, long))
        }

        assertNull(imports.sent, "nothing may be sent once the text is over the limit")
        assertTrue(refused.characters > StatementLimits.MAX_TEXT_CHARS)
        assertEquals(StatementLimits.MAX_TEXT_CHARS, refused.limit)
    }

    @Test
    fun textAtExactlyTheLimitIsAccepted() {
        assertNull(ImportStatement.tooLong("a".repeat(StatementLimits.MAX_TEXT_CHARS)))
        assertNotNull(ImportStatement.tooLong("a".repeat(StatementLimits.MAX_TEXT_CHARS + 1)))
    }

    @Test
    fun theSourceKindTravelsSoAnOddParseCanBeExplained() = runTest {
        val imports = FakeImports()
        val pages = (0 until 3).map {
            ExtractedPage(index = it, lines = listOf(ExtractedLine(text = transaction)))
        }

        ImportStatement(imports).execute(
            ExtractedDocument(pages = pages, source = SourceKind.OCR),
        )

        val sent = assertNotNull(imports.sent)
        assertEquals(SourceKind.OCR.wire, sent.sourceKind)
        assertEquals(3, sent.pageCount)
    }

    @Test
    fun anAccountIsOptionalSoAnImportCanBeSortedOutLater() = runTest {
        val imports = FakeImports()

        ImportStatement(imports).execute(statement(header, transaction))

        assertNull(assertNotNull(imports.sent).accountId)
    }

    @Test
    fun theUploadCarriesNothingBeyondTheAgreedFields() = runTest {
        val imports = FakeImports()

        ImportStatement(imports).execute(
            statement("JANE DOE", "jane@example.com", "M5V 3A8", header, transaction),
        )

        // Asserted on the object that goes on the wire, not on an intermediate:
        // this is the last point at which anything could still be added.
        val sent = assertNotNull(imports.sent)
        assertFalse(sent.text.contains("jane@example.com"), "email line dropped")
        assertFalse(sent.text.contains("M5V 3A8"), "postal code line dropped")
        assertFalse(
            Regex("""\d(?:[ -]?\d){4,}""").containsMatchIn(sent.text),
            "no digit run longer than 4 may survive",
        )
    }
}
