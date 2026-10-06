package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.GoalChanges
import com.humblesolutions.finai.model.GoalHorizon
import com.humblesolutions.finai.model.GoalKind
import com.humblesolutions.finai.model.GoalStatus
import com.humblesolutions.finai.model.NewGoal
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One goal as backend #65's `GoalOut` writes it. */
private const val CAR = """
{"id": "g-car", "name": "Car", "kind": "car", "horizon": "short_term",
 "target": "6000.00", "saved": "1200.00", "remaining": "4800.00",
 "target_date": "2027-09-01", "monthly_contribution": "250.00",
 "required_monthly": "400.00", "projected_completion": "2028-02",
 "progress_percent": 20, "status": "behind", "achieved_at": null, "priority": 0}
"""

private const val PAGE = """
{"goals": [$CAR], "budget": null, "budget_reason": "learning",
 "disclaimer_version": null, "projection_version": "v1", "assumes_growth": false}
"""

private val goalJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class GoalTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/**
 * Every goals call's exact request, pinned against backend #65's routes. The
 * edit body is the one that matters most: the server only clears a field it
 * is sent an explicit null for, and the shared JSON otherwise drops nulls.
 */
class KtorGoalsRepositoryTest {

    private class Seen {
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
    }

    private fun repository(seen: Seen = Seen(), status: HttpStatusCode = HttpStatusCode.OK, answer: String = CAR): KtorGoalsRepository {
        val handler: MockRequestHandler = { request ->
            seen.method = request.method
            seen.path = request.url.encodedPath
            seen.body = (request.body as? TextContent)?.text
            respond(answer, status, goalJsonHeaders)
        }
        return KtorGoalsRepository(
            FinAiHttpClient.create(baseUrl = "https://api.example.com", tokens = GoalTokens(), logging = false, engine = MockEngine(handler)),
        )
    }

    @Test
    fun `it reads every goal with the comparison and what to disclaim`() = runTest {
        val seen = Seen()
        val page = repository(seen, answer = PAGE).list()

        assertEquals(HttpMethod.Get, seen.method)
        assertEquals("/goals", seen.path)
        assertEquals(GoalStatus.BEHIND, page.goals.single().status)
        assertEquals("v1", page.projectionVersion)
    }

    @Test
    fun `a new goal leaves out what was not given`() = runTest {
        val seen = Seen()
        repository(seen, HttpStatusCode.Created).create(
            NewGoal("Car", GoalKind.CAR, GoalHorizon.SHORT_TERM, "6000.00", saved = null, targetDate = "2027-09-01", monthlyContribution = null),
        )

        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("/goals", seen.path)
        assertEquals(
            """{"name":"Car","kind":"car","horizon":"short_term","target":"6000.00","target_date":"2027-09-01"}""",
            seen.body,
        )
    }

    /** The bug this guards: a removed date silently surviving the edit. */
    @Test
    fun `an edit sends an explicit null for each field it clears`() = runTest {
        val seen = Seen()
        repository(seen).update(
            "g-car",
            GoalChanges(name = "New car", clearTargetDate = true, clearMonthlyContribution = true, clearKind = true),
        )

        assertEquals(HttpMethod.Patch, seen.method)
        assertEquals("/goals/g-car", seen.path)
        val body = FinAiJson.parseToJsonElement(seen.body.orEmpty()).jsonObject
        assertEquals("New car", body["name"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["target_date"])
        assertEquals(JsonNull, body["monthly_contribution"])
        assertEquals(JsonNull, body["kind"])
    }

    /** "Unchanged" is said by leaving a field out — never by a null. */
    @Test
    fun `an edit leaves out every field it does not change`() = runTest {
        val seen = Seen()
        repository(seen).update("g-car", GoalChanges(saved = "1500.00"))

        val body = FinAiJson.parseToJsonElement(seen.body.orEmpty()).jsonObject
        assertEquals(setOf("saved"), body.keys)
    }

    @Test
    fun `adding money posts the amount to the goal`() = runTest {
        val seen = Seen()
        repository(seen).add("g-car", "50.00")

        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("/goals/g-car/add", seen.path)
        assertEquals("""{"amount":"50.00"}""", seen.body)
    }

    @Test
    fun `reordering puts the ids in order`() = runTest {
        val seen = Seen()
        repository(seen, answer = PAGE).reorder(listOf("g-b", "g-a"))

        assertEquals(HttpMethod.Put, seen.method)
        assertEquals("/goals/order", seen.path)
        assertEquals("""{"ids":["g-b","g-a"]}""", seen.body)
    }

    @Test
    fun `deleting expects no body back`() = runTest {
        val seen = Seen()
        repository(seen, HttpStatusCode.NoContent, answer = "").delete("g-car")

        assertEquals(HttpMethod.Delete, seen.method)
        assertEquals("/goals/g-car", seen.path)
    }

    @Test
    fun `the disclaimer is read from the server`() = runTest {
        val seen = Seen()
        val terms = repository(seen, answer = """{"version":"ca-v1","body":"Not financial advice.","effective_from":null}""").disclaimer()

        assertEquals("/legal/disclaimer", seen.path)
        assertEquals("ca-v1", terms?.version)
    }

    /** A region with no disclaimer is an answer, not a failure. */
    @Test
    fun `no disclaimer for the region is null not an error`() = runTest {
        val terms = repository(status = HttpStatusCode.NotFound, answer = """{"detail":{"code":"no_disclaimer"}}""").disclaimer()

        assertNull(terms)
    }

    @Test
    fun `the limit arrives as its own error with its number`() = runTest {
        val error = assertFailsWith<ApiException.GoalLimitReached> {
            repository(status = HttpStatusCode.Conflict, answer = """{"detail":{"code":"goal_limit_reached","limit":20}}""")
                .create(NewGoal("Car", null, GoalHorizon.SHORT_TERM, "10.00", null, null, null))
        }
        assertEquals(20, error.limit)
    }

    @Test
    fun `an empty change set is recognised as nothing to send`() {
        assertTrue(GoalChanges().isEmpty)
        assertFalse(GoalChanges(clearTargetDate = true).isEmpty)
    }
}
