package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.AiAccept
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.CommitmentPayment
import com.humblesolutions.finai.model.SpendingCheck
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
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
    override suspend fun setLine(month: String, categoryId: String, amount: String, thisMonthOnly: Boolean): Budget = http.putJson("budgets/$month/lines/$categoryId", AmountBody(amount, thisMonthOnly))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun resetLine(month: String, categoryId: String): Budget = http.deleteJson("budgets/$month/lines/$categoryId/override")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun suggestSetup(): AiProposal = http.postEmpty("budgets/ai/setup")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun setupSuggestion(): AiProposal = http.getJson("budgets/ai/setup")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun acceptSetup(accept: AiAccept): Budget = http.postJson("budgets/ai/setup/accept", accept)

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun review(month: String): Budget = http.postEmpty("budgets/$month/ai/review")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun tickOff(month: String, commitmentId: String, transactionId: String): Budget = http.putJson("budgets/$month/commitments/$commitmentId/payment", TickOffBody(transactionId))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun tickOffWithNew(month: String, commitmentId: String, payment: CommitmentPayment): Budget = http.postJson("budgets/$month/commitments/$commitmentId/payment", payment)

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun untick(month: String, commitmentId: String): Budget = http.deleteJson("budgets/$month/commitments/$commitmentId/payment")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun spendingCheck(month: String): SpendingCheck = http.getJson("budgets/$month/spending-check")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun runSpendingCheck(month: String): SpendingCheck = http.postEmpty("budgets/$month/spending-check")

    override fun close() = http.close()
}

/** The body of `PUT /budgets/{month}/commitments/{id}/payment`. */
@Serializable
internal data class TickOffBody(@SerialName("transaction_id") val transactionId: String)

/** The body of `PUT /budgets/{month}/lines/{category_id}`. */
@Serializable
internal data class AmountBody(
    val amount: String,
    @SerialName("this_month_only") val thisMonthOnly: Boolean = false,
)
