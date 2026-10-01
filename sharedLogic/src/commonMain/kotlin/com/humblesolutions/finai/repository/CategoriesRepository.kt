package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import kotlin.coroutines.cancellation.CancellationException

/** The categories this household can file into (Finance-backend 3.4). */
interface CategoriesRepository {

    /** Shared and household categories together, sorted by name. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(): List<Category>

    /**
     * A category this household makes for itself (#32) — for a correction the
     * shared taxonomy has no room for. Raises [ApiException.CategoryExists]
     * naming the one that already has that name, so the picker selects it
     * rather than asking the person to think of another.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun create(name: String): Category

    fun close()
}
