package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CAPABILITIES =
    """{"region":"CA","currency":"CAD","locale":"en-CA","features":{},"content":{},"onboarding_required":[]}"""
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class FakeTokens(
    private var token: String?,
    private val refreshTo: String? = null,
    private val failWith: ApiException? = null,
) : SessionTokenSource {
    var refreshes = 0
    val rejected = mutableListOf<String?>()

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? {
        failWith?.let { throw it }
        return token
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? {
        this.rejected += rejected
        refreshes++
        token = refreshTo
        return refreshTo
    }
}

private class CapturingLogger : Logger {
    val lines = mutableListOf<String>()
    override fun log(message: String) {
        lines += message
    }
}

class FinAiHttpClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        tokens: SessionTokenSource,
        baseUrl: String = "https://api.example.test",
        logging: Boolean = false,
        logger: Logger = CapturingLogger(),
        handler: MockRequestHandler,
    ): HttpClient {
        val engine = MockEngine { request ->
            seen += request
            handler(request)
        }
        return FinAiHttpClient.create(baseUrl, tokens, logging, engine, logger)
    }

    private fun repository(
        tokens: SessionTokenSource,
        baseUrl: String = "https://api.example.test",
        logging: Boolean = false,
        logger: Logger = CapturingLogger(),
        handler: MockRequestHandler,
    ): KtorCapabilitiesRepository = KtorCapabilitiesRepository(client(tokens, baseUrl, logging, logger, handler))

    @Test
    fun `sends the session token as a bearer header`() = runTest {
        val repo = repository(FakeTokens("t1")) { respond(CAPABILITIES, HttpStatusCode.OK, jsonHeaders) }
        assertEquals("CA", repo.fetch().region)
        assertEquals("Bearer t1", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `resolves requests against the configured base URL`() = runTest {
        for (base in listOf("https://api.example.test", "https://api.example.test/")) {
            seen.clear()
            repository(FakeTokens("t1"), baseUrl = base) { respond(CAPABILITIES, HttpStatusCode.OK, jsonHeaders) }.fetch()
            assertEquals("https://api.example.test/capabilities", seen.single().url.toString())
        }
    }

    @Test
    fun `sends no token when signed out and maps the server reply to Unauthorized`() = runTest {
        val tokens = FakeTokens(null)
        val repo = repository(tokens) { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"detail":"Not authenticated"}""", HttpStatusCode.Forbidden, jsonHeaders)
        }
        assertFailsWith<ApiException.Unauthorized> { repo.fetch() }
        assertEquals(0, tokens.refreshes)
    }

    @Test
    fun `refreshes once and retries after a 401`() = runTest {
        val tokens = FakeTokens("old", refreshTo = "new")
        val repo = repository(tokens) { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer new") {
                respond(CAPABILITIES, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"detail":"token expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }
        assertEquals("CA", repo.fetch().region)
        assertEquals(1, tokens.refreshes)
        assertEquals(2, seen.size)
        assertEquals("Bearer new", seen.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `hands the rejected token to the refresh`() = runTest {
        val tokens = FakeTokens("old", refreshTo = "new")
        val repo = repository(tokens) { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer new") {
                respond(CAPABILITIES, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("", HttpStatusCode.Unauthorized)
            }
        }
        repo.fetch()
        assertEquals(listOf<String?>("old"), tokens.rejected)
    }

    @Test
    fun `sends the token only to the API origin`() = runTest {
        val client = client(FakeTokens("t1")) { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        client.get("https://third-party.example/upload") // another host
        client.get("http://api.example.test/capabilities") // same host over plain HTTP
        client.get("https://api.example.test:8443/capabilities") // same host on another port
        assertEquals(3, seen.size)
        assertTrue(
            seen.none { it.headers[HttpHeaders.Authorization] != null },
            "the session token left the API origin: ${seen.map { it.url }}",
        )
        client.get("capabilities")
        assertEquals("Bearer t1", seen.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `ignores a 401 from another origin`() = runTest {
        val tokens = FakeTokens("old", refreshTo = "new")
        val client = client(tokens) { respond("", HttpStatusCode.Unauthorized) }
        client.get("https://third-party.example/upload")
        assertEquals(0, tokens.refreshes)
        assertNull(seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `treats a second 401 as final instead of looping`() = runTest {
        val tokens = FakeTokens("old", refreshTo = "new")
        val repo = repository(tokens) { respond("""{"detail":"token expired"}""", HttpStatusCode.Unauthorized, jsonHeaders) }
        assertFailsWith<ApiException.Unauthorized> { repo.fetch() }
        assertEquals(1, tokens.refreshes)
        assertEquals(2, seen.size)
    }

    @Test
    fun `surfaces a failed refresh as Unauthorized without sending anything`() = runTest {
        val tokens = FakeTokens("old", failWith = ApiException.Unauthorized("refresh failed"))
        val repo = repository(tokens) { respond(CAPABILITIES, HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<ApiException.Unauthorized> { repo.fetch() }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `maps a feature gate to FeatureUnavailable`() = runTest {
        val repo = repository(FakeTokens("t1")) {
            respond(
                """{"detail":{"code":"feature_unavailable","feature":"bank_linking","reason":"coming_soon"}}""",
                HttpStatusCode.Forbidden,
                jsonHeaders,
            )
        }
        val error = assertFailsWith<ApiException.FeatureUnavailable> { repo.fetch() }
        assertEquals("bank_linking", error.feature)
        assertEquals(FeatureReason.COMING_SOON, error.reason)
    }

    @Test
    fun `maps a transport failure to Network`() = runTest {
        val repo = repository(FakeTokens("t1")) { throw IOException("offline") }
        assertFailsWith<ApiException.Network> { repo.fetch() }
    }

    @Test
    fun `maps a body that will not decode to Unexpected`() = runTest {
        val repo = repository(FakeTokens("t1")) { respond("not json", HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<ApiException.Unexpected> { repo.fetch() }
    }

    @Test
    fun `lets cancellation through untouched`() = runTest {
        val repo = repository(FakeTokens("t1")) { throw CancellationException("superseded") }
        assertFailsWith<CancellationException> { repo.fetch() }
    }

    @Test
    fun `never logs the token or the response body`() = runTest {
        val logger = CapturingLogger()
        val body = """{"region":"CA","content":{"tax_accounts":["BODY-CANARY"]}}"""
        val repo = repository(FakeTokens("TOKEN-CANARY"), logging = true, logger = logger) {
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        repo.fetch()
        assertTrue(logger.lines.isNotEmpty(), "logging was on but nothing was logged; the check below would prove nothing")
        assertTrue(logger.lines.none { "TOKEN-CANARY" in it }, "the bearer token reached the log")
        assertTrue(logger.lines.none { "BODY-CANARY" in it }, "the response body reached the log")
    }

    @Test
    fun `logs nothing when logging is off`() = runTest {
        val logger = CapturingLogger()
        repository(FakeTokens("t1"), logging = false, logger = logger) { respond(CAPABILITIES, HttpStatusCode.OK, jsonHeaders) }.fetch()
        assertTrue(logger.lines.isEmpty())
    }
}
