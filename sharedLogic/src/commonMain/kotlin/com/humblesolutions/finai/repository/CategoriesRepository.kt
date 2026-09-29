package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import kotlin.coroutines.cancellation.CancellationException

/** The categories this household can file into (Finance-backend 3.4). */
interface CategoriesRepository {

    /** Shared and household categories together, sorted by name. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(): List<Category>

    fun close()
}
