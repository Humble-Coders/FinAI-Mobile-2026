package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A category this household can file into — `GET /categories` (Finance-backend
 * 3.4): the shared taxonomy and the household's own, never another household's.
 */
@Serializable
data class Category(
    val id: String = "",
    val slug: String = "",
    val name: String = "",
    @SerialName("is_system")
    val isSystem: Boolean = true,
)
