package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.model.SourceKind
import com.humblesolutions.finai.model.StatementImports
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
        override suspend fun list(): StatementImports = StatementImports()

        var sent: StatementUpload? = null

        override suspend fun parse(upload: StatementUpload): ParsedStatement {
            sent = upload
            return ParsedStatement(importId = "import-1")
        }

        override suspend fun save(importId: String, rows: RowsToSave): SaveOutcome = error("these tests only parse")

        override fun close() = Unit
    }

    private fun statement(vararg lines: String, source: SourceKind = SourceKind.PDF_TEXT) = ExtractedDocument(
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
            statement(
                "JANE DOE",
                "jane@example.com",
                "M5V 3A8",
                header,
                transaction,
                // Identifiers of the lengths that used to slip through: the
                // rule masked at seven digits while the wire is checked at
                // five, and the fixture happened to contain neither.
                "15 Aug  E-TRANSFER 12345                  25.00",
                "16 Aug  PURCHASE REF 123456               10.99",
                "17 Aug  CHQ 1234567 10.99",
            ),
        )

        // Asserted on the object that goes on the wire, not on an intermediate:
        // this is the last point at which anything could still be added.
        val sent = assertNotNull(imports.sent)
        assertFalse(sent.text.contains("jane@example.com"), "email line dropped")
        assertFalse(sent.text.contains("M5V 3A8"), "postal code line dropped")
        assertFalse(sent.text.contains("12345"), "five-digit run survived")
        assertFalse(sent.text.contains("123456"), "six-digit run survived")
        assertFalse(sent.text.contains("1234567"), "seven-digit run survived")

        // Amounts are not identifiers, so they are taken out before the run is
        // measured, and the run is measured inside a token — a masked tail
        // printed next to an amount is not one long number.
        val withoutAmounts = Regex("""\d[\d,]*\.\d{2}""").replace(sent.text, " ")
        assertFalse(
            Regex("""\d(?:-?\d){4,}""").containsMatchIn(withoutAmounts),
            "no digit run longer than 4 may survive: ${sent.text}",
        )
        // ...and no amount was rewritten on the way.
        assertTrue(sent.text.contains("10.99"), "an amount was altered: ${sent.text}")
        assertTrue(sent.text.contains("25.00"), "an amount was altered: ${sent.text}")
    }

    @Test
    fun tooManyPagesIsRefusedBeforeAnyRequest() = runTest {
        val imports = FakeImports()
        val pages = (0..StatementLimits.MAX_PAGES).map {
            ExtractedPage(index = it, lines = listOf(ExtractedLine(text = transaction)))
        }

        val refused = assertFailsWith<StatementTooManyPages> {
            ImportStatement(imports).execute(ExtractedDocument(pages = pages))
        }

        assertNull(imports.sent, "an over-paged statement must not be sent")
        assertEquals(StatementLimits.MAX_PAGES + 1, refused.pages)
        assertEquals(StatementLimits.MAX_PAGES, refused.limit)
    }

    @Test
    fun pagesAreRefusedBeforeLengthSoTheAdviceIsActionable() = runTest {
        // A statement can fail both bounds. Pages is the one a person can do
        // something about, so it is the one they are told about.
        val imports = FakeImports()
        // Long in total, not long per page. Giving every one of 501 pages its
        // own 200,000-character line built about a hundred megabytes of
        // strings: the JVM absorbed it and Kotlin/Native did not finish inside
        // runTest's minute, so this failed only on the iOS simulator. The
        // bound is on the whole text, so spreading it is the same test.
        val perPage = StatementLimits.MAX_TEXT_CHARS / StatementLimits.MAX_PAGES + 20
        val line = "14 Aug  " + "SPOTIFY ".repeat(perPage / 8) + " 10.99"
        val pages = (0..StatementLimits.MAX_PAGES).map {
            ExtractedPage(index = it, lines = listOf(ExtractedLine(text = line)))
        }

        assertFailsWith<StatementTooManyPages> {
            ImportStatement(imports).execute(ExtractedDocument(pages = pages))
        }
        assertNull(imports.sent)
    }

    @Test
    fun aStatementWithNoTransactionsSaysSoRatherThanPostingNothing() = runTest {
        // Page 1 with nothing that reads as a transaction redacts to "", and
        // the server refuses empty text with a validation error that means
        // nothing to a person.
        val imports = FakeImports()

        val refused = assertFailsWith<StatementHasNothingToSend> {
            ImportStatement(imports).execute(statement("ROYAL BANK OF CANADA", "JANE DOE"))
        }

        assertNull(imports.sent, "empty text must never be posted")
        assertEquals(2, refused.droppedLines, "the count is the only explanation there is")
    }

    @Test
    fun everyLocalRefusalIsOneTypeAScreenCanCatch() {
        // A screen that handles two of these and forgets the third is the
        // failure this interface exists to prevent.
        val refusals: List<StatementRefusal> = listOf(
            StatementTooLong(StatementLimits.MAX_TEXT_CHARS + 1),
            StatementTooManyPages(StatementLimits.MAX_PAGES + 1),
            StatementHasNothingToSend(3),
        )

        assertEquals(refusals.size, refusals.map { it.messageKey }.toSet().size)
        assertTrue(refusals.all { it.messageKey.isNotBlank() })
    }

    @Test
    fun theCallerIsToldHowMuchWasDropped() = runTest {
        val imports = FakeImports()
        var redaction: StatementRedactor.Redaction? = null

        ImportStatement(imports).execute(
            statement("JANE DOE", "55 Main St", header, transaction),
            onRedacted = { redaction = it },
        )

        // Three header lines sit above the first transaction and are dropped;
        // a silent short import is the one failure nothing else reveals.
        assertEquals(3, assertNotNull(redaction).droppedLines)
    }

    @Test
    fun theDiagnosticAnswerTravelsOnlyWhenGiven() = runTest {
        val imports = FakeImports()
        val document = statement("2026-08-14  TIM HORTONS  12.40")

        ImportStatement(imports).execute(document, accountId = "acct-1")
        assertFalse(assertNotNull(imports.sent).keepTextForDiagnostics)

        ImportStatement(imports).execute(document, accountId = "acct-1", keepTextForDiagnostics = true)
        assertTrue(assertNotNull(imports.sent).keepTextForDiagnostics)
    }
}
