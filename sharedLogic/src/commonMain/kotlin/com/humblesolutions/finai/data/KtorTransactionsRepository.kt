package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.ConfirmRows
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.repository.SessionTokenSource
import com.humblesolutions.finai.repository.TransactionsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlin.coroutines.cancellation.CancellationException

/** The transaction endpoints over Ktor: typed-in entries (#38) and the review queue (#32). */
class KtorTransactionsRepository internal constructor(
    private val http: HttpClient,
) : TransactionsRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun create(entry: NewTransaction): Transaction =
        http.postJson("transactions", entry)

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun review(cursor: String?): ReviewPage =
        // Through Ktor's parameter rather than glued into the path: the
        // server's cursor is URL-safe base64 today, and the day it is not,
        // a `+` would reach the handler as a space and paging would break
        // without a word.
        http.getJson("transactions/review") { if (cursor != null) parameter("cursor", cursor) }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome =
        http.patchJson("transactions/$id", patch)

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun confirm(id: String): PatchOutcome =
        http.postEmpty("transactions/$id/confirm")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun confirmAll(ids: List<String>): ConfirmOutcome =
        http.postJson("transactions/confirm", ConfirmRows(ids))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun delete(id: String): DeleteOutcome = http.deleteJson("transactions/$id")

    override fun close() = http.close()
}