package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val consentJson = headersOf(HttpHeaders.ContentType, "application/json")

private class ConsentTokens : SessionTokenSource {
    @Throws(ApiException::class, CancellationException::class)
    override suspend fun currentToken(): String? = "token"

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun refreshedToken(rejected: String?): String? = null
}

/** The AI-processing consent endpoints the import screen asks before the first import (#31). */
class KtorAiConsentRepositoryTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun repository(status: HttpStatusCode, body: String) = KtorAiConsentRepository(
        FinAiHttpClient.create(
            "https://api.example.com",
            ConsentTokens(),
            false,
            engine = MockEngine { request ->
                seen += request
                respond(body, status, consentJson)
            },
        ),
    )

    @Test
    fun thePolicyIsReadWithItsVersion() = runTest {
        val policy = repository(
            HttpStatusCode.OK,
            """{"version":"ai-v1","body":"To read your statement…","effective_from":"2026-09-21T00:00:00Z"}""",
        ).policy()

        assertEquals("ai-v1", policy.version)
        assertEquals("To read your statement…", policy.body)
        assertEquals("/legal/ai-processing", seen.single().url.encodedPath)
    }

    @Test
    fun consentNamesTheVersionThatWasShown() = runTest {
        repository(HttpStatusCode.OK, """{"version":"ai-v1"}""").consent("ai-v1")

        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/legal/ai-processing/consent", request.url.encodedPath)
        val body = (request.body as TextContent).text
        assertTrue(body.contains("\"version\":\"ai-v1\""), body)
    }

    @Test
    fun aVersionNoLongerInForceIsItsOwnError() = runTest {
        val error = assertFailsWith<ApiException.AiPolicyChanged> {
            repository(
                HttpStatusCode.Conflict,
                """{"detail":{"code":"ai_policy_version_mismatch","current_version":"ai-v2"}}""",
            ).consent("ai-v1")
        }
        assertEquals("ai-v2", error.currentVersion)
    }

    @Test
    fun theStatusSaysWhetherThePersonConsents() = runTest {
        val status = repository(HttpStatusCode.OK, """{"consented":true,"version":"ai-v1"}""").status()

        assertTrue(status.consented)
        assertEquals("ai-v1", assertNotNull(status.version))
        assertEquals("/legal/ai-processing/consent", seen.single().url.encodedPath)
        assertEquals(HttpMethod.Get, seen.single().method)
    }
}
