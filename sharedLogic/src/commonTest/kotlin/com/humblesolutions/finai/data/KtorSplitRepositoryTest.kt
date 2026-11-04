package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.SplitPersonToSave
import com.humblesolutions.finai.model.SplitToSave
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class SplitTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

private const val SPLIT = """
{"id": "s1", "title": "Lunch", "occurred_on": "2026-10-12", "mode": "equal",
 "currency": "INR", "total": "10.00", "tax": "0.00", "tip": "0.00",
 "participants": [
   {"id": "p0", "contact_id": null, "is_self": true, "name": "", "phone": null,
    "share": "3.34", "settled": true, "items": []},
   {"id": "p1", "contact_id": "c1", "is_self": false, "name": "Rohan",
    "phone": "+919876543210", "share": "3.33", "settled": false, "items": []}],
 "items": [], "owed_to_you": "3.33"}
"""

/** `/splits`' exact requests, pinned against the backend's routes. */
class KtorSplitRepositoryTest {

    private class Seen {
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
    }

    private fun repository(seen: Seen, answer: String, status: HttpStatusCode = HttpStatusCode.OK) = KtorSplitRepository(
        FinAiHttpClient.create(
            baseUrl = "https://api.example.com",
            tokens = SplitTokens(),
            logging = false,
            engine = MockEngine { request ->
                seen.method = request.method
                seen.path = request.url.encodedPath
                seen.body = (request.body as? TextContent)?.text
                respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    @Test
    fun `a split is sent with the person themselves as a null contact`() = runTest {
        val seen = Seen()
        val split = repository(seen, SPLIT, HttpStatusCode.Created).create(
            SplitToSave("Lunch", "2026-10-12", "equal", "10.00", emptyList(), null, null, listOf(SplitPersonToSave(null), SplitPersonToSave("c1"))),
        )

        assertEquals(HttpMethod.Post to "/splits", seen.method to seen.path)
        val body = FinAiJson.parseToJsonElement(seen.body.orEmpty()).jsonObject
        assertEquals("c1", body["participants"]?.jsonArray?.get(1)?.jsonObject?.get("contact_id")?.jsonPrimitive?.content)
        assertTrue(split.participants[0].isSelf)
        assertEquals("3.33", split.owedToYou)
    }

    @Test
    fun `settling patches that participant`() = runTest {
        val seen = Seen()
        repository(seen, SPLIT).settle("s1", "p1", settled = true)

        assertEquals(HttpMethod.Patch to "/splits/s1/participants/p1", seen.method to seen.path)
        assertEquals("true", FinAiJson.parseToJsonElement(seen.body.orEmpty()).jsonObject["settled"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an unreachable number and the receipt limit are their own errors`() = runTest {
        assertFailsWith<ApiException.InvalidPhone> {
            repository(Seen(), """{"detail": {"code": "invalid_phone", "field": "phone"}}""", HttpStatusCode.UnprocessableEntity).addContact("X", "123")
        }
        assertFailsWith<ApiException.ReceiptLimitReached> {
            repository(Seen(), """{"detail": {"code": "receipt_limit_reached", "limit": 40}}""", HttpStatusCode.TooManyRequests).readReceipt("x")
        }
    }
}
