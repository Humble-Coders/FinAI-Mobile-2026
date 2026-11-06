package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.AiAcceptLine
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.AiProposalLine
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Category
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

    private val categories = listOf(
        Category("cat-rent", "rent", "Rent & mortgage"),
        Category("cat-groceries", "groceries", "Groceries"),
        Category("cat-gifts", "gifts", "Gifts"),
        Category("cat-pets", "pets", "Pets"),
        Category("cat-mine", "custom-1", "Bike club", isSystem = false),
        Category("cat-income", "income", "Income"),
        Category("cat-transfers", "transfers", "Transfers"),
        Category("cat-savings", "savings", "Savings"),
        Category("cat-debt", "debt_payment", "Debt payment"),
    )

    @Test
    fun only_categories_not_yet_in_the_draft_and_budgetable_can_be_added() {
        val names = AiBudgetEdit.addable(categories, AiBudgetEdit.draftOf(proposal)).map { it.name }
        assertEquals(listOf("Bike club", "Gifts", "Pets"), names, "the person's own first, then by name")
    }

    @Test
    fun an_added_line_starts_empty_and_counts_once_typed() {
        val added = AiBudgetEdit.withLineAdded(AiBudgetEdit.draftOf(proposal), categories[2])
        assertEquals(5, added.lines.size)
        assertEquals("Added by you", AiBudgetEdit.kindLabel(added.lines.last().line))
        assertNull(AiBudgetEdit.blockingReason(added, proposal), "blank is zero")
        val typed = AiBudgetEdit.withAmount(added, 4, "50")
        assertEquals("Over your income by $50.00", AiBudgetEdit.leftLabel(typed, proposal))
        assertEquals(added, AiBudgetEdit.withLineAdded(added, categories[2]), "never twice")
        assertEquals(AiAcceptLine("cat-gifts", "50.00"), AiBudgetEdit.accept(typed, "CAD").lines.last())
    }

    @Test
    fun a_line_can_be_removed_unless_it_holds_debt_or_commitments() {
        val draft = AiBudgetEdit.draftOf(proposal)
        assertFalse(AiBudgetEdit.canRemove(draft.lines[0], "CAD"), "rent holds commitments")
        assertTrue(AiBudgetEdit.canRemove(draft.lines[2], "CAD"))
        assertFalse(AiBudgetEdit.canRemove(draft.lines[3], "CAD"), "the Debt line")
        val removed = AiBudgetEdit.withoutLine(draft, 2, "CAD")
        assertEquals(listOf("Rent & mortgage", "Groceries", "Debt payment"), removed.lines.map { it.line.name })
        assertEquals("$300.00 left unplanned", AiBudgetEdit.leftLabel(removed, proposal))
        assertEquals(draft, AiBudgetEdit.withoutLine(draft, 0, "CAD"))
    }

    @Test
    fun a_kept_draft_comes_back_only_for_the_suggestion_it_was_made_from() {
        val edited = AiBudgetEdit.withAmount(
            AiBudgetEdit.withoutLine(AiBudgetEdit.withLineAdded(AiBudgetEdit.draftOf(proposal), categories[3]), 2, "CAD"),
            3,
            "40",
        ).copy(savings = "1700")
        val kept = AiBudgetEdit.encode(edited, proposal)
        assertEquals(edited, AiBudgetEdit.restore(kept, proposal))
        assertNull(AiBudgetEdit.restore(kept, proposal.copy(savings = "1700.00")), "a new suggestion starts afresh")
        assertNull(AiBudgetEdit.restore(kept, proposal.copy(month = "2026-11-01")))
        assertNull(AiBudgetEdit.restore("not json", proposal))
        assertNull(AiBudgetEdit.restore(null, proposal))
    }

    @Test
    fun a_raise_is_offered_only_while_the_budget_has_room_for_it() {
        val dining = BudgetLine(categoryId = "cat-dining", slug = "dining", allocated = "200.00", aiSuggested = "240.00")
        val lower = dining.copy(allocated = "300.00")
        val roomy = Budget(currency = "CAD", savingsRoom = "1562.00")
        assertEquals("AI suggests $240.00", AiBudgetEdit.suggestionLabel(dining, roomy))

        val short = roomy.copy(shortfall = "75.00")
        assertNull(AiBudgetEdit.suggestionLabel(dining, short), "already over income")
        assertEquals("AI suggests $240.00", AiBudgetEdit.suggestionLabel(lower, short), "a cut is always offered")

        val fixedTarget = Budget(currency = "CAD", savingsRoom = "1225.00", savings = BudgetLine(allocated = "1200.00", isUserSet = true))
        assertNull(AiBudgetEdit.suggestionLabel(dining, fixedTarget), "$25 of room is not $40")
        assertEquals("AI suggests $240.00", AiBudgetEdit.suggestionLabel(dining, fixedTarget.copy(savingsRoom = "1240.00")))
    }
}
