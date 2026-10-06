package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.model.GoalChanges
import com.humblesolutions.finai.model.GoalsPage
import com.humblesolutions.finai.model.NewGoal
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.GoalsRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** `/goals` and `/legal/disclaimer` over Ktor — one implementation for both platforms. */
class KtorGoalsRepository internal constructor(
    private val http: HttpClient,
) : GoalsRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun list(): GoalsPage = http.getJson("goals")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun create(goal: NewGoal): Goal = http.postJson("goals", NewGoalBody.of(goal))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun update(id: String, changes: GoalChanges): Goal = http.patchJson("goals/$id", patchBody(changes))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun add(id: String, amount: String): Goal = http.postJson("goals/$id/add", AddMoneyBody(amount))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun reorder(ids: List<String>): GoalsPage = http.putJson("goals/order", OrderBody(ids))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun delete(id: String) {
        // 204: there is no body to decode.
        http.sendMapped { delete("goals/$id") }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun disclaimer(): Terms? = try {
        http.getJson<Terms>("legal/disclaimer")
    } catch (e: ApiException.NotFound) {
        // `no_disclaimer`: the region has none. Shown as no line, not an error.
        null
    }

    override fun close() = http.close()
}

/**
 * The `PATCH /goals/{id}` body, written field by field.
 *
 * Not a `@Serializable` class: the shared JSON drops nulls, and here a null
 * is the instruction — the server reads an absent field as "unchanged" and
 * only an explicit null as "remove it". A field left alone is simply not put.
 */
internal fun patchBody(changes: GoalChanges): JsonObject = buildJsonObject {
    changes.name?.let { put("name", it) }
    changes.horizon?.let { put("horizon", it.wire) }
    changes.target?.let { put("target", it) }
    changes.saved?.let { put("saved", it) }
    when {
        changes.clearKind -> put("kind", JsonNull)
        changes.kind != null -> put("kind", changes.kind.wire)
    }
    when {
        changes.clearTargetDate -> put("target_date", JsonNull)
        changes.targetDate != null -> put("target_date", changes.targetDate)
    }
    when {
        changes.clearMonthlyContribution -> put("monthly_contribution", JsonNull)
        changes.monthlyContribution != null -> put("monthly_contribution", changes.monthlyContribution)
    }
}

/** The `POST /goals` body. A field left empty is left out, and the server applies its default. */
@Serializable
internal data class NewGoalBody(
    val name: String,
    val kind: String? = null,
    val horizon: String,
    val target: String,
    val saved: String? = null,
    @SerialName("target_date")
    val targetDate: String? = null,
    @SerialName("monthly_contribution")
    val monthlyContribution: String? = null,
) {
    companion object {
        fun of(goal: NewGoal) = NewGoalBody(
            name = goal.name,
            kind = goal.kind?.wire,
            horizon = goal.horizon.wire,
            target = goal.target,
            saved = goal.saved,
            targetDate = goal.targetDate,
            monthlyContribution = goal.monthlyContribution,
        )
    }
}

@Serializable
internal data class AddMoneyBody(val amount: String)

@Serializable
internal data class OrderBody(val ids: List<String>)
