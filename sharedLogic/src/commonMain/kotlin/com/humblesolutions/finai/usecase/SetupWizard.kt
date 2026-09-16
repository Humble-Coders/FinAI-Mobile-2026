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

    /**
     * Whether the whole step may be passed without answering it, which decides
     * whether a Skip control is drawn at all (ticket #17).
     *
     * Only the last one is: income and the monthly expense are the gate the API
     * keeps reporting `financial_setup` for, so a Skip on either would be a
     * control that cannot do what it says. The optional lists on the expense
     * step are skipped by leaving them empty, not by skipping the step.
     */
    val isOptional: Boolean get() = this == PORTFOLIO

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
    ;

    /**
     * Whether nothing has been answered yet, rather than what is there being
     * wrong. The two deserve different timing: a step should not open by
     * telling someone off for not having typed, but a figure that is already
     * wrong needs saying whenever it is on screen — including when the user
     * comes back to the step later.
     */
    val isUnanswered: Boolean get() = this == INCOME_MISSING || this == EXPENSE_MISSING

    /** The step whose figure this is about, or null for a row in a list. */
    val step: SetupStep?
        get() = when (this) {
            INCOME_MISSING, INCOME_NOT_MONEY -> SetupStep.INCOME
            EXPENSE_MISSING, EXPENSE_NOT_MONEY -> SetupStep.EXPENSES
            ITEM_NAME_MISSING, ITEM_AMOUNT_INVALID, RATE_INVALID -> null
        }
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
     * Why Continue on [step] cannot go ahead, or null when it can.
     *
     * The steps can be swiped through freely, so a step is not "reached" by
     * answering the one before it. Continue therefore waits for every mandatory
     * figure **up to and including** its step: on the expense step a missing
     * income still holds it, because moving on would carry an unanswered gate
     * forward. The lists are optional, but a row that exists must be complete —
     * a name and an amount. Nobody is held up by a list they never opened.
     */
    fun blockingReason(step: SetupStep, draft: SetupDraft, fractionDigits: Int = 2): SetupBlock? {
        val income = incomeBlock(draft, fractionDigits)
        return when (step) {
            SetupStep.INCOME -> income
            SetupStep.EXPENSES -> income
                ?: expenseBlock(draft, fractionDigits)
                ?: draft.obligations.firstNotNullOfOrNull { itemBlock(it, fractionDigits) }
            SetupStep.PORTFOLIO -> income
                ?: expenseBlock(draft, fractionDigits)
                ?: draft.debts.firstNotNullOfOrNull { itemBlock(it, fractionDigits, debt = true) }
                ?: draft.investments.firstNotNullOfOrNull { itemBlock(it, fractionDigits) }
        }
    }

    /** Why the gate cannot clear yet — the two mandatory figures only — or null. */
    fun mandatoryBlock(draft: SetupDraft, fractionDigits: Int = 2): SetupBlock? =
        incomeBlock(draft, fractionDigits) ?: expenseBlock(draft, fractionDigits)

    /**
     * What the notice under Continue should say on [step], or null.
     *
     * The same reason Continue is disabled for, with one exception about timing:
     * a figure simply not answered yet *on this step* waits until the user has
     * [touched] it, so a step never opens by telling someone off for not having
     * started. Anything already wrong, and anything missing from an earlier
     * step, is said straight away — the user cannot fix it here without being
     * told where.
     */
    fun notice(step: SetupStep, draft: SetupDraft, fractionDigits: Int = 2, touched: Boolean = false): SetupBlock? {
        val block = blockingReason(step, draft, fractionDigits) ?: return null
        return block.takeIf { touched || !it.isUnanswered || it.step != step }
    }

    /**
     * Whether Skip on [step] can go through: only on a step that is optional in
     * full, and only once both mandatory figures are in. Skip drops that step's
     * lists and finishes the wizard, so a missing figure would finish it with
     * the gate still outstanding.
     */
    fun canSkip(step: SetupStep, draft: SetupDraft, fractionDigits: Int = 2): Boolean =
        step.isOptional && mandatoryBlock(draft, fractionDigits) == null

    private fun incomeBlock(draft: SetupDraft, fractionDigits: Int): SetupBlock? =
        amountBlock(draft.income, fractionDigits, SetupBlock.INCOME_MISSING, SetupBlock.INCOME_NOT_MONEY)

    private fun expenseBlock(draft: SetupDraft, fractionDigits: Int): SetupBlock? =
        amountBlock(draft.monthlyExpense, fractionDigits, SetupBlock.EXPENSE_MISSING, SetupBlock.EXPENSE_NOT_MONEY)

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

    /**
     * What a list adds up to, or null when it holds nothing worth totalling.
     *
     * The row behind a list shows this rather than how many rows there are: a
     * count answers a question nobody asked, and the figure is what the user
     * came to check. Half-finished rows are left out, exactly as [payload]
     * leaves them out of the save.
     */
    fun total(items: List<ItemDraft>, fractionDigits: Int = 2, debt: Boolean = false): String? {
        val amounts = items.filter { itemBlock(it, fractionDigits, debt) == null }.map { it.amount }
        if (amounts.isEmpty()) return null
        return amounts.fold("0") { running, amount ->
            // Every amount here passed itemBlock at this scale, so this cannot
            // refuse today; it returns null rather than a wrong figure if that
            // ever stops being true.
            Money.add(running, amount, fractionDigits) ?: return null
        }
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
