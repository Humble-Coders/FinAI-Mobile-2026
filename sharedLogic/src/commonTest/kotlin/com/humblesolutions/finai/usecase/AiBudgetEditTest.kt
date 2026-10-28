package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.AiAcceptLine
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.AiProposalLine
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiBudgetEditTest {

    private val proposal = AiProposal(
        month = "2026-10-01",
        currency = "CAD",
        income = "5000.00",
        lines = listOf(
            AiProposalLine("cat-rent", "rent", "Rent & mortgage", "need", "1800.00", "1800.00", null),
            AiProposalLine("cat-groceries", "groceries", "Groceries", "need", "500.00", "0.00", "Food at home."),
            AiProposalLine("cat-dining", "dining", "Dining out", "want", "300.00", "0.00", null),
            AiProposalLine("cat-debt", "debt_payment", "Debt payment", "debt", "620.00", "620.00", null),
        ),
        savings = "1780.00",
        needsPercent = 58,
        wantsPercent = 6,
        savingsPercent = 36,
        attemptsLeft = 2,
    )

    @Test
    fun the_suggestion_as_it_came_adds_up_and_can_be_started() {
        val draft = AiBudgetEdit.draftOf(proposal)
        assertEquals("0.00", AiBudgetEdit.left(draft, proposal))
        assertNull(AiBudgetEdit.blockingReason(draft, proposal))
        assertNull(AiBudgetEdit.leftLabel(draft, proposal))
    }

    @Test
    fun lowering_a_line_leaves_money_unplanned_and_raising_one_goes_over() {
        val lower = AiBudgetEdit.withAmount(AiBudgetEdit.draftOf(proposal), 2, "180")
        assertEquals("$120.00 left unplanned", AiBudgetEdit.leftLabel(lower, proposal))
        val higher = AiBudgetEdit.withAmount(AiBudgetEdit.draftOf(proposal), 1, "600")
        assertEquals("Over your income by $100.00", AiBudgetEdit.leftLabel(higher, proposal))
        assertEquals(AiBudgetBlock.OVER_INCOME, AiBudgetEdit.blockingReason(higher, proposal))
    }

    @Test
    fun a_line_cannot_go_below_its_commitments_or_be_something_else() {
        val below = AiBudgetEdit.withAmount(AiBudgetEdit.draftOf(proposal), 0, "1500")
        assertEquals(AiBudgetBlock.BELOW_COMMITMENT, AiBudgetEdit.blockingReason(below, proposal))
        val nonsense = AiBudgetEdit.withAmount(AiBudgetEdit.draftOf(proposal), 1, "lots")
        assertEquals(AiBudgetBlock.AMOUNT_INVALID, AiBudgetEdit.blockingReason(nonsense, proposal))
    }

    @Test
    fun starting_sends_every_line_but_debt_and_a_blank_is_zero() {
        val draft = AiBudgetEdit.withAmount(AiBudgetEdit.draftOf(proposal), 2, "")
        val sent = AiBudgetEdit.accept(draft, "CAD")
        assertEquals(
            listOf(
                AiAcceptLine("cat-rent", "1800.00"),
                AiAcceptLine("cat-groceries", "500.00"),
                AiAcceptLine("cat-dining", "0.00"),
            ),
            sent.lines,
        )
        assertEquals("1780.00", sent.savings)
        assertNull(AiBudgetEdit.accept(draft.copy(savings = "0"), "CAD").savings)
    }

    @Test
    fun the_words_around_it() {
        assertEquals("Needs 58% · Wants 6% · Savings 36%", AiBudgetEdit.shareLabel(proposal))
        assertEquals("Includes $1,800.00 of commitments", AiBudgetEdit.committedLabel(proposal.lines[0], "CAD"))
        assertNull(AiBudgetEdit.committedLabel(proposal.lines[1], "CAD"))
        assertNull(AiBudgetEdit.committedLabel(proposal.lines[3], "CAD"), "the Debt line is its own")
        assertEquals("Suggest again (2 left)", AiBudgetEdit.attemptsLabel(proposal))
        assertNull(AiBudgetEdit.attemptsLabel(proposal.copy(attemptsLeft = 0)))
    }

    @Test
    fun a_line_shows_the_ai_number_only_when_it_differs() {
        val line = BudgetLine(categoryId = "cat-groceries", allocated = "450.00", aiSuggested = "430.00")
        assertEquals("AI suggests $430.00", AiBudgetEdit.suggestionLabel(line, "CAD"))
        assertNull(AiBudgetEdit.suggestionLabel(line.copy(aiSuggested = "450"), "CAD"))
        assertNull(AiBudgetEdit.suggestionLabel(line.copy(aiSuggested = null), "CAD"))
    }

    @Test
    fun the_review_is_asked_for_only_while_due_and_running() {
        assertTrue(AiBudgetEdit.shouldReview(Budget(aiReviewDue = true)))
        assertFalse(AiBudgetEdit.shouldReview(Budget(aiReviewDue = true, monthEnded = true)))
        assertFalse(AiBudgetEdit.shouldReview(Budget()))
    }

    @Test
    fun a_line_says_what_is_usually_spent_when_there_is_history() {
        val groceries = proposal.lines[1].copy(usual = "450.00")
        assertEquals("You usually spend $450.00 a month", AiBudgetEdit.usualLabel(groceries, "CAD"))
        assertNull(AiBudgetEdit.usualLabel(proposal.lines[1], "CAD"), "a new account has none")
        assertNull(AiBudgetEdit.usualLabel(proposal.lines[3].copy(usual = "500.00"), "CAD"), "not on the Debt line")
    }
}
