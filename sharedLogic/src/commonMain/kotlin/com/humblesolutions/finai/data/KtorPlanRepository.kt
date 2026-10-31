package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.DiagnosticBody
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanSplitEdit
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun diagnostics(): PlanDiagnostics = http.getJson("plan/diagnostics")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun runDiagnostic(kind: String, savingsTarget: String?, savingsNow: String?, monthlySavings: String?): PlanDiagnostic = http.postJson("plan/diagnostics/$kind", DiagnosticBody(savingsTarget, savingsNow, monthlySavings))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun editSplit(edit: PlanSplitEdit): Plan = http.putJson("plan/split", splitEditBody(edit))

    override fun close() = http.close()
}

/** The `PUT /plan/preferences` body. Without weights the saved ones are kept. */
internal fun preferencesBody(choices: PlanPreferencesToSave): JsonObject = buildJsonObject {
    put("strategy", choices.strategy.wire)
    choices.weights?.let { weights ->
        put(
            "weights",
            buildJsonArray {
                weights.forEach { weight ->
                    add(
                        buildJsonObject {
                            put("category", weight.category.wire)
                            put("percent", weight.percent)
                        },
                    )
                }
            },
        )
    }
}

/** The `PUT /plan/split` body. */
internal fun splitEditBody(edit: PlanSplitEdit): JsonObject = buildJsonObject {
    put("row", edit.row.wire)
    edit.goalId?.let { put("goal_id", it) }
    put("amount", edit.amount)
    put("apply", edit.apply)
}
