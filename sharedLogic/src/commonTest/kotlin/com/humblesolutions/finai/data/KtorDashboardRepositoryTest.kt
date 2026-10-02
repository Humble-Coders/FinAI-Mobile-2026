package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exact payload `GET /dashboard` returns, copied from the backend's own
 * schema. This is where a field renamed on the server shows up — as a decode
 * failure here rather than as a zero on somebody's phone.
 */
private const val AUGUST = """
{
  "month": "2026-08-01",
  "currency": "CAD",
  "net": "1500.00",
  "previous_net": "600.00",
  "income": {"actual": "5000.00", "expected": "5200.00"},
  "expenses": {"actual": "3500.00", "expected": "3000.00"},
  "investments": {"balance": "40000.00", "moved": "500.00"},
  "debts": {"balance": "12000.00", "moved": "400.00"},
  "commitments": [
    {"name": "Rent", "expected": "1800.00",
     "match": {"id": "c0ffee", "occurred_on": "2026-08-02", "amount": "1800.00",
               "description": "HARBOURVIEW PROPERTIES RENT"}},
    {"name": "Car payment", "expected": "400.00", "match": null}
  ],
  "trend": [
    {"month": "2026-06-01", "net": "300.00"},
    {"month": "2026-07-01", "net": null},
    {"month": "2026-08-01", "net": "1500.00"}
  ],
  "pending_review": 2
}
"""

private val dashboardJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class DashboardTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

class KtorDashboardRepositoryTest {

    private fun repository(handler: MockRequestHandler) = KtorDashboardRepository(
        FinAiHttpClient.create(
            baseUrl = "https://api.example.com",
            tokens = DashboardTokens(),
            logging = false,
            engine = MockEngine(handler),
        ),
    )

    @Test
    fun `it decodes the month the server sent`() = runTest {
        val repository = repository { respond(AUGUST, HttpStatusCode.OK, dashboardJsonHeaders) }

        val month = repository.read("2026-08")

        assertEquals("2026-08-01", month.month)
        assertEquals("CAD", month.currency)
        assertEquals("1500.00", month.net)
        assertEquals("600.00", month.previousNet)
        assertEquals("5000.00", month.income.actual)
        assertEquals("5200.00", month.income.expected)
        assertEquals("40000.00", month.investments.balance)
        assertEquals("500.00", month.investments.moved)
        assertEquals(2, month.pendingReview)
        repository.close()
    }

    @Test
    fun `a commitment keeps its match and the absence of one`() = runTest {
        val repository = repository { respond(AUGUST, HttpStatusCode.OK, dashboardJsonHeaders) }

        val month = repository.read("2026-08")

        val rent = month.commitments.first { it.name == "Rent" }
        assertTrue(rent.wasSeen)
        assertEquals("2026-08-02", rent.match?.occurredOn)
        assertEquals("1800.00", rent.match?.amount)

        val car = month.commitments.first { it.name == "Car payment" }
        assertFalse(car.wasSeen, "a null match is not seen, and is never paid")
        repository.close()
    }

    @Test
    fun `a month with no rows stays null through the wire`() = runTest {
        // The whole reason the field is nullable: if this decoded as "0" the
        // chart would draw a bar for a month nobody recorded.
        val repository = repository { respond(AUGUST, HttpStatusCode.OK, dashboardJsonHeaders) }

        val month = repository.read("2026-08")

        val july = month.trend.first { it.month == "2026-07-01" }
        assertNull(july.net)
        assertFalse(july.hasData)
        repository.close()
    }

    @Test
    fun `the month asked for goes on the query string`() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        var query: String? = null
        val repository = repository { request ->
            method = request.method
            path = request.url.encodedPath
            query = request.url.parameters["month"]
            respond(AUGUST, HttpStatusCode.OK, dashboardJsonHeaders)
        }

        repository.read("2026-08")

        assertEquals(HttpMethod.Get, method)
        assertEquals("/dashboard", path)
        assertEquals("2026-08", query)
        repository.close()
    }

    @Test
    fun `no month means now and sends no parameter at all`() = runTest {
        // Not an empty one: the server reads a missing month as "now", and ""
        // is a malformed month it would refuse.
        var sent: Set<String> = emptySet()
        val repository = repository { request ->
            sent = request.url.parameters.names()
            respond(AUGUST, HttpStatusCode.OK, dashboardJsonHeaders)
        }

        repository.read(null)

        assertFalse("month" in sent)
        repository.close()
    }

    @Test
    fun `a payload missing everything optional still decodes`() = runTest {
        // Defaults on every field, so a server that has not shipped a field yet
        // gives an empty dashboard rather than a crash on somebody's phone.
        val repository = repository {
            respond("""{"month":"2026-08-01","currency":"CAD"}""", HttpStatusCode.OK, dashboardJsonHeaders)
        }

        val month = repository.read("2026-08")

        assertEquals("0", month.net)
        assertNull(month.previousNet)
        assertTrue(month.commitments.isEmpty())
        assertTrue(month.isEmpty)
        repository.close()
    }
}
