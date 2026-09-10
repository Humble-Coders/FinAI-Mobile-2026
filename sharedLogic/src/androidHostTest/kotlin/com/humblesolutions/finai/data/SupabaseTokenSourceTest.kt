package com.humblesolutions.finai.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The real token source on a real supabase-kt client, with only the network
 * mocked. In androidHostTest because switching off Android lifecycle callbacks
 * is an Android-only Auth setting.
 */
class SupabaseTokenSourceTest {

    /** Token grants the client asked Supabase for — each one spends a refresh token. */
    private var grants = 0

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val client = createSupabaseClient("https://project.supabase.test", "publishable-key") {
        defaultLogLevel = LogLevel.NONE
        httpEngine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/token")) {
                grants++
                respond(
                    """{"access_token":"T2","refresh_token":"R2","expires_in":3600,"token_type":"bearer",""" +
                        """"user":{"id":"u1","aud":"authenticated"}}""",
                    HttpStatusCode.OK,
                    json,
                )
            } else {
                respond("""{"id":"u1","aud":"authenticated"}""", HttpStatusCode.OK, json)
            }
        }
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            autoLoadFromStorage = false
            autoSaveToStorage = false
            alwaysAutoRefresh = false
            enableLifecycleCallbacks = false
        }
    }

    private suspend fun signInWithT1() = client.auth.importSession(
        UserSession(accessToken = "T1", refreshToken = "R1", expiresIn = 3600, tokenType = "bearer", user = null),
        autoRefresh = false,
    )

    @Test
    fun `refreshes when the rejected token is still the current one`() = runTest {
        signInWithT1()
        assertEquals("T2", SupabaseTokenSource(client).refreshedToken("T1"))
        assertEquals(1, grants)
    }

    @Test
    fun `hands back a newer token instead of refreshing again`() = runTest {
        signInWithT1()
        // The server turned down T0, but another request already moved the session on to T1.
        assertEquals("T1", SupabaseTokenSource(client).refreshedToken("T0"))
        assertEquals(0, grants)
    }

    @Test
    fun `refreshes once for two requests rejected together`() = runTest {
        signInWithT1()
        val tokens = SupabaseTokenSource(client)
        val results = List(2) { async { tokens.refreshedToken("T1") } }.awaitAll()
        assertEquals(listOf<String?>("T2", "T2"), results)
        assertEquals(1, grants)
    }

    @Test
    fun `a late 401 for a token already replaced does not refresh again`() = runTest {
        signInWithT1()
        val tokens = SupabaseTokenSource(client)
        // Request A was rejected with T1 and refreshed the session to T2.
        assertEquals("T2", tokens.refreshedToken("T1"))
        // Request B was also sent with T1; its 401 arrives only now.
        assertEquals("T2", tokens.refreshedToken("T1"))
        assertEquals(1, grants)
    }

    @Test
    fun `returns null when signed out`() = runTest {
        assertNull(SupabaseTokenSource(client).refreshedToken("T1"))
        assertEquals(0, grants)
    }
}
