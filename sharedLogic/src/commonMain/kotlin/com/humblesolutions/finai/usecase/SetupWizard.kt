package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Debt
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Investment
import com.humblesolutions.finai.model.Obligation
import com.humblesolutions.finai.util.Money

/** The wizard's three screens, in order. */
enum class SetupStep {
    /** Monthly income. Mandatory. */
    INCOME,

    /** Monthly expense, mandatory, and the itemised obligations, which are not. */
    EXPENSES,

    /** Debts and investments, both itemised and both optional. */
    PORTFOLIO,
    ;

    /** `1` of `3`, for the step label and the dots. */
    val number: Int get() = ordinal + 1

    val isLast: Boolean get() = this == PORTFOLIO

    companion object {
        const val COUNT = 3
    }
}

/** Why a step cannot be left yet — one reason, shown where it belongs. */
enum class SetupBlock(val messageKey: String) {
    INCOME_MISSING(Strings.setup_income_missing),
    INCOME_NOT_MONEY(Strings.setup_amount_invalid),
    EXPENSE_MISSING(Strings.setup_expense_missing),
    EXPENSE_NOT_MONEY(Strings.setup_amount_invalid),
    ITEM_NAME_MISSING(Strings.setup_item_name_missing),
    ITEM_AMOUNT_INVALID(Strings.setup_amount_invalid),
    RATE_INVALID(Strings.setup_rate_invalid),
}

/** One row of a list the user is filling in — a debt, an investment, an obligation. */
data class ItemDraft(
    val name: String = "",
    val amount: String = "",
    /** Debts only; blank when not given, which is allowed. */
    val minimumPayment: String = "",
    /** Debts only, a percentage as typed. Blank when not given. */
    val interestRatePercent: String = "",
)

/** Everything the wizard is holding, as typed. */
data class SetupDraft(
    val income: String = "",
    val monthlyExpense: String = "",
    val obligations: List<ItemDraft> = emptyList(),
    val debts: List<ItemDraft> = emptyList(),
    val investments: List<ItemDraft> = emptyList(),
)

/**
 * The wizard's rules, shared so the two platforms cannot disagree about a
 * figure or a gate (kmp-arch-v2 → blocking reasons).
 *
 * One function answers "why can this not be saved yet", and the disabled
 * Continue button, the inline notice and the save path all read it — a greyed
 * button and a server refusal must never drift apart.
 */
object SetupWizard {

    /** The highest percentage the server will store for a debt. */
    const val MAX_RATE_PERCENT = 100

    /**
     * Why [step] cannot be left, or null when it can.
     *
     * The mandatory pair must be money. The lists are optional, but a row that
     * exists must be complete: a name and an amount. Nobody is held up by a
     * list they never opened.
     */
    fun blockingReason(step: SetupStep, draft: SetupDraft, fractionDigits: Int = 2): SetupBlock? = when (step) {
        SetupStep.INCOME -> amountBlock(draft.income, fractionDigits, SetupBlock.INCOME_MISSING, SetupBlock.INCOME_NOT_MONEY)
        SetupStep.EXPENSES ->
            amountBlock(draft.monthlyExpense, fractionDigits, SetupBlock.EXPENSE_MISSING, SetupBlock.EXPENSE_NOT_MONEY)
                ?: draft.obligations.firstNotNullOfOrNull { itemBlock(it, fractionDigits) }
        SetupStep.PORTFOLIO ->
            draft.debts.firstNotNullOfOrNull { itemBlock(it, fractionDigits, debt = true) }
                ?: draft.investments.firstNotNullOfOrNull { itemBlock(it, fractionDigits) }
    }

    /** Why a single row cannot be kept, or null. Used by the row's own editor. */
    fun itemBlock(item: ItemDraft, fractionDigits: Int = 2, debt: Boolean = false): SetupBlock? {
        if (item.name.isBlank()) return SetupBlock.ITEM_NAME_MISSING
        if (!Money.isMoney(item.amount, fractionDigits)) return SetupBlock.ITEM_AMOUNT_INVALID
        if (debt) {
            if (item.minimumPayment.isNotBlank() && !Money.isMoney(item.minimumPayment, fractionDigits)) {
                return SetupBlock.ITEM_AMOUNT_INVALID
            }
            if (item.interestRatePercent.isNotBlank() && !isRate(item.interestRatePercent)) {
                return SetupBlock.RATE_INVALID
            }
        }
        return null
    }

    /** A percentage the server will take: 0 to 100, at most two decimal places. */
    fun isRate(raw: String): Boolean {
        val normalized = Money.normalize(raw, fractionDigits = 2) ?: return false
        return Money.compare(normalized, MAX_RATE_PERCENT.toString()) <= 0
    }

    /**
     * Where to resume: the first step whose answer is missing.
     *
     * Only the mandatory pair can hold the wizard, so a saved income and
     * expense means there is nothing left it must ask for — the optional steps
     * are never re-offered, because a skipped answer and an unasked one look
     * identical (#29).
     */
    fun resumeAt(setup: FinancialSetup): SetupStep = when {
        setup.income.isNullOrBlank() -> SetupStep.INCOME
        setup.monthlyExpense.isNullOrBlank() -> SetupStep.EXPENSES
        else -> SetupStep.PORTFOLIO
    }

    /** What was saved, reopened as a draft, so a resumed wizard shows the figures. */
    fun draftFrom(setup: FinancialSetup): SetupDraft = SetupDraft(
        income = setup.income.orEmpty(),
        monthlyExpense = setup.monthlyExpense.orEmpty(),
        obligations = setup.obligations.map { ItemDraft(name = it.name, amount = it.monthlyAmount) },
        debts = setup.debts.map {
            ItemDraft(
                name = it.name,
                amount = it.balance,
                minimumPayment = it.minimumPayment.orEmpty(),
                interestRatePercent = it.interestRatePercent.orEmpty(),
            )
        },
        investments = setup.investments.map { ItemDraft(name = it.name, amount = it.amount) },
    )

    /**
     * The whole wizard, as the API wants it.
     *
     * Every save carries everything, because a save replaces what the wizard
     * owns: sending one step's answer alone would erase the others. Amounts are
     * normalised here, so the server sees one shape whatever was typed, and
     * rows that were left half-finished are dropped rather than refused.
     */
    fun payload(draft: SetupDraft, currency: String, fractionDigits: Int = 2): FinancialSetup = FinancialSetup(
        currency = currency,
        income = Money.normalize(draft.income, fractionDigits),
        monthlyExpense = Money.normalize(draft.monthlyExpense, fractionDigits),
        obligations = draft.obligations.mapNotNull { item ->
            if (itemBlock(item, fractionDigits) != null) return@mapNotNull null
            Obligation(name = item.name.trim(), monthlyAmount = Money.normalize(item.amount, fractionDigits).orEmpty())
        },
        debts = draft.debts.mapNotNull { item ->
            if (itemBlock(item, fractionDigits, debt = true) != null) return@mapNotNull null
            Debt(
                name = item.name.trim(),
                balance = Money.normalize(item.amount, fractionDigits).orEmpty(),
                minimumPayment = Money.normalize(item.minimumPayment, fractionDigits),
                interestRatePercent = Money.normalize(item.interestRatePercent, fractionDigits = 2),
            )
        },
        investments = draft.investments.mapNotNull { item ->
            if (itemBlock(item, fractionDigits) != null) return@mapNotNull null
            Investment(name = item.name.trim(), amount = Money.normalize(item.amount, fractionDigits).orEmpty())
        },
    )

    private fun amountBlock(raw: String, fractionDigits: Int, missing: SetupBlock, invalid: SetupBlock): SetupBlock? = when {
        raw.isBlank() -> missing
        !Money.isMoney(raw, fractionDigits) -> invalid
        else -> null
    }
}
