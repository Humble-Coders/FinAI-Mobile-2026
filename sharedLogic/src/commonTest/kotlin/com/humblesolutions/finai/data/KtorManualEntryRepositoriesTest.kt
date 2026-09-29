package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.NewAccount
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = headersOf(HttpHeaders.ContentType, "application/json")

private class Tokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

private const val SAVED = """{"id":"tx-1","account_id":"acct-1","occurred_on":"2026-09-28",
    "amount":"12.50","currency":"CAD","direction":"debit","description":"Tim Hortons",
    "merchant":"Tim Hortons","category_id":"cat-1","source":"manual"}"""

private val entry = NewTransaction(
    accountId = "acct-1",
    occurredOn = "2026-09-28",
    amount = "12.50",
    direction = TransactionDirection.DEBIT,
    description = "Tim Hortons",
)

/** What the three manual-entry calls send, and how their refusals read. */
class KtorManualEntryRepositoriesTest {

    private var seen: HttpRequestData? = null

    private fun client(status: HttpStatusCode, body: String) = FinAiHttpClient.create(
        baseUrl = "https://api.example.com",
        tokens = Tokens(),
        logging = false,
        engine = MockEngine { request ->
            seen = request
            respond(body, status, json)
        },
    )

    private fun sentBody() =
        Json.parseToJsonElement((assertNotNull(seen).body as TextContent).text).jsonObject

    @Test
    fun aTransactionIsPostedWithTheApiSFieldNames() = runTest {
        KtorTransactionsRepository(client(HttpStatusCode.Created, SAVED)).create(entry)

        assertEquals(HttpMethod.Post, assertNotNull(seen).method)
        assertTrue(assertNotNull(seen).url.encodedPath.endsWith("/transactions"))
        val body = sentBody()
        assertEquals("\"acct-1\"", body["account_id"].toString())
        assertEquals("\"2026-09-28\"", body["occurred_on"].toString())
        assertEquals("\"12.50\"", body["amount"].toString())
        assertEquals("\"debit\"", body["direction"].toString())
    }

    @Test
    fun noCategoryAndNoOverrideAreLeftOffTheRequest() = runTest {
        // Absent rather than null or false: with no category the backend
        // categorizes, and an override must never be sent unless asked for.
        KtorTransactionsRepository(client(HttpStatusCode.Created, SAVED)).create(entry)

        val body = sentBody()
        assertFalse("category_id" in body)
        assertFalse("allow_duplicate" in body)
    }

    @Test
    fun keepItAnywaySendsTheOverride() = runTest {
        KtorTransactionsRepository(client(HttpStatusCode.Created, SAVED))
            .create(entry.copy(allowDuplicate = true))

        assertEquals("true", sentBody()["allow_duplicate"].toString())
    }

    @Test
    fun theAmountRoundTripsAsADecimalString() = runTest {
        val saved = KtorTransactionsRepository(client(HttpStatusCode.Created, SAVED))
            .create(entry)

        assertEquals("12.50", saved.amount)
        assertEquals("manual", saved.source)
    }

    @Test
    fun aDuplicateCarriesWhatItMatched() = runTest {
        val refusal = """{"detail":{"code":"duplicate_transaction","duplicate_of":{
            "id":"tx-9","occurred_on":"2026-09-28","amount":"12.50",
            "description":"TIM HORTONS #4821"}}}"""

        val error = assertFailsWith<ApiException.DuplicateTransaction> {
            KtorTransactionsRepository(client(HttpStatusCode.Conflict, refusal)).create(entry)
        }

        val match = assertNotNull(error.match)
        assertEquals("tx-9", match.id)
        assertEquals("12.50", match.amount)
        assertEquals("TIM HORTONS #4821", match.description)
    }

    @Test
    fun aDuplicateWithoutItsMatchIsStillADuplicate() = runTest {
        // A server predating the amended 409 still answers "duplicate" — the
        // screen can say so, which beats a generic error.
        val error = assertFailsWith<ApiException.DuplicateTransaction> {
            KtorTransactionsRepository(
                client(HttpStatusCode.Conflict, """{"detail":{"code":"duplicate_transaction"}}"""),
            ).create(entry)
        }

        assertNull(error.match)
    }

    @Test
    fun aTakenAccountNameIsItsOwnError() = runTest {
        val error = assertFailsWith<ApiException> {
            KtorAccountsRepository(
                client(HttpStatusCode.Conflict, """{"detail":{"code":"duplicate_account_name"}}"""),
            ).create(NewAccount(name = "RBC", kind = AccountKind.CHEQUING))
        }

        assertIs<ApiException.DuplicateAccountName>(error)
    }

    @Test
    fun accountsDecodeAndAnUnknownKindDoesNotBreakTheList() = runTest {
        val listed = KtorAccountsRepository(
            client(
                HttpStatusCode.OK,
                """[{"id":"a1","name":"RBC","kind":"chequing","currency":"CAD"},
                    {"id":"a2","name":"Wallet","kind":"crypto_wallet","currency":"CAD"}]""",
            ),
        ).list()

        assertEquals(listOf(AccountKind.CHEQUING, AccountKind.UNKNOWN), listed.map { it.kind })
    }

    @Test
    fun aNewAccountSendsNoCurrency() = runTest {
        // The server takes the currency from the household's region.
        KtorAccountsRepository(
            client(
                HttpStatusCode.Created,
                """{"id":"a1","name":"RBC","kind":"chequing","currency":"CAD"}""",
            ),
        ).create(NewAccount(name = "RBC", kind = AccountKind.SAVINGS))

        val body = sentBody()
        assertFalse("currency" in body)
        assertEquals("\"savings\"", body["kind"].toString())
    }

    @Test
    fun aChequingAccountSendsItsKind() = runTest {
        // CHEQUING is the default kind and the likeliest one, which is exactly
        // the value a default-dropping encoder would leave off the wire.
        KtorAccountsRepository(
            client(
                HttpStatusCode.Created,
                """{"id":"a1","name":"RBC","kind":"chequing","currency":"CAD"}""",
            ),
        ).create(NewAccount(name = "RBC", kind = AccountKind.CHEQUING))

        assertEquals("\"chequing\"", sentBody()["kind"].toString())
        assertEquals("\"RBC\"", sentBody()["name"].toString())
    }

    @Test
    fun everyRequiredFieldIsSentEvenAtItsDefault() = runTest {
        // A request built entirely from default values still carries every
        // field the API requires — the check that would have caught debits
        // going out with no direction.
        KtorTransactionsRepository(client(HttpStatusCode.Created, SAVED)).create(NewTransaction())

        val body = sentBody()
        for (field in listOf("account_id", "occurred_on", "amount", "direction", "description")) {
            assertTrue(field in body, "$field was left off the request")
        }
    }

    @Test
    fun categoriesDecodeWhetherTheyAreSharedOrTheHousehold_s() = runTest {
        val listed = KtorCategoriesRepository(
            client(
                HttpStatusCode.OK,
                """[{"id":"c1","slug":"dining","name":"Dining","is_system":true},
                    {"id":"c2","slug":"side_business","name":"Side business","is_system":false}]""",
            ),
        ).list()

        assertEquals(listOf(true, false), listed.map { it.isSystem })
    }

    @Test
    fun aDirectionThisBuildDoesNotKnowIsNotGuessed() = runTest {
        val saved = KtorTransactionsRepository(
            client(HttpStatusCode.Created, SAVED.replace("\"debit\"", "\"refund\"")),
        ).create(entry)

        assertEquals(TransactionDirection.UNKNOWN, saved.direction)
    }
}
