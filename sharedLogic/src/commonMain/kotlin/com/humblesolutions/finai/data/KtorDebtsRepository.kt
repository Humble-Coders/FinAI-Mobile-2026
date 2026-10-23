package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.DebtChanges
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.DebtsPage
import com.humblesolutions.finai.repository.DebtsRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** `/debts` over Ktor — one implementation for both platforms. */
class KtorDebtsRepository internal constructor(
    private val http: HttpClient,
) : DebtsRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun list(): DebtsPage = http.getJson("debts")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun update(id: String, changes: DebtChanges): DebtRecord = http.patchJson("debts/$id", debtPatchBody(changes))

    override fun close() = http.close()
}

/**
 * The `PATCH /debts/{id}` body, written field by field: an absent field is
 * "unchanged" and only an explicit null clears one, which the shared JSON's
 * dropped nulls cannot say (see [DebtChanges]).
 */
internal fun debtPatchBody(changes: DebtChanges): JsonObject = buildJsonObject {
    changes.name?.let { put("name", it) }
    changes.kind?.let { put("kind", it.wire) }
    changes.balance?.let { put("balance", it) }
    when {
        changes.clearMinimumPayment -> put("minimum_payment", JsonNull)
        changes.minimumPayment != null -> put("minimum_payment", changes.minimumPayment)
    }
    when {
        changes.clearInterestRate -> put("interest_rate_percent", JsonNull)
        changes.interestRatePercent != null -> put("interest_rate_percent", changes.interestRatePercent)
    }
    // The server refuses half a promotion, so the pair always travels together.
    when {
        changes.clearPromotion -> {
            put("promo_rate_percent", JsonNull)
            put("promo_ends_on", JsonNull)
        }

        changes.promoRatePercent != null && changes.promoEndsOn != null -> {
            put("promo_rate_percent", changes.promoRatePercent)
            put("promo_ends_on", changes.promoEndsOn)
        }
    }
    when {
        changes.clearAccount -> put("account_id", JsonNull)
        changes.accountId != null -> put("account_id", changes.accountId)
    }
}
