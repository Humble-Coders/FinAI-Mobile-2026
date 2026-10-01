package com.humblesolutions.finai.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A page of the review queue — `GET /transactions/review` (#32).
 *
 * [nextCursor] is opaque: the client stores it and sends it back, and nothing
 * else may be read from it. It is keyset, not an offset, so a row answered
 * between two pages cannot push another row past unseen — in a queue whose
 * whole point is that every row gets looked at, that would be the worst bug.
 */
@Serializable
data class ReviewPage(
    val rows: List<Transaction> = emptyList(),
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

/**
 * A correction to one row — `PATCH /transactions/{id}`.
 *
 * Every field is optional and only what changed is sent: a field left out is a
 * field the person did not touch. The server treats null as "not sent" too, so
 * nothing here can clear a value.
 */
@Serializable
data class TransactionPatch(
    @SerialName("occurred_on")
    val occurredOn: String? = null,
    val amount: String? = null,
    val direction: TransactionDirection? = null,
    val description: String? = null,
    val merchant: String? = null,
    @SerialName("category_id")
    val categoryId: String? = null,
) {
    /** Whether anything was actually changed; the server refuses an empty patch. */
    val isEmpty: Boolean
        get() = occurredOn == null && amount == null && direction == null &&
            description == null && merchant == null && categoryId == null
}

/** What correcting or confirming one row came to. */
@Serializable
data class PatchOutcome(
    val transaction: Transaction = Transaction(),
    /** This was the import's last outstanding row, so the statement is done. */
    @SerialName("import_finished")
    val importFinished: Boolean = false,
    /**
     * A category change was learned as a rule for this merchant. False when the
     * category did not change, or the row has no merchant to learn from.
     */
    @SerialName("rule_recorded")
    val ruleRecorded: Boolean = false,
    /**
     * How many *other* rows waiting in the queue took the new category. Said
     * out loud, never silently: these are changes to someone's financial
     * records that they did not make one by one.
     */
    val recategorized: Int = 0,
)

/** Rows accepted exactly as they are — `POST /transactions/confirm`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfirmRows(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val ids: List<String> = emptyList(),
)

@Serializable
data class ConfirmOutcome(
    /**
     * How many rows actually left the queue. Not necessarily how many were
     * sent: a row with no category stays, asking for one (Finance-backend #48).
     */
    val confirmed: Int = 0,
    @SerialName("imports_finished")
    val importsFinished: List<String> = emptyList(),
)

@Serializable
data class DeleteOutcome(
    @SerialName("import_finished")
    val importFinished: Boolean = false,
)

/** `POST /categories` — a category the household makes for itself. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NewCategory(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val name: String = "",
)
