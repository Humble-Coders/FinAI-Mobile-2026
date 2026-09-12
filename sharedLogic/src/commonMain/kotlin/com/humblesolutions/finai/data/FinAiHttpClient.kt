package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.plugins.plugin
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException

/** Builds the Ktor client every Render API repository uses. */
internal object FinAiHttpClient {

    const val CONNECT_TIMEOUT_MS: Long = 15_000

    /** Generous on purpose: Render's free tier can take most of a minute to wake a sleeping service. */
    const val REQUEST_TIMEOUT_MS: Long = 60_000

    /**
     * @param logging true only in debug builds. Even then it logs headers alone,
     *   with Authorization redacted — never bodies, which carry financial data.
     */
    fun create(
        baseUrl: String,
        tokens: SessionTokenSource,
        logging: Boolean,
        engine: HttpClientEngine? = null,
        logger: Logger = Logger.SIMPLE,
    ): HttpClient {
        val api = Url(baseUrl.trimEnd('/') + "/")
        val configure: HttpClientConfig<*>.() -> Unit = {
            // Statuses become ApiException in sendMapped, not Ktor exceptions.
            expectSuccess = false
            install(ContentNegotiation) { json(FinAiJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            defaultRequest {
                url(baseUrl.trimEnd('/') + "/")
                accept(ContentType.Application.Json)
            }
            if (logging) {
                install(Logging) {
                    this.logger = logger
                    level = LogLevel.HEADERS
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }
            install(SessionBearer) {
                this.tokens = tokens
                this.api = api
            }
        }
        val client = if (engine == null) HttpClient(configure) else HttpClient(engine, configure)

        client.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call
            // Another origin's 401 says nothing about our token: never refresh for
            // it, and never hand it the token on a retry.
            if (!request.url.build().sameOriginAs(api)) return@intercept call
            // The server rejected a token we believed valid — revoked, or clock skew
            // the expiry check could not see. Refresh once and retry; a second 401
            // is final, so this can never loop. The refresh is told which token was
            // rejected, so it can tell whether the session has already moved on.
            val rejected = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            val fresh = tokens.refreshedToken(rejected) ?: return@intercept call
            request.headers.remove(HttpHeaders.Authorization)
            request.bearerAuth(fresh)
            execute(request)
        }
        return client
    }
}

internal class SessionBearerConfig {
    var tokens: SessionTokenSource? = null

    /** The API's base URL. Its origin is the only place the token is sent. */
    var api: Url? = null
}

/**
 * Attaches a token that is valid now to every request for the API, and to
 * nothing else: an absolute URL on another origin (a signed upload URL, a
 * third-party call) must never receive the user's session token.
 * See [SessionTokenSource.currentToken].
 */
internal val SessionBearer = createClientPlugin("SessionBearer", ::SessionBearerConfig) {
    val tokens = requireNotNull(pluginConfig.tokens) { "SessionBearer needs a token source" }
    val api = requireNotNull(pluginConfig.api) { "SessionBearer needs the API's base URL" }
    onRequest { request, _ ->
        if (!request.url.build().sameOriginAs(api)) return@onRequest
        tokens.currentToken()?.let { request.bearerAuth(it) }
    }
}

/** Same scheme, host and port — the unit a bearer token may be sent to. */
private fun Url.sameOriginAs(other: Url): Boolean =
    protocol == other.protocol && host.equals(other.host, ignoreCase = true) && port == other.port

/**
 * Sends a request and maps every failure to [ApiException]: statuses through
 * [ApiErrorMapper], transport failures to [ApiException.Network].
 * [CancellationException] passes through untouched — a superseded call is not a failure.
 */
internal suspend fun HttpClient.sendMapped(request: suspend HttpClient.() -> HttpResponse): HttpResponse {
    val response = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException.Network(e)
    }
    if (!response.status.isSuccess()) {
        throw ApiErrorMapper.fromResponse(response.status.value, response.bodyAsTextOrEmpty())
    }
    return response
}

private suspend fun HttpResponse.bodyAsTextOrEmpty(): String = try {
    bodyAsText()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ""
}

internal suspend inline fun <reified T> HttpResponse.decoded(): T = try {
    body<T>()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw ApiException.Unexpected(status.value, e)
}

internal suspend inline fun <reified T> HttpClient.getJson(path: String): T =
    sendMapped { get(path) }.decoded()

internal suspend inline fun <reified B, reified T> HttpClient.putJson(path: String, body: B): T =
    sendMapped { put(path) { contentType(ContentType.Application.Json); setBody(body) } }.decoded()

internal suspend inline fun <reified B, reified T> HttpClient.postJson(path: String, body: B): T =
    sendMapped { post(path) { contentType(ContentType.Application.Json); setBody(body) } }.decoded()
