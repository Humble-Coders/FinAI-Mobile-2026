package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetEditTest {

    private fun line(
        name: String = "Groceries",
        id: String = "cat-groceries",
        slug: String = "groceries",
        allocated: String = "440.00",
        suggested: String = "440.00",
        spent: String = "380.00",
        userSet: Boolean = false,
    ) = BudgetLine(
        categoryId = id,
        slug = slug,
        name = name,
        suggested = suggested,
        allocated = allocated,
        isUserSet = userSet,
        spent = spent,
    )

    // ── Blocking reasons ────────────────────────────────────────────────

    @Test
    fun a_draft_starts_at_the_line_s_allocation() {
        assertEquals(BudgetDraft("440.00"), BudgetEdit.draftOf(line()))
    }

    @Test
    fun an_unchanged_draft_cannot_be_saved() {
        assertEquals(BudgetBlock.NOTHING_CHANGED, BudgetEdit.blockingReason(line(), BudgetDraft("440.00"), "CAD"))
        // The same amount written differently is not a change.
        assertEquals(BudgetBlock.NOTHING_CHANGED, BudgetEdit.blockingReason(line(), BudgetDraft("440"), "CAD"))
        assertEquals(BudgetBlock.NOTHING_CHANGED, BudgetEdit.blockingReason(line(), BudgetDraft(" 440.00 "), "CAD"))
    }

    @Test
    fun a_changed_amount_can_be_saved() {
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("500"), "CAD"))
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("440.01"), "CAD"))
    }

    @Test
    fun each_refused_draft_names_its_own_reason() {
        assertEquals(BudgetBlock.NO_AMOUNT, BudgetEdit.blockingReason(line(), BudgetDraft(""), "CAD"))
        assertEquals(BudgetBlock.NO_AMOUNT, BudgetEdit.blockingReason(line(), BudgetDraft("   "), "CAD"))
        assertEquals(BudgetBlock.AMOUNT_NOT_MONEY, BudgetEdit.blockingReason(line(), BudgetDraft("five hundred"), "CAD"))
        assertEquals(BudgetBlock.AMOUNT_NEGATIVE, BudgetEdit.blockingReason(line(), BudgetDraft("-50"), "CAD"))
        assertEquals(BudgetBlock.AMOUNT_NEGATIVE, BudgetEdit.blockingReason(line(), BudgetDraft(" -50.00"), "CAD"))
    }

    /**
     * `Money.normalize` declines both "500.999" and "five hundred" with the
     * same null, so without telling them apart a real figure with one digit
     * too many reads as nonsense. The person can act on "too many decimal
     * places"; they cannot act on "that isn't an amount".
     */
    @Test
    fun more_decimals_than_the_currency_has_gets_its_own_reason() {
        assertEquals(BudgetBlock.AMOUNT_TOO_PRECISE, BudgetEdit.blockingReason(line(), BudgetDraft("500.999"), "CAD"))
        assertEquals(BudgetBlock.AMOUNT_TOO_PRECISE, BudgetEdit.blockingReason(line(), BudgetDraft("500.0001"), "CAD"))
        // Still nonsense, and still told apart from the above.
        assertEquals(BudgetBlock.AMOUNT_NOT_MONEY, BudgetEdit.blockingReason(line(), BudgetDraft("five hundred"), "CAD"))
        // Two places is what CAD has, so these are fine.
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("500.99"), "CAD"))
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("500.9"), "CAD"))
    }

    @Test
    fun a_currency_without_cents_refuses_any_decimal() {
        val yen = line(allocated = "50000", suggested = "50000", spent = "0")
        assertEquals(BudgetBlock.AMOUNT_TOO_PRECISE, BudgetEdit.blockingReason(yen, BudgetDraft("60000.5"), "JPY"))
        assertNull(BudgetEdit.blockingReason(yen, BudgetDraft("60000"), "JPY"))
    }

    /**
     * `"1,200"` is twelve hundred, not a precision error — which is the whole
     * reason this asks [com.humblesolutions.finai.util.Money] rather than
     * counting the digits after the last dot or comma itself.
     */
    @Test
    fun a_thousands_separator_is_not_mistaken_for_precision() {
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("1,200"), "CAD"))
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("1,200.50"), "CAD"))
    }

    /** Zero is a decision — "I budget nothing here" — and the server takes it. */
    @Test
    fun zero_is_an_allowed_allocation() {
        assertNull(BudgetEdit.blockingReason(line(), BudgetDraft("0"), "CAD"))
        assertNull(BudgetEdit.blockingReasonForNew(BudgetDraft("0"), "CAD"))
    }

    @Test
    fun a_new_line_cannot_be_unchanged_but_is_refused_for_every_other_reason() {
        assertNull(BudgetEdit.blockingReasonForNew(BudgetDraft("120"), "CAD"))
        assertEquals(BudgetBlock.NO_AMOUNT, BudgetEdit.blockingReasonForNew(BudgetDraft(""), "CAD"))
        assertEquals(BudgetBlock.AMOUNT_NEGATIVE, BudgetEdit.blockingReasonForNew(BudgetDraft("-1"), "CAD"))
        assertEquals(BudgetBlock.AMOUNT_TOO_PRECISE, BudgetEdit.blockingReasonForNew(BudgetDraft("1.234"), "CAD"))
    }

    @Test
    fun income_and_transfers_can_never_hold_a_line() {
        assertFalse(BudgetEdit.isBudgetable("income"))
        assertFalse(BudgetEdit.isBudgetable("transfers"))
        assertTrue(BudgetEdit.isBudgetable("groceries"))
        assertTrue(BudgetEdit.isBudgetable("savings"))
        assertTrue(BudgetEdit.isBudgetable("debt_payment"))
    }

    // ── Ordering ────────────────────────────────────────────────────────
    // One order for Home and the Budget tab: over budget first, then closest
    // to the allocation (PO decision on #50).

    /** The worked example from the decision: the same four lines, one order everywhere. */
    @Test
    fun over_budget_leads_and_the_rest_follow_by_how_close_they_are_to_their_limit() {
        val lines = listOf(
            line(name = "Rent", id = "rent", slug = "rent", allocated = "2000", spent = "400"),
            line(name = "Groceries", id = "groceries", slug = "groceries", allocated = "440", spent = "380"),
            line(name = "Coffee", id = "coffee", slug = "coffee", allocated = "100", spent = "90"),
            line(name = "Dining", id = "dining", slug = "dining", allocated = "50", spent = "60"),
        )

        assertEquals(
            listOf("Dining", "Coffee", "Groceries", "Rent"),
            BudgetEdit.ordered(lines, "CAD").map { it.name },
        )
    }

    /** Closeness is the share of the allocation used, not the dollars left. */
    @Test
    fun a_small_line_nearly_spent_comes_before_a_big_line_barely_touched() {
        val big = line(name = "Rent", id = "a", slug = "rent", allocated = "2000", spent = "400")
        val tight = line(name = "Coffee", id = "b", slug = "coffee", allocated = "100", spent = "90")

        assertEquals(listOf("Coffee", "Rent"), BudgetEdit.ordered(listOf(big, tight), "CAD").map { it.name })
    }

    /**
     * Among overspends the furthest over leads. Home has four places; the
     * worst overspends must be the ones that show, not the alphabetically
     * first — which is what a bar fraction capped at 1 would have produced.
     */
    @Test
    fun the_line_furthest_over_its_allocation_leads_the_overspends() {
        val slightly = line(name = "Alpha", id = "a", slug = "a", allocated = "50", spent = "60")
        val badly = line(name = "Zulu", id = "z", slug = "z", allocated = "10", spent = "30")

        assertEquals(listOf("Zulu", "Alpha"), BudgetEdit.ordered(listOf(slightly, badly), "CAD").map { it.name })
    }

    @Test
    fun spending_against_nothing_allocated_is_as_far_over_as_a_line_can_be() {
        val nothingAllocated = line(name = "Alpha", id = "a", slug = "a", allocated = "0", spent = "1")
        val tripled = line(name = "Zulu", id = "z", slug = "z", allocated = "10", spent = "30")

        assertEquals(listOf("Alpha", "Zulu"), BudgetEdit.ordered(listOf(tripled, nothingAllocated), "CAD").map { it.name })
    }

    /** Two phones showing one budget must show it in one order. */
    @Test
    fun identical_lines_break_their_tie_the_same_way_whatever_order_they_arrive_in() {
        val a = line(name = "Apples", id = "id-a", slug = "a", allocated = "100", spent = "50")
        val b = line(name = "Bananas", id = "id-b", slug = "b", allocated = "100", spent = "50")

        assertEquals(
            BudgetEdit.ordered(listOf(a, b), "CAD").map { it.categoryId },
            BudgetEdit.ordered(listOf(b, a), "CAD").map { it.categoryId },
        )
    }

    /** "See budget" opens a list whose top four are the four Home showed. */
    @Test
    fun home_shows_the_top_of_the_list_the_budget_tab_shows() {
        val lines = (1..6).map {
            line(name = "Cat $it", id = "id-$it", slug = "s$it", allocated = "100", spent = (it * 10).toString())
        }
        val budget = Budget(currency = "CAD", lines = lines)

        assertEquals(BudgetEdit.ordered(lines, "CAD").take(4), BudgetEdit.forHome(budget))
    }

    // ── Months ──────────────────────────────────────────────────────────

    @Test
    fun the_selector_runs_back_from_the_current_month_and_rolls_the_year() {
        assertEquals(
            listOf("2026-02", "2026-01", "2025-12", "2025-11"),
            BudgetEdit.months("2026-02", count = 4),
        )
    }

    @Test
    fun a_month_that_cannot_be_read_still_offers_itself() {
        assertEquals(listOf("not-a-month"), BudgetEdit.months("not-a-month"))
    }

    @Test
    fun a_budget_s_full_date_reduces_to_the_month_the_endpoint_takes() {
        assertEquals("2026-10", BudgetEdit.monthKeyOf(Budget(month = "2026-10-01")))
    }
}
