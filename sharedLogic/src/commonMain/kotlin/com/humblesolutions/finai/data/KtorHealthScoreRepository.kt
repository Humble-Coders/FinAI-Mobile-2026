package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.HealthScore
import com.humblesolutions.finai.repository.HealthScoreRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** `/health-score` over Ktor — one implementation for both platforms. */
class KtorHealthScoreRepository internal constructor(
    private val http: HttpClient,
) : HealthScoreRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun current(): HealthScore = http.getJson("health-score")

    override fun close() = http.close()
}
