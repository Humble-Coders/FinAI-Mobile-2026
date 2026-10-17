package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** `/plan` and `/plan/preferences` over Ktor — one implementation for both platforms. */
class KtorPlanRepository internal constructor(
    private val http: HttpClient,
) : PlanRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun plan(month: String?): Plan = http.getJson("plan") { month?.let { parameter("month", it) } }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences = http.putJson("plan/preferences", preferencesBody(choices))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun disclaimer(): Terms? = try {
        http.getJson<Terms>("legal/disclaimer")
    } catch (e: ApiException.NotFound) {
        // `no_disclaimer`: the region has none. Shown as no line, not an error.
        null
    }

    override fun close() = http.close()
}

/** The `PUT /plan/preferences` body. A null buffer is "no buffer", so it is written, never dropped. */
internal fun preferencesBody(choices: PlanPreferencesToSave): JsonObject = buildJsonObject {
    if (choices.buffer == null) put("buffer", JsonNull) else put("buffer", choices.buffer)
    put("goal_share_percent", choices.goalSharePercent)
    put("rate_line_percent", choices.rateLinePercent)
    put("strategy", choices.strategy.wire)
}
