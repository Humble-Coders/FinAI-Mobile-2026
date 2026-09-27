package com.humblesolutions.finai.data

import com.humblesolutions.finai.config.StatementLimits
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.repository.SessionTokenSource
import com.humblesolutions.finai.repository.StatementImportRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /statements/parse` over Ktor — one implementation for both platforms.
 *
 * The body is text the device already redacted, plus the statement period as
 * its own field. The file itself is never part of this request, and there is no
 * upload endpoint to send it to.
 */
class KtorStatementImportRepository internal constructor(
    private val http: HttpClient,
) : StatementImportRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun parse(upload: StatementUpload): ParsedStatement =
        // Its own timeouts, not the client's default minute. The server is
        // allowed three minutes to parse, and giving up before it does leaves
        // the import finishing unseen while the user is told it failed.
        //
        // BOTH bounds, and that is the whole point: while the server parses,
        // the connection is silent — nothing is sent until the handler
        // returns — so a socket timeout left at the default kills the request
        // at a minute however long the request timeout is.
        http.postJson("statements/parse", upload) {
            timeout {
                requestTimeoutMillis = StatementLimits.PARSE_TIMEOUT_MS
                socketTimeoutMillis = StatementLimits.PARSE_TIMEOUT_MS
            }
        }

    override fun close() = http.close()
}
