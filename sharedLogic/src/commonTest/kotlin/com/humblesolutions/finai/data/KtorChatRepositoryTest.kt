package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatRole
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

private val chatJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class ChatTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/** Every chat call's exact request, pinned against backend #84 and #89's routes. */
class KtorChatRepositoryTest {

    private class Seen {
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
    }

    private fun repository(seen: Seen = Seen(), status: HttpStatusCode = HttpStatusCode.OK, answer: String = "{}"): KtorChatRepository {
        val handler: MockRequestHandler = { request ->
            seen.method = request.method
            seen.path = request.url.encodedPath
            seen.body = (request.body as? TextContent)?.text
            respond(answer, status, chatJsonHeaders)
        }
        return KtorChatRepository(
            FinAiHttpClient.create(baseUrl = "https://api.example.com", tokens = ChatTokens(), logging = false, engine = MockEngine(handler)),
        )
    }

    @Test
    fun `the chips come from the server`() = runTest {
        val seen = Seen()
        val chips = repository(seen, answer = """{"suggestions":[{"id":"spent_last_month","text":"How much did I spend last month?"}]}""").suggestions()

        assertEquals("/chat/suggestions", seen.path)
        assertEquals("spent_last_month", chips.single().id)
    }

    @Test
    fun `the list reads newest first as sent`() = runTest {
        val seen = Seen()
        val list = repository(seen, answer = """{"conversations":[{"id":"c2","title":"Rent","preview":"You paid","updated_at":"2026-09-15T12:00:00Z"},{"id":"c1","title":null,"preview":null,"updated_at":"2026-09-14T12:00:00Z"}]}""").conversations()

        assertEquals("/chat/conversations", seen.path)
        assertEquals(listOf("c2", "c1"), list.map { it.id })
        assertNull(list[1].title)
    }

    @Test
    fun `starting a chat posts to the list`() = runTest {
        val seen = Seen()
        val started = repository(seen, HttpStatusCode.Created, """{"id":"c3","title":null,"preview":null,"updated_at":"2026-09-15T12:00:00Z"}""").start()

        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("/chat/conversations", seen.path)
        assertEquals("c3", started.id)
    }

    @Test
    fun `a conversation reads its messages with who said them`() = runTest {
        val seen = Seen()
        val conversation = repository(seen, answer = """{"id":"c1","title":"August","messages":[{"role":"user","text":"What did I spend?","at":"2026-09-15T12:00:00Z","answered_by":null,"disclaimer":null},{"role":"assistant","text":"You spent ${'$'}840.00 in August.","at":"2026-09-15T12:00:01Z","answered_by":"model","disclaimer":false}]}""").conversation("c1")

        assertEquals("/chat/conversations/c1", seen.path)
        assertEquals(listOf(ChatRole.YOU, ChatRole.COACH), conversation.messages.map { it.sender })
        assertEquals("You spent ${'$'}840.00 in August.", conversation.messages.last().text)
    }

    @Test
    fun `a typed question is sent as text with no suggestion id`() = runTest {
        val seen = Seen()
        val reply = repository(seen, answer = """{"text":"You spent ${'$'}840.00.","disclaimer":false,"answered_by":"model","learning":null}""").send("c1", "What did I spend?", null)

        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("/chat/conversations/c1/messages", seen.path)
        val body = FinAiJson.parseToJsonElement(seen.body!!).jsonObject
        assertEquals("What did I spend?", body["text"]!!.jsonPrimitive.content)
        assertFalse("suggestion_id" in body, "no id for a typed question")
        assertNull(reply.learning)
    }

    @Test
    fun `a chip sends its id and the reply carries the learning progress`() = runTest {
        val seen = Seen()
        val reply = repository(
            seen,
            answer = """{"text":"You've spent ${'$'}50.10 so far in 2026.","disclaimer":false,"answered_by":"template","learning":{"ready":false,"complete_months":0,"transactions":5,"needs":{"complete_months":1,"transactions":20}}}""",
        ).send("c1", "", "spent_this_year")

        val body = FinAiJson.parseToJsonElement(seen.body!!).jsonObject
        assertEquals("spent_this_year", body["suggestion_id"]!!.jsonPrimitive.content)
        assertEquals(5, reply.learning!!.transactions)
        assertEquals(20, reply.learning!!.needs.transactions)
    }

    @Test
    fun `deleting a chat reads no body from the 204`() = runTest {
        val seen = Seen()
        repository(seen, HttpStatusCode.NoContent, "").delete("c1")

        assertEquals(HttpMethod.Delete, seen.method)
        assertEquals("/chat/conversations/c1", seen.path)
    }

    @Test
    fun `the daily limit is its own refusal`() = runTest {
        val error = assertFailsWith<ApiException.ChatLimitReached> {
            repository(status = HttpStatusCode.TooManyRequests, answer = """{"detail":{"code":"chat_limit_reached","message":"That's today's questions.","limit":30}}""").send("c1", "Hi", null)
        }
        assertEquals(30, error.limit)
    }

    @Test
    fun `typed questions without consent are refused with consent required`() = runTest {
        assertFailsWith<ApiException.ConsentRequired> {
            repository(status = HttpStatusCode.Conflict, answer = """{"detail":{"code":"consent_required","policy_version":"ai-v1"}}""").send("c1", "Hi", null)
        }
    }

    @Test
    fun `no disclaimer in the region is no link not an error`() = runTest {
        assertNull(repository(status = HttpStatusCode.NotFound, answer = """{"detail":{"code":"no_disclaimer"}}""").disclaimer())
    }
}
