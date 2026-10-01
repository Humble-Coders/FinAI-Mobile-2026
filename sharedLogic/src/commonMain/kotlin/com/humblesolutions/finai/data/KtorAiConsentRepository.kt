package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.AiConsentAccepted
import com.humblesolutions.finai.model.AiConsentIn
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** The AI-processing consent endpoints over Ktor — one implementation for both platforms. */
class KtorAiConsentRepository internal constructor(
    private val http: HttpClient,
) : AiConsentRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun policy(): AiPolicy = http.getJson("legal/ai-processing")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun consent(version: String) {
        http.postJson<AiConsentIn, AiConsentAccepted>("legal/ai-processing/consent", AiConsentIn(version))
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun status(): AiConsentStatus = http.getJson("legal/ai-processing/consent")

    override fun close() = http.close()
}
