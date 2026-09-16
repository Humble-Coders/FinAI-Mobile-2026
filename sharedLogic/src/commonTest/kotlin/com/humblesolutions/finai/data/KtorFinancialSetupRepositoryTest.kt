package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Debt
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SAVED = """
    {"currency":"CAD","income":"4000.00","monthly_expense":"2500.00",
     "debts":[{"name":"Card","balance":"1500.00","minimum_payment":null,"interest_rate_percent":null}],
     "investments":[],"obligations":[]}
"""
private val setupJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private class SetupTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/** The wizard's two calls: what comes back, what goes out, and how refusals read. */
class KtorFinancialSetupRepositoryTest {

    private fun repository(handler: MockRequestHandler) =
        KtorFinancialSetupRepository(
            FinAiHttpClient.create(
                baseUrl = "https://api.example.com",
                tokens = SetupTokens(),
                logging = false,
                engine = MockEngine(handler),
            ),
        )

    @Test
    fun `it reads what is saved`() = runTest {
        val repository = repository { respond(SAVED, HttpStatusCode.OK, setupJsonHeaders) }

        val setup = repository.get()

        assertEquals("CAD", setup.currency)
        assertEquals("4000.00", setup.income)
        assertEquals(Debt("Card", "1500.00", null, null), setup.debts.single())
        repository.close()
    }

    @Test
    fun `a save sends the whole wizard and never the currency`() = runTest {
        var body: String? = null
        var method: HttpMethod? = null
        var path: String? = null
        val repository = repository { request ->
            method = request.method
            path = request.url.encodedPath
            body = (request.body as TextContent).text
            respond(SAVED, HttpStatusCode.OK, setupJsonHeaders)
        }

        repository.save(
            FinancialSetup(currency = "CAD", income = "4000.00", monthlyExpense = "2500.00"),
        )

        assertEquals(HttpMethod.Put, method)
        assertEquals("/financial-setup", path)
        assertTrue(body!!.contains("\"monthly_expense\":\"2500.00\""), body!!)
        // The server decides the currency from the household's region; sending it
        // back would suggest a client could change it.
        assertFalse(body!!.contains("currency"), body!!)
        repository.close()
    }

    @Test
    fun `an amount the server refuses names the field that was wrong`() = runTest {
        val repository = repository {
            respond(
                """{"detail":{"code":"invalid_amount","field":"debts.0.balance","message":"not a number"}}""",
                HttpStatusCode.UnprocessableEntity,
                setupJsonHeaders,
            )
        }

        val failure = assertFailsWith<ApiException.InvalidAmount> {
            repository.save(FinancialSetup(income = "4000.00"))
        }

        assertEquals("debts.0.balance", failure.field)
        repository.close()
    }

    @Test
    fun `a signed out caller is unauthorized rather than a decoding failure`() = runTest {
        val repository = repository { respond("", HttpStatusCode.Unauthorized, setupJsonHeaders) }

        assertFailsWith<ApiException.Unauthorized> { repository.get() }
        repository.close()
    }
}
