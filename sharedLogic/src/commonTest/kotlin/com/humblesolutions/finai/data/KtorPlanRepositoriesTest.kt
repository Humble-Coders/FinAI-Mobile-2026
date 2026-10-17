package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.BalanceSource
import com.humblesolutions.finai.model.DebtChanges
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
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

/** One debt as backend #76's `DebtOut` writes it. */
private const val VISA = """
{"id": "d-visa", "name": "Visa", "kind": "credit_card", "balance": "4000.00",
 "minimum_payment": "120.00", "interest_rate_percent": "20.99",
 "promo_rate_percent": null, "promo_ends_on": null, "due_day": 12,
 "account_id": "a-card", "balance_as_of": "2026-08-31",
 "balance_source": "statement", "balance_stale": false, "from_setup": true}
"""

/** A plan as backend #76's `PlanOut` writes it. */
private const val PLAN = """
{"month": "2026-11", "currency": "CAD", "status": "ready", "leftover": "1200.00",
 "shortfall": null,
 "preferences": {"saved": false, "buffer": "1000.00", "buffer_goal_id": null,
   "goal_share_percent": "20.00", "rate_line_percent": "6.00", "strategy": "avalanche",
   "suggested": {"buffer": "1000.00", "goal_share_percent": "20.00",
     "rate_line_percent": "6.00", "strategy": "avalanche"}},
 "split": {"buffer": "800.00", "buffer_saved": "200.00", "buffer_target": "1000.00",
   "goals": [{"goal_id": "g-house", "name": "House", "amount": "80.00"}],
   "extra_debt": "320.00", "save_or_invest": "0.00"},
 "debts": [{"debt_id": "d-visa", "name": "Visa", "kind": "credit_card",
   "balance": "4000.00", "minimum_payment": "120.00", "rate_percent": "20.99",
   "order": 1, "minimum_only": false, "missing_rate": false, "gets_extra_now": true,
   "paid_off": "2027-03", "never_paid_off": false, "interest": "180.73"}],
 "totals": {"total_interest": "180.73", "debt_free": "2027-03",
   "minimums_only_interest": "900.00", "interest_saved": "719.27",
   "other_strategy": "snowball", "other_strategy_difference": "0.00"}}
"""

private const val PREFERENCES = """
{"saved": true, "buffer": null, "buffer_goal_id": null, "goal_share_percent": "25.00",
 "rate_line_percent": "7.00", "strategy": "snowball",
 "suggested": {"buffer": "1000.00", "goal_share_percent": "20.00",
   "rate_line_percent": "6.00", "strategy": "avalanche"}}
"""

private val planJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class PlanTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/** `/debts` and `/plan`'s exact requests, pinned against backend #76's routes. */
class KtorPlanRepositoriesTest {

    private class Seen {
        var method: HttpMethod? = null
        var path: String? = null
        var query: String? = null
        var body: String? = null
    }

    private fun handler(seen: Seen, answer: String, status: HttpStatusCode): MockRequestHandler = { request ->
        seen.method = request.method
        seen.path = request.url.encodedPath
        seen.query = request.url.encodedQuery
        seen.body = (request.body as? TextContent)?.text
        respond(answer, status, planJsonHeaders)
    }

    private fun client(seen: Seen, answer: String, status: HttpStatusCode = HttpStatusCode.OK) = FinAiHttpClient.create(baseUrl = "https://api.example.com", tokens = PlanTokens(), logging = false, engine = MockEngine(handler(seen, answer, status)))

    @Test
    fun `it reads the debts with their source and link`() = runTest {
        val seen = Seen()
        val page = KtorDebtsRepository(client(seen, """{"currency": "CAD", "debts": [$VISA]}""")).list()

        assertEquals(HttpMethod.Get, seen.method)
        assertEquals("/debts", seen.path)
        val visa = page.debts.single()
        assertEquals(DebtKind.CREDIT_CARD, visa.kind)
        assertEquals(BalanceSource.STATEMENT, visa.balanceSource)
        assertEquals("a-card", visa.accountId)
        assertTrue(visa.fromSetup)
    }

    @Test
    fun `an edit sends only what changed and an explicit null to clear`() = runTest {
        val seen = Seen()
        KtorDebtsRepository(client(seen, VISA)).update(
            "d-visa",
            DebtChanges(interestRatePercent = "19.99", clearMinimumPayment = true, clearAccount = true),
        )

        assertEquals(HttpMethod.Patch, seen.method)
        assertEquals("/debts/d-visa", seen.path)
        val body = FinAiJson.parseToJsonElement(seen.body.orEmpty()).jsonObject
        assertEquals(setOf("interest_rate_percent", "minimum_payment", "account_id"), body.keys)
        assertEquals("19.99", body["interest_rate_percent"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["minimum_payment"])
        assertEquals(JsonNull, body["account_id"])
    }

    @Test
    fun `a promotion travels as a pair or is cleared as a pair`() {
        val set = debtPatchBody(DebtChanges(promoRatePercent = "0.00", promoEndsOn = "2027-05-01"))
        val cleared = debtPatchBody(DebtChanges(clearPromotion = true))
        val half = debtPatchBody(DebtChanges(promoRatePercent = "0.00"))

        assertEquals(setOf("promo_rate_percent", "promo_ends_on"), set.keys)
        assertEquals(JsonNull, cleared["promo_rate_percent"])
        assertEquals(JsonNull, cleared["promo_ends_on"])
        assertTrue(half.isEmpty(), "half a promotion is never sent: the server refuses it")
    }

    @Test
    fun `a new balance is sent as the balance`() {
        val body = debtPatchBody(DebtChanges(balance = "3500.00"))

        assertEquals(setOf("balance"), body.keys)
    }

    @Test
    fun `it reads the plan for a month`() = runTest {
        val seen = Seen()
        val plan = KtorPlanRepository(client(seen, PLAN)).plan("2026-11")

        assertEquals("/plan", seen.path)
        assertEquals("month=2026-11", seen.query)
        assertEquals(PlanStatus.READY, plan.status)
        assertEquals("1200.00", plan.leftover)
        assertEquals("g-house", plan.split.goals.single().goalId)
        assertTrue(plan.debts.single().getsExtraNow)
        assertEquals(PlanStrategy.SNOWBALL, plan.totals.otherStrategy)
    }

    @Test
    fun `this month sends no month`() = runTest {
        val seen = Seen()
        KtorPlanRepository(client(seen, PLAN)).plan(null)

        assertNull(seen.query?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `no buffer is sent as an explicit null`() = runTest {
        val seen = Seen()
        val saved = KtorPlanRepository(client(seen, PREFERENCES)).savePreferences(
            PlanPreferencesToSave(buffer = null, goalSharePercent = "25.00", rateLinePercent = "7.00", strategy = PlanStrategy.SNOWBALL),
        )

        assertEquals(HttpMethod.Put, seen.method)
        assertEquals("/plan/preferences", seen.path)
        val body = FinAiJson.parseToJsonElement(seen.body.orEmpty()).jsonObject
        assertEquals(JsonNull, body["buffer"])
        assertEquals("snowball", body["strategy"]?.jsonPrimitive?.content)
        assertTrue(saved.saved)
        assertNull(saved.buffer)
    }

    @Test
    fun `a buffer goal past the limit is the goal limit`() = runTest {
        val refused = """{"detail": {"code": "goal_limit_reached", "message": "x", "limit": 20}}"""

        val error = assertFailsWith<ApiException.GoalLimitReached> {
            KtorPlanRepository(client(Seen(), refused, HttpStatusCode.Conflict)).savePreferences(
                PlanPreferencesToSave(buffer = "1000.00", goalSharePercent = "20.00", rateLinePercent = "6.00", strategy = PlanStrategy.AVALANCHE),
            )
        }
        assertEquals(20, error.limit)
    }

    @Test
    fun `a region without a disclaimer is no disclaimer`() = runTest {
        val none = KtorPlanRepository(client(Seen(), """{"detail": {"code": "no_disclaimer"}}""", HttpStatusCode.NotFound)).disclaimer()

        assertNull(none)
    }

    @Test
    fun `an unknown kind or strategy still decodes`() = runTest {
        val odd = PLAN.replace("\"credit_card\"", "\"payday_loan\"").replace("\"strategy\": \"avalanche\"", "\"strategy\": \"other\"")
        val plan = KtorPlanRepository(client(Seen(), odd)).plan(null)

        assertEquals(DebtKind.UNKNOWN, plan.debts.single().kind)
        assertEquals(PlanStrategy.AVALANCHE, plan.preferences.strategy)
        assertFalse(plan.debts.single().missingRate)
    }
}
