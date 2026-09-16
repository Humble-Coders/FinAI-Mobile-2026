package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Debt
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Investment
import com.humblesolutions.finai.model.Obligation
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** `GET`/`PUT /financial-setup` over Ktor — one implementation for both platforms. */
class KtorFinancialSetupRepository internal constructor(
    private val http: HttpClient,
) : FinancialSetupRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun get(): FinancialSetup = http.getJson("financial-setup")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun save(setup: FinancialSetup): FinancialSetup =
        http.putJson("financial-setup", setup.toRequest())

    override fun close() = http.close()
}

/**
 * The request body, which is the response minus `currency`: the server decides
 * that from the household's region, and sending it back would suggest a client
 * could change it.
 */
@Serializable
private data class FinancialSetupIn(
    val income: String? = null,
    @SerialName("monthly_expense")
    val monthlyExpense: String? = null,
    val debts: List<Debt> = emptyList(),
    val investments: List<Investment> = emptyList(),
    val obligations: List<Obligation> = emptyList(),
)

private fun FinancialSetup.toRequest() = FinancialSetupIn(
    income = income,
    monthlyExpense = monthlyExpense,
    debts = debts,
    investments = investments,
    obligations = obligations,
)
