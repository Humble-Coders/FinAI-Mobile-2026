package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the setup wizard holds, from `GET /financial-setup` (PRD F1).
 *
 * Amounts are decimal strings in both directions (PRD §4.4); the backend alone
 * converts to minor units. Defaults on every field, so a payload missing one
 * still decodes.
 *
 * `income` and `monthlyExpense` are the mandatory pair the onboarding rule gates
 * on: until both are stored, the API keeps reporting `financial_setup`. The three
 * lists are optional and stay editable from the profile later — an empty list
 * means the question was skipped or never asked, which are the same fact (#29).
 */
@Serializable
data class FinancialSetup(
    /** What the amounts are denominated in, so the wizard needs no second call. */
    val currency: String = "",
    val income: String? = null,
    @SerialName("monthly_expense")
    val monthlyExpense: String? = null,
    val debts: List<Debt> = emptyList(),
    val investments: List<Investment> = emptyList(),
    val obligations: List<Obligation> = emptyList(),
) {
    /** Both figures stored: the point at which the onboarding step clears. */
    val mandatoryComplete: Boolean get() = !income.isNullOrBlank() && !monthlyExpense.isNullOrBlank()
}

@Serializable
data class Debt(
    val name: String = "",
    val balance: String = "",
    @SerialName("minimum_payment")
    val minimumPayment: String? = null,
    /** A percentage as typed, e.g. "5.25"; the server stores basis points. */
    @SerialName("interest_rate_percent")
    val interestRatePercent: String? = null,
)

@Serializable
data class Investment(
    val name: String = "",
    val amount: String = "",
)

@Serializable
data class Obligation(
    val name: String = "",
    @SerialName("monthly_amount")
    val monthlyAmount: String = "",
)
