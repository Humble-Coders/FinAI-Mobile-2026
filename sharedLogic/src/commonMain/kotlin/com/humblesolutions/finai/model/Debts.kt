package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The household's debts as `GET /debts` gives them (backend #76).
 *
 * Not the setup wizard's [Debt]: that is what the wizard holds and shows back,
 * five fields with no id. These have an id, a kind, a promotion, an account
 * link and a dated balance, and are edited one at a time.
 *
 * Amounts are decimal strings and rates percentages as typed (`"20.99"`).
 * Defaults on every field, so a payload missing one still decodes.
 */
@Serializable
data class DebtsPage(
    val currency: String = "",
    /** Setup's first, in the wizard's order, then the rest oldest first — the server's order. */
    val debts: List<DebtRecord> = emptyList(),
)

@Serializable
data class DebtRecord(
    val id: String = "",
    val name: String = "",
    val kind: DebtKind = DebtKind.OTHER,
    val balance: String = "0",
    @SerialName("minimum_payment")
    val minimumPayment: String? = null,
    @SerialName("interest_rate_percent")
    val interestRatePercent: String? = null,
    /** A balance transfer's rate until [promoEndsOn]. Both or neither. */
    @SerialName("promo_rate_percent")
    val promoRatePercent: String? = null,
    /** `YYYY-MM-DD`. */
    @SerialName("promo_ends_on")
    val promoEndsOn: String? = null,
    @SerialName("due_day")
    val dueDay: Int? = null,
    /** The household's card or loan account this debt is, when linked. */
    @SerialName("account_id")
    val accountId: String? = null,
    /** `YYYY-MM-DD`: the day the balance was true. */
    @SerialName("balance_as_of")
    val balanceAsOf: String? = null,
    @SerialName("balance_source")
    val balanceSource: BalanceSource = BalanceSource.UNKNOWN,
    /** The server's judgement that the balance wants updating: old, and not from a statement. */
    @SerialName("balance_stale")
    val balanceStale: Boolean = false,
    /** Typed into the setup wizard, which keeps its name, balance, minimum, rate and due day. */
    @SerialName("from_setup")
    val fromSetup: Boolean = false,
)

/** What a debt is. It tells debts apart on the plan; only the rate decides the payoff order. */
@Serializable(with = DebtKindSerializer::class)
enum class DebtKind(val wire: String) {
    CREDIT_CARD("credit_card"),
    LINE_OF_CREDIT("line_of_credit"),
    CAR_LOAN("car_loan"),
    BALANCE_TRANSFER("balance_transfer"),
    STUDENT_LOAN("student_loan"),
    MORTGAGE("mortgage"),
    OTHER("other"),

    /** A kind this build does not know; drawn and edited as [OTHER]. Never sent. */
    UNKNOWN(""),
    ;

    companion object {
        /** The kinds a person can choose, in the order the picker shows them. */
        val choosable: List<DebtKind> = entries.filter { it != UNKNOWN }

        fun fromWire(value: String?): DebtKind = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object DebtKindSerializer :
    WireEnumSerializer<DebtKind>("DebtKind", { DebtKind.fromWire(it) }, { it.wire })

/** Where a debt's balance came from — said beside it. */
@Serializable(with = BalanceSourceSerializer::class)
enum class BalanceSource(val wire: String) {
    SETUP("setup"),
    MANUAL("manual"),
    STATEMENT("statement"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): BalanceSource = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

internal object BalanceSourceSerializer :
    WireEnumSerializer<BalanceSource>("BalanceSource", { BalanceSource.fromWire(it) }, { it.wire })

/**
 * What an edit changes, field by field — built by `DebtEdit`, so only what the
 * person changed is sent.
 *
 * As with goals, the server reads an absent field as "unchanged" and only an
 * explicit null as "remove it", while the shared JSON drops nulls on the way
 * out. Hence the `clear…` flags beside the fields that can be emptied, and a
 * request body written by hand. Name, kind and balance cannot be cleared.
 */
data class DebtChanges(
    val name: String? = null,
    val kind: DebtKind? = null,
    /** A new balance: the server dates it today and marks it typed by hand. */
    val balance: String? = null,
    val minimumPayment: String? = null,
    val clearMinimumPayment: Boolean = false,
    val interestRatePercent: String? = null,
    val clearInterestRate: Boolean = false,
    /** The promotion travels as a pair: both set, or [clearPromotion]. */
    val promoRatePercent: String? = null,
    val promoEndsOn: String? = null,
    val clearPromotion: Boolean = false,
    val accountId: String? = null,
    val clearAccount: Boolean = false,
) {
    val isEmpty: Boolean
        get() = name == null && kind == null && balance == null &&
            minimumPayment == null && !clearMinimumPayment &&
            interestRatePercent == null && !clearInterestRate &&
            promoRatePercent == null && promoEndsOn == null && !clearPromotion &&
            accountId == null && !clearAccount
}
