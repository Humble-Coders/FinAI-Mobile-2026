package com.humblesolutions.finai.data

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.RowToSave
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private const val PARSED = """{"import_id":"i-1","currency":"CAD","rows":[],
    "unparsed_line_count":0,"model":"test"}"""

private class ImportTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/** What the parse call puts on the wire, and how long it is willing to wait. */
class KtorStatementImportRepositoryTest {

    private var seen: HttpRequestData? = null

    private fun repository(): KtorStatementImportRepository {
        val engine = MockEngine { request ->
            seen = request
            respond(PARSED, HttpStatusCode.OK, jsonHeaders)
        }
        return KtorStatementImportRepository(
            FinAiHttpClient.create(
                baseUrl = "https://api.example.com",
                tokens = ImportTokens(),
                logging = false,
                engine = engine,
            ),
        )
    }

    @Test
    fun waitsLongerForAParseThanForAnythingElse() = runTest {
        repository().parse(StatementUpload(sourceKind = "pdf_text", text = "14 Aug COFFEE 5.00"))

        // The server is allowed three minutes to parse. Timing out first does
        // not stop it — the import finishes unseen, the model call is spent,
        // and the user is told to try again.
        val timeout = assertNotNull(
            assertNotNull(seen).getCapabilityOrNull(HttpTimeoutCapability),
            "the parse request carries no timeout of its own",
        )
        // Both bounds. Asserting only the request timeout is how the first
        // attempt at this passed while the socket timeout still ended the
        // call at a minute: the test checked the field that had been set
        // rather than the waiting the change was meant to buy.
        assertEquals(
            StatementLimits.PARSE_TIMEOUT_MS,
            timeout.requestTimeoutMillis,
            "request timeout fell back to the default",
        )
        assertEquals(
            StatementLimits.PARSE_TIMEOUT_MS,
            timeout.socketTimeoutMillis,
            "socket timeout fell back to the default, so the parse still dies at a minute",
        )
        assertTrue(
            StatementLimits.PARSE_TIMEOUT_MS > FinAiHttpClient.REQUEST_TIMEOUT_MS,
            "the parse timeout must exceed the default, not fall back to it",
        )
    }

    @Test
    fun sendsTheRedactedTextAndTheFieldsTheApiNames() = runTest {
        repository().parse(
            StatementUpload(
                sourceKind = "ocr",
                pageCount = 2,
                text = "14 Aug COFFEE 5.00",
                statementPeriodStart = "2026-08-01",
                statementPeriodEnd = "2026-08-31",
            ),
        )

        val body = (assertNotNull(seen).body as TextContent).text
        assertTrue(body.contains("\"source_kind\":\"ocr\""), body)
        assertTrue(body.contains("\"page_count\":2"), body)
        assertTrue(body.contains("\"statement_period_start\":\"2026-08-01\""), body)
        assertTrue(body.contains("\"statement_period_end\":\"2026-08-31\""), body)
    }

    @Test
    fun theDiagnosticFlagStaysOffTheWireUnlessItIsTrue() = runTest {
        val repo = repository()

        repo.parse(StatementUpload(sourceKind = "pdf_text", text = "14 Aug COFFEE 5.00"))
        val plain = (assertNotNull(seen).body as TextContent).text
        repo.parse(
            StatementUpload(sourceKind = "pdf_text", text = "14 Aug COFFEE 5.00", keepTextForDiagnostics = true),
        )
        val offered = (assertNotNull(seen).body as TextContent).text

        // Off by default and never sent as false: only an explicit yes from
        // the person travels (#31).
        assertFalse(plain.contains("keep_text_for_diagnostics"), plain)
        assertTrue(offered.contains("\"keep_text_for_diagnostics\":true"), offered)
    }

    @Test
    fun savingPostsTheRowsToTheImportWithTheAccount() = runTest {
        val engine = MockEngine { request ->
            seen = request
            respond(
                """{"import_id":"i-1","saved":2,"duplicates":1,"flagged":0,"needs_review":1}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val repo = KtorStatementImportRepository(
            FinAiHttpClient.create("https://api.example.com", ImportTokens(), false, engine = engine),
        )

        val outcome = repo.save(
            "i-1",
            RowsToSave(
                accountId = "acct-1",
                rows = listOf(
                    RowToSave("2026-08-14", "COFFEE", "5.00", "debit", 90),
                ),
            ),
        )

        val request = assertNotNull(seen)
        assertEquals("/statements/i-1/transactions", request.url.encodedPath)
        val body = (request.body as TextContent).text
        assertTrue(body.contains("\"account_id\":\"acct-1\""), body)
        assertTrue(body.contains("\"amount\":\"5.00\""), body)
        assertEquals(2, outcome.saved)
        assertEquals(1, outcome.needsReview)
    }
}
