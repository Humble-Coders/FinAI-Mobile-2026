package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/** `GET /categories` over Ktor. */
class KtorCategoriesRepository internal constructor(
    private val http: HttpClient,
) : CategoriesRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun list(): List<Category> = http.getJson("categories")

    override fun close() = http.close()
}
