package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.BudgetStatus
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `GET /budgets/{month}` and both writes answer with, copied from the
 * backend's `BudgetOut` (Finance-backend `app/schemas/budget.py`). A field
 * renamed there shows up here as a decode failure rather than as a zero on
 * somebody's phone.
 */
private const val OCTOBER = """
{
  "status": "ready",
  "month": "2026-10-01",
  "currency": "CAD",
  "learning": null,
  "expected_income": "5000.00",
  "lines": [
    {"category_id": "11111111-1111-1111-1111-111111111111", "slug": "groceries", "name": "Groceries",
     "suggested": "440.00", "allocated": "500.00", "is_user_set": true, "spent": "380.00"}
  ],
  "savings": null,
  "debt": null,
  "total_allocated": "500.00",
  "total_spent": "380.00",
  "shortfall": null,
  "uncategorised_spent": "0.00",
  "uncategorised_count": 0
}
"""

private const val GROCERIES = "11111111-1111-1111-1111-111111111111"

private val budgetJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class BudgetTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/**
 * The three calls' exact requests, pinned against the backend's routes
 * (`app/api/budgets.py`). The paths and the `PUT` body are what a unit test
 * elsewhere cannot see: a wrong body key would come back as FastAPI's own
 * 422, whose `detail` is a list, so it maps to the generic rejection and
 * every save would read as refused.
 */
class KtorBudgetRepositoryTest {

    private fun repository(handler: MockRequestHandler) = KtorBudgetRepository(
        FinAiHttpClient.create(
            baseUrl = "https://api.example.com",
            tokens = BudgetTokens(),
            logging = false,
            engine = MockEngine(handler),
        ),
    )

    @Test
    fun `it reads a month by its YYYY-MM and decodes the whole budget`() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        val repository = repository { request ->
            method = request.method
            path = request.url.encodedPath
            respond(OCTOBER, HttpStatusCode.OK, budgetJsonHeaders)
        }

        val budget = repository.get("2026-10")

        assertEquals(HttpMethod.Get, method)
        assertEquals("/budgets/2026-10", path)
        assertEquals(BudgetStatus.READY, budget.status)
        assertEquals("2026-10-01", budget.month)
        assertEquals("500.00", budget.lines.single().allocated)
        assertTrue(budget.lines.single().isUserSet)
        assertNull(budget.shortfall)
        repository.close()
    }

    @Test
    fun `setting a line puts the amount as a decimal string under amount`() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
        val repository = repository { request ->
            method = request.method
            path = request.url.encodedPath
            body = (request.body as TextContent).text
            respond(OCTOBER, HttpStatusCode.OK, budgetJsonHeaders)
        }

        val budget = repository.setLine("2026-10", GROCERIES, "500.00")

        assertEquals(HttpMethod.Put, method)
        assertEquals("/budgets/2026-10/lines/$GROCERIES", path)
        assertEquals("""{"amount":"500.00"}""", body)
        // The write answers with the whole month, which is what the screens rely on.
        assertEquals("380.00", budget.totalSpent)
        repository.close()
    }

    @Test
    fun `resetting a line deletes its override`() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        val repository = repository { request ->
            method = request.method
            path = request.url.encodedPath
            respond(OCTOBER, HttpStatusCode.OK, budgetJsonHeaders)
        }

        repository.resetLine("2026-10", GROCERIES)

        assertEquals(HttpMethod.Delete, method)
        assertEquals("/budgets/2026-10/lines/$GROCERIES/override", path)
        repository.close()
    }

    @Test
    fun `a refusal arrives as its own error rather than a generic one`() = runTest {
        val repository = repository {
            respond(
                """{"detail":{"code":"not_budgetable","field":"category_id","message":"transfers is money moving, not money spent."}}""",
                HttpStatusCode.UnprocessableEntity,
                budgetJsonHeaders,
            )
        }

        val error = assertFailsWith<ApiException.NotBudgetable> {
            repository.setLine("2026-10", GROCERIES, "10")
        }
        assertEquals("category_id", error.field)
        repository.close()
    }

    @Test
    fun `resetting a line that is not there is not found`() = runTest {
        val repository = repository {
            respond("""{"detail":{"code":"not_found"}}""", HttpStatusCode.NotFound, budgetJsonHeaders)
        }

        assertFailsWith<ApiException.NotFound> { repository.resetLine("2026-10", GROCERIES) }
        repository.close()
    }
}
