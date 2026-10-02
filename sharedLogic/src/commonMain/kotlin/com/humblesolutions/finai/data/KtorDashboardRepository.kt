package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlin.coroutines.cancellation.CancellationException

/** `GET /dashboard` over Ktor — one implementation for both platforms. */
class KtorDashboardRepository internal constructor(
    private val http: HttpClient,
) : DashboardRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun read(month: String?): Dashboard = http.getJson("dashboard") {
        // Omitted rather than sent empty: the server reads a missing month as
        // "now", and "" would be a malformed one.
        if (month != null) parameter("month", month)
    }

    override fun close() = http.close()
}
