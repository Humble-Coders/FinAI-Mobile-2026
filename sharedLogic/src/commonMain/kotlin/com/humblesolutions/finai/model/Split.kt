package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Split expense — `/splits`, gated by `split_expenses`. A bill divided between
 * the person and the people they split with. Kept apart from the ledger: the
 * bill reaches it through the statement it was paid on.
 *
 * Every share is the server's; the app previews them the same way
 * (`SplitMath`) while the person is choosing. Amounts are decimal strings.
 */
@Serializable
data class SplitContact(
    val id: String = "",
    val name: String = "",
    /** E.164, `"+919876543210"`. */
    val phone: String = "",
)

@Serializable
internal data class SplitContacts(val contacts: List<SplitContact> = emptyList())

@Serializable
internal data class ContactBody(val name: String, val phone: String)

/** What the AI read from a receipt; every amount is printed on it. */
@Serializable
data class ReceiptRead(
    val merchant: String? = null,
    @SerialName("occurred_on")
    val occurredOn: String? = null,
    val items: List<ReceiptItem> = emptyList(),
    val subtotal: String? = null,
    val tax: String? = null,
    val tip: String? = null,
    val total: String? = null,
    /** Lines the AI offered that are not on the receipt: left out. */
    val unreadable: Int = 0,
    @SerialName("reads_left")
    val readsLeft: Int = 0,
)

@Serializable
data class ReceiptItem(
    val name: String = "",
    val quantity: Int = 1,
    val amount: String = "0",
)

@Serializable
internal data class ReceiptBody(val text: String)

/** How a bill is divided. */
enum class SplitMode(val wire: String) {
    EQUAL("equal"),
    ITEMS("items"),
}

/** `POST /splits` — built by `SplitExpense.toSave` from a draft that passed its blocking reason. */
@Serializable
data class SplitToSave(
    val title: String,
    @SerialName("occurred_on")
    val occurredOn: String,
    val mode: String,
    val total: String?,
    val items: List<SplitItemToSave>,
    val tax: String?,
    val tip: String?,
    /** The person themselves (`contactId` null) first, then the others. */
    val participants: List<SplitPersonToSave>,
)

@Serializable
data class SplitItemToSave(
    val name: String,
    val amount: String,
    /** Indexes into the participants. */
    val participants: List<Int>,
)

@Serializable
data class SplitPersonToSave(
    @SerialName("contact_id")
    val contactId: String? = null,
)

/** A saved split, with each person's share. */
@Serializable
data class Split(
    val id: String = "",
    val title: String = "",
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val mode: String = "equal",
    val currency: String = "",
    val total: String = "0",
    val tax: String = "0",
    val tip: String = "0",
    val participants: List<SplitParticipant> = emptyList(),
    val items: List<SplitItem> = emptyList(),
    /** Still to come from the others. */
    @SerialName("owed_to_you")
    val owedToYou: String = "0",
)

@Serializable
data class SplitParticipant(
    val id: String = "",
    @SerialName("contact_id")
    val contactId: String? = null,
    @SerialName("is_self")
    val isSelf: Boolean = false,
    val name: String = "",
    val phone: String? = null,
    val share: String = "0",
    val settled: Boolean = false,
    /** The items this person shares in; empty for an equal split. */
    val items: List<String> = emptyList(),
)

@Serializable
data class SplitItem(
    val name: String = "",
    val amount: String = "0",
    val participants: List<Int> = emptyList(),
)

@Serializable
data class SplitSummary(
    val id: String = "",
    val title: String = "",
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val total: String = "0",
    val people: Int = 0,
    @SerialName("owed_to_you")
    val owedToYou: String = "0",
    val settled: Boolean = false,
)

@Serializable
internal data class SplitSummaries(val splits: List<SplitSummary> = emptyList())

@Serializable
internal data class SettleBody(val settled: Boolean)
