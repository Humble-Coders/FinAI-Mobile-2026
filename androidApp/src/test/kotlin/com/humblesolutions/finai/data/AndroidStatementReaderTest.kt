package com.humblesolutions.finai.data

import android.net.Uri
import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.SourceKind
import com.humblesolutions.finai.repository.StatementReadException
import com.humblesolutions.finai.usecase.StatementPeriod
import com.humblesolutions.finai.usecase.StatementRedactor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The text-layer path, end to end on the JVM.
 *
 * Robolectric earns its place here: PdfBox needs an Android `Context` for its
 * font resources, so without it this path could only be verified by compiling
 * it — and "it compiles" says nothing about whether a statement comes out.
 *
 * The statement is generated rather than committed. A real one is somebody's
 * financial life, and the ticket is explicit that fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric emulates `compileSdk` by default, which here is 36 — newer than
// it supports, and it dies inside Android 16's ApplicationSharedMemory before
// a test runs. The SDK emulated says nothing about what this test covers: it
// reads a PDF and runs the redactor, neither of which is version-specific.
@Config(sdk = [34])
class AndroidStatementReaderTest {

    private lateinit var reader: AndroidStatementReader

    private val statementLines = listOf(
        "ROYAL BANK OF CANADA",
        "JORDAN A MCKENZIE",
        "144 RIDEAU ST APT 12",
        "OTTAWA ON  K1N 5X6",
        "Account number: 06012-5004321",
        "Statement period: 1 Aug 2026 to 31 Aug 2026",
        "01 Aug  PAYROLL DEP NORTHWIND   2410.00",
        "02 Aug  TIM HORTONS #4821          5.25",
        "03 Aug  LOBLAWS 1042            134.02",
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        reader = AndroidStatementReader(context)
    }

    /** The same statement, encrypted — what plenty of banks actually email. */
    private fun aLockedStatementPdf(password: String): Uri {
        val file = File.createTempFile("locked-statement", ".pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 10f)
                content.newLineAtOffset(40f, 750f)
                statementLines.forEach { line ->
                    content.showText(line)
                    content.newLineAtOffset(0f, -14f)
                }
                content.endText()
            }
            document.protect(
                StandardProtectionPolicy(password, password, AccessPermission()),
            )
            document.save(file)
        }
        return Uri.fromFile(file)
    }

    @Test
    fun aLockedStatementOpensWithItsPassword() = runTest {
        val document = reader.read(aLockedStatementPdf("hunter2").toString(), "hunter2")

        assertEquals(SourceKind.PDF_TEXT, document.source)
        assertTrue(
            document.allLines().any { it.text.contains("TIM HORTONS") },
            "the statement did not come back: ${document.allLines().size} lines",
        )
    }

    @Test
    fun theWrongPasswordSaysSoRatherThanFailingObscurely() = runTest {
        val failure = assertFailsWith<StatementReadException.PasswordRequired> {
            reader.read(aLockedStatementPdf("hunter2").toString(), "not-the-password")
        }

        assertTrue(failure.wrongPassword, "the screen needs to know a password was tried")
    }

    @Test
    fun aLockedStatementWithNoPasswordAsksForOne() = runTest {
        val failure = assertFailsWith<StatementReadException.PasswordRequired> {
            reader.read(aLockedStatementPdf("hunter2").toString())
        }

        assertFalse(failure.wrongPassword, "nothing was tried yet, so nothing was wrong")
    }

    @Test
    fun closingAReaderThatNeverScannedDoesNotBuildAnOcrEngine() {
        // Every test above reads a text-layer PDF, so the recogniser was never
        // needed. Closing must not construct one just to release it — under
        // Robolectric that throws "MlKitContext has not been initialized",
        // which is exactly what it would do on a device in the same state.
        reader.close()
        reader.close()
    }

    @Test
    fun aStatementWithTooManyPagesIsRefusedBeforeItIsRead() = runTest {
        val file = File.createTempFile("long-statement", ".pdf")
        PDDocument().use { document ->
            repeat(StatementLimits.MAX_PAGES + 1) { document.addPage(PDPage()) }
            document.save(file)
        }
        var pagesRead = 0

        val refused = assertFailsWith<StatementReadException.TooManyPages> {
            reader.read(Uri.fromFile(file).toString()) { _, _ -> pagesRead++ }
        }

        assertEquals(StatementLimits.MAX_PAGES + 1, refused.pages)
        // The point of the check: nothing was read. Counting pages after the
        // read costs the user the whole OCR pass before the refusal.
        assertEquals(0, pagesRead, "pages were read before the file was refused")
    }

    private fun aStatementPdf(): Uri {
        val file = File.createTempFile("statement", ".pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 10f)
                content.newLineAtOffset(40f, 750f)
                statementLines.forEach { line ->
                    content.showText(line)
                    content.newLineAtOffset(0f, -14f)
                }
                content.endText()
            }
            document.save(file)
        }
        return Uri.fromFile(file)
    }

    @Test
    fun aPdfWithATextLayerIsReadWithoutOcr() = runTest {
        val document = reader.read(aStatementPdf().toString())

        assertEquals(SourceKind.PDF_TEXT, document.source)
        assertTrue("no lines came out", document.allLines().isNotEmpty())
    }

    @Test
    fun everyStatementLineComesBackInReadingOrder() = runTest {
        val document = reader.read(aStatementPdf().toString())

        val text = document.allLines().joinToString("\n") { it.text }
        assertTrue("payroll line missing", text.contains("PAYROLL DEP NORTHWIND"))
        assertTrue("coffee line missing", text.contains("TIM HORTONS"))
        assertTrue(
            "out of order",
            text.indexOf("PAYROLL") < text.indexOf("TIM HORTONS"),
        )
    }

    @Test
    fun linesCarryTheirPositionOnThePage() = runTest {
        val document = reader.read(aStatementPdf().toString())

        // A statement is a table; reading order alone loses the columns.
        assertTrue(document.allLines().any { it.box.right > it.box.left })
    }

    @Test
    fun readingProgressIsReportedPerPage() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()

        reader.read(aStatementPdf().toString()) { done, total -> seen += done to total }

        assertEquals(listOf(1 to 1), seen)
    }

    @Test
    fun whatLeavesTheDeviceCarriesNoIdentifiers() = runTest {
        // The whole pipeline: read it, take the period, redact, and check what
        // would actually go on the wire.
        val document = reader.read(aStatementPdf().toString())

        val period = StatementPeriod.find(document)
        val text = StatementRedactor.redact(document)

        assertNotNull("the period must survive the redaction", period)
        assertEquals("2026-08-01", period!!.start)
        assertFalse("the name survived", text.contains("MCKENZIE"))
        assertFalse("the address survived", text.contains("RIDEAU"))
        assertFalse("the account number survived", text.contains("5004321"))
        assertTrue("a transaction was lost", text.contains("TIM HORTONS"))
    }

    @Test
    fun anUnreadableFileSaysSoRatherThanReturningNothing() = runTest {
        val notAPdf = File.createTempFile("junk", ".pdf").apply { writeText("not a pdf") }

        try {
            reader.read(Uri.fromFile(notAPdf).toString())
            throw AssertionError("expected a StatementReadException")
        } catch (expected: StatementReadException) {
            // The screen needs to tell this from a network failure (3.6).
        }
    }
}
