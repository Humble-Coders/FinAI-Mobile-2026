package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One statement this household has imported, from `GET /statements`.
 *
 * What the transactions screen offers as a thing to pick. Until this existed
 * an import's id was only ever known to the screen that had just created it,
 * so "show me what came from the May statement" was a question with no way to
 * ask it.
 *
 * Defaults on every field, so a payload missing one still decodes.
 */
@Serializable
data class StatementImportSummary(
    val id: String = "",
    /** When it was imported — what a person picks a statement by. */
    @SerialName("created_at")
    val createdAt: String = "",
    val status: String = "",
    @SerialName("source_kind")
    val sourceKind: String = "",
    @SerialName("page_count")
    val pageCount: Int? = null,
    /** How many rows it produced. */
    val saved: Int = 0,
    /** How many of those still want a person. */
    @SerialName("needs_review")
    val needsReview: Int = 0,
) {
    /** Whether it is worth offering at all: an import with no rows shows nothing. */
    val hasRows: Boolean get() = saved > 0
}

/** The list as it arrives. No cursor: see `GET /statements`. */
@Serializable
data class StatementImports(
    val imports: List<StatementImportSummary> = emptyList(),
)
