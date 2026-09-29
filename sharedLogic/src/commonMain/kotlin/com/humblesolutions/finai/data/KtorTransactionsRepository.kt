package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.repository.SessionTokenSource
import com.humblesolutions.finai.repository.TransactionsRepository
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** `POST /transactions` over Ktor. */
class KtorTransactionsRepository internal constructor(
    private val http: HttpClient,
) : TransactionsRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun create(entry: NewTransaction): Transaction =
        http.postJson("transactions", entry)

    override fun close() = http.close()
}
