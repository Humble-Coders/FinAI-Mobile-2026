package com.humblesolutions.finai.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which way money moved. Mirrors the backend's `TransactionDirection`.
 *
 * The wire words are the bank's; nothing on screen uses them. A person reads
 * "Money out" and "Money in" (see `Strings.manual_entry_direction_*`).
 */
@Serializable(with = TransactionDirectionSerializer::class)
enum class TransactionDirection(val wire: String) {
    DEBIT("debit"),
    CREDIT("credit"),

    /**
     * A direction this build does not know. Deliberately not a fallback to
     * [DEBIT]: guessing which way money moved is the one guess that silently
     * changes a balance. Never sent — [com.humblesolutions.finai.usecase.ManualEntry]
     * builds requests only from [DEBIT] or [CREDIT].
     */
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): TransactionDirection = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object TransactionDirectionSerializer :
    WireEnumSerializer<TransactionDirection>(
        "TransactionDirection",
        { TransactionDirection.fromWire(it) },
        { it.wire },
    )

/**
 * `POST /transactions` — a transaction typed in by hand (Finance-backend #38,
 * as amended 2026-09-29 with mobile #30).
 *
 * `occurred_on` is an ISO date and `amount` a decimal string, never a Double
 * (PRD §4.4). Built only by [com.humblesolutions.finai.usecase.ManualEntry],
 * which refuses to produce one until every answer is valid.
 */
// Every field the API requires is forced onto the wire. FinAiJson leaves out
// any value equal to its default, and DEBIT is both the default and the most
// common answer — so without this every "money out" entry went without a
// direction and was refused. The defaults stay for decoding tolerance.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NewTransaction(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("account_id")
    val accountId: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("occurred_on")
    val occurredOn: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val amount: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val direction: TransactionDirection = TransactionDirection.DEBIT,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val description: String = "",
    // Omitted when null (FinAiJson has explicitNulls = false): with no category
    // the backend categorizes, applying the household's own rules first.
    @SerialName("category_id")
    val categoryId: String? = null,
    // Omitted unless true. "Keep it anyway" after a duplicate warning — the
    // backend saves it as a genuine repeat. Never inferred; only a person
    // answering the duplicate question sets it.
    @SerialName("allow_duplicate")
    val allowDuplicate: Boolean = false,
)

/**
 * A transaction as the API returns it — the row `POST /transactions` saved, and
 * the row the review queue lists (#32). One type, because it is one thing.
 */
@Serializable
data class Transaction(
    val id: String = "",
    @SerialName("account_id")
    val accountId: String = "",
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val amount: String = "",
    val currency: String = "",
    val direction: TransactionDirection = TransactionDirection.UNKNOWN,
    val description: String? = null,
    val merchant: String? = null,
    @SerialName("category_id")
    val categoryId: String? = null,
    val source: String? = null,
    // Whether the backend put the row in the review queue on the way in, and
    // why (#38): a possible duplicate, or nothing to file it into a category.
    // The phone says so on Save rather than a bare "saved" (#30).
    @SerialName("needs_review")
    val needsReview: Boolean = false,
    @SerialName("review_reason")
    val reviewReason: ReviewReason? = null,
    // The two the review queue adds (#32). Null on a row typed in by hand,
    // which never has a confidence and cannot arrive as a suspected duplicate.
    @SerialName("extraction_confidence")
    val extractionConfidence: Int? = null,
    /** What a suspected duplicate matched — shown beside it, never guessed at. */
    @SerialName("duplicate_of")
    val duplicateOf: DuplicateMatch? = null,
)

/**
 * The transaction a new entry would duplicate — carried in the 409 so the screen
 * can show "you already have this" without a second request.
 */
@Serializable
data class DuplicateMatch(
    val id: String = "",
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val amount: String = "",
    val description: String? = null,
)
