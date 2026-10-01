package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.NewAccount
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** `GET`/`POST /accounts` over Ktor — one implementation for both platforms. */
class KtorAccountsRepository internal constructor(
    private val http: HttpClient,
) : AccountsRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun list(): List<Account> = http.getJson("accounts")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun create(account: NewAccount): Account = http.postJson("accounts", account)

    override fun close() = http.close()
}
