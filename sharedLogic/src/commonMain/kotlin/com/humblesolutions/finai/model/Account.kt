package com.humblesolutions.finai.model

import com.humblesolutions.finai.i18n.Strings
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A financial account — `GET /accounts` (Finance-backend 3.3). */
@Serializable
data class Account(
    val id: String = "",
    val name: String = "",
    val kind: AccountKind = AccountKind.UNKNOWN,
    val currency: String = "",
    val institution: String? = null,
)

/** Mirrors `AccountKind` in the backend's `app/models/enums.py`. */
@Serializable(with = AccountKindSerializer::class)
enum class AccountKind(val wire: String) {
    CHEQUING("chequing"),
    SAVINGS("savings"),
    CREDIT_CARD("credit_card"),
    LOAN("loan"),
    INVESTMENT("investment"),
    CASH("cash"),

    /** A kind this build does not know yet. Never offered when creating one. */
    UNKNOWN(""),
    ;

    /** The string naming this kind, so both apps label it alike. Null for [UNKNOWN]. */
    val labelKey: String?
        get() = when (this) {
            CHEQUING -> Strings.account_kind_chequing
            SAVINGS -> Strings.account_kind_savings
            CREDIT_CARD -> Strings.account_kind_credit_card
            LOAN -> Strings.account_kind_loan
            INVESTMENT -> Strings.account_kind_investment
            CASH -> Strings.account_kind_cash
            UNKNOWN -> null
        }

    companion object {
        /** The kinds a person may choose from — everything but [UNKNOWN]. */
        val choosable: List<AccountKind> get() = entries.filter { it != UNKNOWN }

        fun fromWire(value: String?): AccountKind = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object AccountKindSerializer :
    WireEnumSerializer<AccountKind>("AccountKind", { AccountKind.fromWire(it) }, { it.wire })

/**
 * `POST /accounts`. The currency is left to the server, which takes it from the
 * household's region — the same reason the setup wizard never sends one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NewAccount(
    // Forced onto the wire: CHEQUING is the default *and* the likeliest kind,
    // so leaving defaults out would send a chequing account with no kind.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val name: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val kind: AccountKind = AccountKind.CHEQUING,
)
