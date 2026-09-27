package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.repository.SessionTokenSource
import com.humblesolutions.finai.repository.StatementImportRepository
import io.ktor.client.HttpClient
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
        http.postJson("statements/parse", upload)

    override fun close() = http.close()
}
