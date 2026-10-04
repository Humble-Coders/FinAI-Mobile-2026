package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Obligation
import com.humblesolutions.finai.util.Money

/** A commitment as the person is editing it: its name and its monthly amount. */
data class CommitmentDraft(
    val name: String = "",
    val amount: String = "",
)

/** Why a commitment edit cannot be saved yet. */
enum class CommitmentBlock(val messageKey: String) {
    NO_NAME(Strings.setup_item_name_missing),
    NAME_TOO_LONG(Strings.commitment_edit_name_too_long),
    NO_AMOUNT(Strings.manual_entry_block_no_amount),
    AMOUNT_NOT_MONEY(Strings.manual_entry_block_amount_invalid),
    AMOUNT_ZERO(Strings.manual_entry_block_amount_zero),
    NOTHING_CHANGED(Strings.commitment_edit_unchanged),
    TOO_MANY(Strings.commitment_add_limit),
}

/**
 * Adding, editing and deleting the commitments the dashboard lists (PRD F3).
 *
 * A commitment is an obligation from the setup wizard, and the server keeps
 * those as one list that is replaced whole — there is no endpoint for one row,
 * and the rows have no ids. So an edit reads the wizard's answers, changes the
 * one that matches, and writes them all back. Shared so both apps find the
 * same row and refuse the same drafts.
 */
object CommitmentEdit {

    /** The server's limit for a wizard item's name (`NAME_MAX` there). */
    const val NAME_LIMIT = 255

    /** The server's limit on a list of wizard items (`MAX_ITEMS` there). */
    const val COUNT_LIMIT = 20

    fun draftOf(commitment: Commitment): CommitmentDraft = CommitmentDraft(name = commitment.name, amount = commitment.expected)

    fun blockingReason(original: Commitment, draft: CommitmentDraft, currency: String): CommitmentBlock? {
        valid(draft, currency)?.let { return it }
        val digits = Money.fractionDigits(currency)
        // Compared normalised: "1800" and "1800.00" are the same commitment.
        if (draft.name.trim() == original.name && Money.normalize(draft.amount, digits) == Money.normalize(original.expected, digits)) {
            return CommitmentBlock.NOTHING_CHANGED
        }
        return null
    }

    /**
     * Why a new commitment cannot be added yet, given how many there are.
     * The count is checked here, not left to the server's 422: a refusal
     * after the person has typed both fields is the worse time to learn it.
     */
    fun blockingReasonForNew(draft: CommitmentDraft, currency: String, existing: Int): CommitmentBlock? {
        if (existing >= COUNT_LIMIT) return CommitmentBlock.TOO_MANY
        return valid(draft, currency)
    }

    private fun valid(draft: CommitmentDraft, currency: String): CommitmentBlock? {
        val name = draft.name.trim()
        if (name.isEmpty()) return CommitmentBlock.NO_NAME
        if (name.length > NAME_LIMIT) return CommitmentBlock.NAME_TOO_LONG
        if (draft.amount.isBlank()) return CommitmentBlock.NO_AMOUNT
        val digits = Money.fractionDigits(currency)
        val amount = Money.normalize(draft.amount, digits) ?: return CommitmentBlock.AMOUNT_NOT_MONEY
        if (!Money.isPositive(amount, digits)) return CommitmentBlock.AMOUNT_ZERO
        return null
    }

    /**
     * [setup] with [original] replaced by [draft], or null when [original] is
     * no longer in it.
     *
     * Null is not an edge case to paper over: it means the wizard's answers
     * changed since the dashboard was read — on another phone, say — and
     * writing the list back would overwrite that change with a stale one.
     * Matched on name and amount together, because those are all a row has.
     * Two identical rows are interchangeable, so changing the first is right.
     */
    fun applied(setup: FinancialSetup, original: Commitment, draft: CommitmentDraft): FinancialSetup? {
        val index = indexOf(setup, original)
        if (index < 0) return null
        val amount = Money.normalize(draft.amount, Money.fractionDigits(setup.currency)) ?: return null
        val changed = setup.obligations.toMutableList()
        changed[index] = changed[index].copy(name = draft.name.trim(), monthlyAmount = amount)
        return setup.copy(obligations = changed)
    }

    /**
     * [setup] with [draft] added at the end, or null when it cannot be: the
     * list is already at the server's limit (it may have grown on another
     * phone since the dashboard was read), or the draft is not a commitment.
     */
    fun added(setup: FinancialSetup, draft: CommitmentDraft): FinancialSetup? {
        if (setup.obligations.size >= COUNT_LIMIT) return null
        val amount = Money.normalize(draft.amount, Money.fractionDigits(setup.currency)) ?: return null
        val name = draft.name.trim().takeIf { it.isNotEmpty() } ?: return null
        return setup.copy(obligations = setup.obligations + Obligation(name = name, monthlyAmount = amount))
    }

    /**
     * [setup] without [original], or null when [original] is no longer in it
     * — the same rule as [applied], for the same reason: an answer changed on
     * another phone is not deleted on the strength of a stale read.
     */
    fun removed(setup: FinancialSetup, original: Commitment): FinancialSetup? {
        val index = indexOf(setup, original)
        if (index < 0) return null
        return setup.copy(obligations = setup.obligations.filterIndexed { i, _ -> i != index })
    }

    private fun indexOf(setup: FinancialSetup, original: Commitment): Int {
        val digits = Money.fractionDigits(setup.currency)
        val wanted = Money.normalize(original.expected, digits)
        return setup.obligations.indexOfFirst {
            it.name == original.name && Money.normalize(it.monthlyAmount, digits) == wanted
        }
    }
}
