package com.humblesolutions.finai.data

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.ApiException
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
}
