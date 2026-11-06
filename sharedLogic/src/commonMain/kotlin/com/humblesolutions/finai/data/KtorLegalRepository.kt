package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.AiConsentAccepted
import com.humblesolutions.finai.model.AiConsentIn
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.LegalRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** The legal endpoints Profile reads and writes, over Ktor. */
class KtorLegalRepository internal constructor(
    private val http: HttpClient,
) : LegalRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun terms(): Terms = http.getJson("legal/terms")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun disclaimer(): Terms? = try {
        http.getJson<Terms>("legal/disclaimer")
    } catch (e: ApiException.NotFound) {
        null
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun aiPolicy(): AiPolicy = http.getJson("legal/ai-processing")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun aiStatus(): AiConsentStatus = http.getJson("legal/ai-processing/consent")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun consentToAi(version: String) {
        http.postJson<AiConsentIn, AiConsentAccepted>("legal/ai-processing/consent", AiConsentIn(version))
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun withdrawAi(): AiConsentStatus = http.deleteJson("legal/ai-processing/consent")

    override fun close() = http.close()
}
