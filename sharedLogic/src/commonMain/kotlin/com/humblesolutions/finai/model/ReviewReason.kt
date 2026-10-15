package com.humblesolutions.finai.model

import kotlinx.serialization.Serializable

/**
 * Why a saved transaction is waiting for a person. Mirrors the backend's
 * `ReviewReason` (`app/models/enums.py`).
 *
 * Read by manual entry (#30) to say what happened on Save, and by the review
 * screen (#32) to say what each row is asking.
 */
@Serializable(with = ReviewReasonSerializer::class)
enum class ReviewReason(val wire: String) {
    /** The figure was read from a statement with low confidence. */
    LOW_CONFIDENCE("low_confidence"),

    /** Nothing filed it into a category — no rule, and no model allowed or able to. */
    UNKNOWN_CATEGORY("unknown_category"),

    /** Same account, day and amount as another row, under a different name. */
    SUSPECTED_DUPLICATE("suspected_duplicate"),

    /**
     * Filed as a transfer because it looks like money moving between two of the
     * household's own accounts — a card bill paid from chequing (backend #73).
     */
    OWN_TRANSFER("own_transfer"),

    /**
     * A reason this build does not know. The row still needs review — only the
     * wording is unknown — so it is never read as "nothing to check".
     */
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): ReviewReason = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object ReviewReasonSerializer :
    WireEnumSerializer<ReviewReason>("ReviewReason", { ReviewReason.fromWire(it) }, { it.wire })
