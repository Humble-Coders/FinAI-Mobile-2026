package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ReviewReason
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.model.TransactionPatch
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val reviewJson = headersOf(HttpHeaders.ContentType, "application/json")

private const val PAGE = """{"rows":[
  {"id":"t-1","account_id":"a-1","occurred_on":"2026-09-28","amount":"12.40","currency":"CAD",
   "direction":"debit","description":"TIM HORTONS","merchant":"Tim Hortons","category_id":null,
   "needs_review":true,"review_reason":"unknown_category","extraction_confidence":62,
   "duplicate_of":{"id":"t-9","occurred_on":"2026-09-28","amount":"12.40","description":"TIM HORTONS"}}
],"next_cursor":"MjAyNi0wOS0yODp0LTE"}"""

private class ReviewTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/** The review endpoints: what goes on the wire, and what comes back (#32). */
class KtorReviewRepositoryTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun repository(status: HttpStatusCode = HttpStatusCode.OK, body: String = PAGE) =
        KtorTransactionsRepository(
            FinAiHttpClient.create(
                "https://api.example.com",
                ReviewTokens(),
                false,
                engine = MockEngine { request ->
                    seen += request
                    respond(body, status, reviewJson)
                },
            ),
        )

    private fun sent() = (assertNotNull(seen.lastOrNull()).body as TextContent).text

    @Test
    fun aPageDecodesWithItsReasonAndWhatItMatched() = runTest {
        val page = repository().review()

        assertEquals("/transactions/review", assertNotNull(seen.single()).url.encodedPath)
        val row = page.rows.single()
        assertEquals(ReviewReason.UNKNOWN_CATEGORY, row.reviewReason)
        assertEquals(62, row.extractionConfidence)
        assertEquals("t-9", assertNotNull(row.duplicateOf).id)
        assertNull(row.categoryId)
        assertTrue(row.needsReview)
        assertEquals("MjAyNi0wOS0yODp0LTE", page.nextCursor)
    }

    @Test
    fun theCursorIsSentBackToContinue() = runTest {
        repository(body = """{"rows":[],"next_cursor":null}""").review("MjAyNi0wOS0yODp0LTE")

        val url = assertNotNull(seen.single()).url
        assertEquals("/transactions/review", url.encodedPath)
        assertEquals("MjAyNi0wOS0yODp0LTE", url.parameters["cursor"])
    }

    @Test
    fun theLastPageHasNoCursor() = runTest {
        val page = repository(body = """{"rows":[],"next_cursor":null}""").review()

        assertNull(page.nextCursor)
        assertEquals(emptyList(), page.rows)
    }

    @Test
    fun aCorrectionPatchesOnlyWhatChanged() = runTest {
        val outcome = repository(
            body = """{"transaction":{"id":"t-1","amount":"12.40","currency":"CAD","needs_review":false,
                "source":"upload"},"import_finished":false,"rule_recorded":true,"recategorized":3}""",
        ).correct("t-1", TransactionPatch(categoryId = "cat-2"))

        val request = assertNotNull(seen.single())
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/transactions/t-1", request.url.encodedPath)
        val body = sent()
        assertTrue(body.contains("\"category_id\":\"cat-2\""), body)
        // Untouched fields are left off entirely, not sent as null.
        assertFalse(body.contains("amount"), body)
        assertFalse(body.contains("occurred_on"), body)
        assertEquals(3, outcome.recategorized)
        assertTrue(outcome.ruleRecorded)
        assertFalse(outcome.transaction.needsReview)
    }

    @Test
    fun anAmountCorrectionTravelsAsADecimalString() = runTest {
        repository(
            body = """{"transaction":{"id":"t-1","amount":"1234.50","currency":"CAD"},"recategorized":0}""",
        ).correct("t-1", TransactionPatch(amount = "1234.50", direction = TransactionDirection.CREDIT))

        val body = sent()
        assertTrue(body.contains("\"amount\":\"1234.50\""), body)
        assertTrue(body.contains("\"direction\":\"credit\""), body)
    }

    @Test
    fun confirmingOneRowNamesItInThePathAndSendsNoBody() = runTest {
        repository(
            body = """{"transaction":{"id":"t-1","needs_review":false},"import_finished":true}""",
        ).confirm("t-1")

        val request = assertNotNull(seen.single())
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/transactions/t-1/confirm", request.url.encodedPath)
    }

    @Test
    fun confirmingManySendsTheIdsAndReportsWhatActuallyLeft() = runTest {
        val outcome = repository(
            body = """{"confirmed":16,"imports_finished":["i-1"]}""",
        ).confirmAll(listOf("t-1", "t-2"))

        val request = assertNotNull(seen.single())
        assertEquals("/transactions/confirm", request.url.encodedPath)
        assertTrue(sent().contains("\"ids\":[\"t-1\",\"t-2\"]"), sent())
        assertEquals(16, outcome.confirmed)
        assertEquals(listOf("i-1"), outcome.importsFinished)
    }

    @Test
    fun deletingRemovesTheRow() = runTest {
        val outcome = repository(body = """{"import_finished":true}""").delete("t-1")

        val request = assertNotNull(seen.single())
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/transactions/t-1", request.url.encodedPath)
        assertTrue(outcome.importFinished)
    }

    @Test
    fun aCorrectionThatWouldDuplicateIsItsOwnError() = runTest {
        val error = assertFailsWith<ApiException.WouldDuplicate> {
            repository(
                HttpStatusCode.Conflict,
                """{"detail":{"code":"would_duplicate","duplicate_of":"t-9","message":"..."}}""",
            ).correct("t-1", TransactionPatch(amount = "5.25"))
        }

        assertEquals("t-9", error.duplicateOfId)
    }

    @Test
    fun aCategoryIsCreatedAndATakenNameNamesTheOneThatExists() = runTest {
        val made = KtorCategoriesRepository(
            FinAiHttpClient.create(
                "https://api.example.com",
                ReviewTokens(),
                false,
                engine = MockEngine { request ->
                    seen += request
                    respond(
                        """{"id":"c-9","slug":"side_business","name":"Side business","is_system":false}""",
                        HttpStatusCode.Created,
                        reviewJson,
                    )
                },
            ),
        ).create("Side business")

        assertEquals("/categories", assertNotNull(seen.single()).url.encodedPath)
        assertTrue(sent().contains("\"name\":\"Side business\""), sent())
        assertFalse(made.isSystem)
        assertEquals("c-9", made.id)
    }

    @Test
    fun aCategoryNameAlreadyUsedCarriesTheExistingId() = runTest {
        val error = assertFailsWith<ApiException.CategoryExists> {
            KtorCategoriesRepository(
                FinAiHttpClient.create(
                    "https://api.example.com",
                    ReviewTokens(),
                    false,
                    engine = MockEngine {
                        respond(
                            """{"detail":{"code":"category_exists","category_id":"c-1"}}""",
                            HttpStatusCode.Conflict,
                            reviewJson,
                        )
                    },
                ),
            ).create("Groceries")
        }

        assertEquals("c-1", error.categoryId)
    }

    @Test
    fun aNameWithNoLettersIsItsOwnError() = runTest {
        assertFailsWith<ApiException.UnnamedCategory> {
            KtorCategoriesRepository(
                FinAiHttpClient.create(
                    "https://api.example.com",
                    ReviewTokens(),
                    false,
                    engine = MockEngine {
                        respond(
                            """{"detail":{"code":"unnamed_category","field":"name"}}""",
                            HttpStatusCode.UnprocessableEntity,
                            reviewJson,
                        )
                    },
                ),
            ).create("!!!")
        }
    }
}
