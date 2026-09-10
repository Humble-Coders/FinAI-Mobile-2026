package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** `GET /capabilities` over Ktor — one implementation for Android and iOS. Owns its client: call [close]. */
class KtorCapabilitiesRepository internal constructor(
    private val http: HttpClient,
) : CapabilitiesRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    override suspend fun fetch(): Capabilities = http.getJson("capabilities")

    override fun close() = http.close()
}
