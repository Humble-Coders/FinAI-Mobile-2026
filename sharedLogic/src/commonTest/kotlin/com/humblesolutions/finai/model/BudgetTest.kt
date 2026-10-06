package com.humblesolutions.finai.model

import com.humblesolutions.finai.data.FinAiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetTest {

    private fun line(
        allocated: String = "440.00",
        spent: String = "380.00",
        suggested: String = "440.00",
        userSet: Boolean = false,
    ) = BudgetLine(
        categoryId = "cat",
        slug = "groceries",
        name = "Groceries",
        suggested = suggested,
        allocated = allocated,
        isUserSet = userSet,
        spent = spent,
    )

    // ── Over budget ─────────────────────────────────────────────────────

    @Test
    fun a_line_is_over_only_once_spending_passes_the_allocation() {
        assertFalse(line(allocated = "440.00", spent = "439.99").isOver())
        // Exactly at the allocation is within it, not over it.
        assertFalse(line(allocated = "440.00", spent = "440.00").isOver())
        assertTrue(line(allocated = "440.00", spent = "440.01").isOver())
    }

    @Test
    fun the_overspend_is_the_difference_and_is_null_when_within() {
        assertNull(line(allocated = "440.00", spent = "100.00").overBy())
        assertEquals("40.00", line(allocated = "440.00", spent = "480.00").overBy())
        assertEquals("0.01", line(allocated = "440.00", spent = "440.01").overBy())
    }

    @Test
    fun a_zero_allocation_is_over_as_soon_as_anything_is_spent() {
        assertTrue(line(allocated = "0.00", spent = "0.01").isOver())
        assertEquals("0.01", line(allocated = "0.00", spent = "0.01").overBy())
        assertFalse(line(allocated = "0.00", spent = "0.00").isOver())
    }

    // ── The bar ─────────────────────────────────────────────────────────

    @Test
    fun the_bar_runs_from_empty_to_full_and_stops_there() {
        assertEquals(0.0, line(allocated = "400.00", spent = "0.00").fraction())
        assertEquals(0.5, line(allocated = "400.00", spent = "200.00").fraction())
        assertEquals(1.0, line(allocated = "400.00", spent = "400.00").fraction())
        // Overspent fills the track rather than overflowing it; the words say the rest.
        assertEquals(1.0, line(allocated = "400.00", spent = "4000.00").fraction())
    }

    @Test
    fun a_zero_allocation_reads_full_only_when_something_was_spent() {
        assertEquals(0.0, line(allocated = "0.00", spent = "0.00").fraction())
        assertEquals(1.0, line(allocated = "0.00", spent = "0.01").fraction())
    }

    // ── Overrides ───────────────────────────────────────────────────────

    @Test
    fun an_override_only_counts_when_it_actually_differs_from_the_suggestion() {
        assertTrue(line(allocated = "500.00", suggested = "440.00", userSet = true).differsFromSuggestion())
        // Set by hand to the same number the server would have picked: nothing to show.
        assertFalse(line(allocated = "440.00", suggested = "440.00", userSet = true).differsFromSuggestion())
        // Not set by hand at all.
        assertFalse(line(allocated = "440.00", suggested = "440.00").differsFromSuggestion())
    }

    // ── Decoding ────────────────────────────────────────────────────────

    /**
     * The shape `GET /budgets/{YYYY-MM}` actually returns, taken from the
     * backend's `BudgetOut` (Finance-backend `app/schemas/budget.py`).
     */
    @Test
    fun a_ready_budget_decodes_from_the_server_s_shape() {
        val json = """
            {
              "status": "ready",
              "month": "2026-10-01",
              "currency": "CAD",
              "learning": null,
              "expected_income": "5000.00",
              "lines": [
                {
                  "category_id": "11111111-1111-1111-1111-111111111111",
                  "slug": "groceries",
                  "name": "Groceries",
                  "suggested": "440.00",
                  "allocated": "500.00",
                  "is_user_set": true,
                  "spent": "380.00"
                }
              ],
              "savings": {
                "category_id": "22222222-2222-2222-2222-222222222222",
                "slug": "savings",
                "name": "Savings",
                "suggested": "900.00",
                "allocated": "900.00",
                "is_user_set": false,
                "spent": "0.00"
              },
              "debt": null,
              "total_allocated": "1400.00",
              "total_spent": "380.00",
              "shortfall": null,
              "uncategorised_spent": "12.50",
              "uncategorised_count": 3
            }
        """.trimIndent()

        val budget = FinAiJson.decodeFromString(Budget.serializer(), json)

        assertEquals(BudgetStatus.READY, budget.status)
        assertTrue(budget.isReady)
        assertEquals("2026-10-01", budget.month)
        assertEquals("CAD", budget.currency)
        assertEquals("5000.00", budget.expectedIncome)
        assertEquals(1, budget.lines.size)
        assertEquals("500.00", budget.lines.first().allocated)
        assertTrue(budget.lines.first().isUserSet)
        assertEquals("Savings", budget.savings?.name)
        assertNull(budget.debt)
        assertFalse(budget.hasShortfall)
        assertTrue(budget.hasUncategorised)
        assertEquals(3, budget.uncategorisedCount)
        // Savings and debt are rows too, listed after the spending lines.
        assertEquals(2, budget.allLines.size)
    }

    /**
     * Still learning does not mean an empty budget: the server keeps sending
     * the lines the person set by hand, because manual budgeting is available
     * from the first day (PRD F9).
     */
    @Test
    fun a_learning_budget_still_carries_its_hand_set_lines() {
        val json = """
            {
              "status": "learning",
              "month": "2026-10-01",
              "currency": "CAD",
              "learning": {
                "ready": false,
                "complete_months": 0,
                "transactions": 12,
                "needs": {"complete_months": 1, "transactions": 20}
              },
              "expected_income": "5000.00",
              "lines": [
                {
                  "category_id": "33333333-3333-3333-3333-333333333333",
                  "slug": "groceries",
                  "name": "Groceries",
                  "suggested": "0.00",
                  "allocated": "300.00",
                  "is_user_set": true,
                  "spent": "40.00"
                }
              ],
              "savings": null,
              "debt": null,
              "total_allocated": "300.00",
              "total_spent": "40.00",
              "shortfall": null,
              "uncategorised_spent": "0.00",
              "uncategorised_count": 0
            }
        """.trimIndent()

        val budget = FinAiJson.decodeFromString(Budget.serializer(), json)

        assertEquals(BudgetStatus.LEARNING, budget.status)
        assertFalse(budget.isReady)
        assertEquals(12, budget.learning?.transactions)
        assertEquals(20, budget.learning?.needs?.transactions)
        assertEquals(1, budget.learning?.needs?.completeMonths)
        assertEquals(1, budget.lines.size)
        assertTrue(budget.lines.first().isUserSet)
    }

    @Test
    fun a_shortfall_is_read_when_the_server_sends_one() {
        val json = """
            {
              "status": "ready",
              "month": "2026-10-01",
              "currency": "CAD",
              "expected_income": "3000.00",
              "lines": [],
              "total_allocated": "3200.00",
              "total_spent": "0.00",
              "shortfall": "200.00",
              "uncategorised_spent": "0.00",
              "uncategorised_count": 0
            }
        """.trimIndent()

        val budget = FinAiJson.decodeFromString(Budget.serializer(), json)

        assertTrue(budget.hasShortfall)
        assertEquals("200.00", budget.shortfall)
    }

    /** An older server, or a field added later, must not break decoding. */
    @Test
    fun a_payload_missing_everything_optional_still_decodes() {
        val budget = FinAiJson.decodeFromString(Budget.serializer(), """{"status": "ready"}""")

        assertEquals(BudgetStatus.READY, budget.status)
        assertEquals("0", budget.totalAllocated)
        assertEquals(emptyList(), budget.lines)
        assertNull(budget.savings)
        assertNull(budget.learning)
    }

    /** A status this build does not know must not read as a ready budget. */
    @Test
    fun an_unknown_status_is_not_treated_as_ready() {
        val budget = FinAiJson.decodeFromString(Budget.serializer(), """{"status": "rebuilding"}""")

        assertEquals(BudgetStatus.UNKNOWN, budget.status)
        assertFalse(budget.isReady)
    }
}
