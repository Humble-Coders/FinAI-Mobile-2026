package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.FinancialSetup
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
}

/**
 * Editing one of the commitments the dashboard lists (PRD F3).
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

    fun draftOf(commitment: Commitment): CommitmentDraft = CommitmentDraft(name = commitment.name, amount = commitment.expected)

    fun blockingReason(original: Commitment, draft: CommitmentDraft, currency: String): CommitmentBlock? {
        val name = draft.name.trim()
        if (name.isEmpty()) return CommitmentBlock.NO_NAME
        if (name.length > NAME_LIMIT) return CommitmentBlock.NAME_TOO_LONG
        if (draft.amount.isBlank()) return CommitmentBlock.NO_AMOUNT
        val digits = Money.fractionDigits(currency)
        val amount = Money.normalize(draft.amount, digits) ?: return CommitmentBlock.AMOUNT_NOT_MONEY
        if (!Money.isPositive(amount, digits)) return CommitmentBlock.AMOUNT_ZERO
        // Compared normalised: "1800" and "1800.00" are the same commitment.
        if (name == original.name && amount == Money.normalize(original.expected, digits)) {
            return CommitmentBlock.NOTHING_CHANGED
        }
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
        val digits = Money.fractionDigits(setup.currency)
        val wanted = Money.normalize(original.expected, digits)
        val index = setup.obligations.indexOfFirst {
            it.name == original.name && Money.normalize(it.monthlyAmount, digits) == wanted
        }
        if (index < 0) return null
        val amount = Money.normalize(draft.amount, digits) ?: return null
        val changed = setup.obligations.toMutableList()
        changed[index] = changed[index].copy(name = draft.name.trim(), monthlyAmount = amount)
        return setup.copy(obligations = changed)
    }
}
