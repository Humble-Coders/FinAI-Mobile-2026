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

    @Test
    fun over_budget_lines_come_first_whichever_order_is_asked_for() {
        val within = line(name = "Groceries", id = "a", allocated = "400", spent = "100")
        val over = line(name = "Dining", id = "b", slug = "dining", allocated = "50", spent = "60")

        for (order in BudgetOrder.entries) {
            val sorted = BudgetEdit.ordered(listOf(within, over), "CAD", order)
            assertEquals(listOf("Dining", "Groceries"), sorted.map { it.name }, "order $order")
        }
    }

    @Test
    fun the_budget_tab_sorts_the_rest_by_spend_and_home_by_how_close_they_are() {
        // Big spend, lots of room: first by spend, last by closeness.
        val big = line(name = "Rent", id = "a", slug = "rent", allocated = "2000", spent = "400")
        // Small spend, nearly full: last by spend, first by closeness.
        val tight = line(name = "Coffee", id = "b", slug = "coffee", allocated = "100", spent = "90")

        assertEquals(
            listOf("Rent", "Coffee"),
            BudgetEdit.ordered(listOf(tight, big), "CAD", BudgetOrder.BY_SPENT).map { it.name },
        )
        assertEquals(
            listOf("Coffee", "Rent"),
            BudgetEdit.ordered(listOf(big, tight), "CAD", BudgetOrder.BY_NEAREST_ALLOCATION).map { it.name },
        )
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

    @Test
    fun home_takes_the_top_of_the_list_the_tab_shows() {
        val lines = (1..6).map {
            line(name = "Cat $it", id = "id-$it", slug = "s$it", allocated = "100", spent = (it * 10).toString())
        }
        val budget = Budget(currency = "CAD", lines = lines)

        val home = BudgetEdit.forHome(budget)
        val all = BudgetEdit.ordered(lines, "CAD", BudgetOrder.BY_NEAREST_ALLOCATION)

        assertEquals(4, home.size)
        assertEquals(all.take(4), home)
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
