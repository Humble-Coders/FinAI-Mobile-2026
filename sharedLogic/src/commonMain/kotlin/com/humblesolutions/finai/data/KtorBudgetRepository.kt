package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** `/budgets` over Ktor — one implementation for both platforms. */
class KtorBudgetRepository internal constructor(
    private val http: HttpClient,
) : BudgetRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun get(month: String): Budget = http.getJson("budgets/$month")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun setLine(month: String, categoryId: String, amount: String): Budget = http.putJson("budgets/$month/lines/$categoryId", AmountBody(amount))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun resetLine(month: String, categoryId: String): Budget = http.deleteJson("budgets/$month/lines/$categoryId/override")

    override fun close() = http.close()
}

/** The body of `PUT /budgets/{month}/lines/{category_id}`. */
@Serializable
internal data class AmountBody(val amount: String)
