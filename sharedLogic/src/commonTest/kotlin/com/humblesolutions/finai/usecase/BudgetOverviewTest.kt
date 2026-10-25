package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetCommitment
import com.humblesolutions.finai.model.CategorySpend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetOverviewTest {

    private val october = Budget(
        month = "2026-10-01",
        currency = "CAD",
        monthIncome = "5000.00",
        monthSpending = "2857.37",
        monthNet = "2142.63",
        monthSaved = "500.00",
        monthLeft = "1642.63",
        commitments = listOf(
            BudgetCommitment(name = "Rent", expected = "1800.00", dueDay = 1, category = "rent", paidOn = "2026-10-01", paidAmount = "1800.00"),
            BudgetCommitment(name = "Internet", expected = "80.00", dueDay = 25, category = "utilities"),
            BudgetCommitment(name = "Gym", expected = "40.00"),
        ),
        incomeExpected = "0.00",
        commitmentsDue = "222.00",
        budgetRest = "640.00",
        projectedLeft = "1280.63",
    )

    private val august = Budget(
        month = "2026-08-01",
        currency = "CAD",
        monthEnded = true,
        monthIncome = "5000.00",
        monthSpending = "4716.79",
        monthNet = "283.21",
        monthSaved = "500.00",
        monthLeft = "-216.79",
        commitments = listOf(BudgetCommitment(name = "GoodLife Fitness", expected = "54.99", dueDay = 3)),
        spendingByCategory = listOf(
            CategorySpend("cat-rent", "rent", "Rent & mortgage", "1800.00"),
            CategorySpend("cat-shopping", "shopping", "Shopping", "1249.99"),
            CategorySpend(null, null, null, "12.34"),
        ),
    )

    @Test
    fun a_running_month_reads_as_so_far_and_shows_its_plan() {
        assertTrue(BudgetOverview.showsPlan(october))
        assertEquals("This month so far", BudgetOverview.soFarTitle(october))
        assertEquals(
            listOf(
                OverviewRow("Income received", "$5,000.00"),
                OverviewRow("Spent", "$2,857.37"),
                OverviewRow("Saved", "$500.00"),
                OverviewRow("Left now", "$1,642.63", isTotal = true),
            ),
            BudgetOverview.soFar(october),
        )
    }

    @Test
    fun a_commitment_says_when_it_was_paid_or_when_it_is_due() {
        val rows = BudgetOverview.commitments(october)
        assertEquals("Paid Oct 1", rows[0].status)
        assertTrue(rows[0].seen)
        assertEquals("Not seen yet · due Oct 25", rows[1].status)
        assertEquals("Not seen yet", rows[2].status)
        assertFalse(rows[2].seen)
    }

    @Test
    fun what_is_still_to_come_lands_on_the_month_end_figure() {
        assertEquals(
            listOf(
                OverviewRow("Income still expected", "+$0.00"),
                OverviewRow("Commitments not seen yet", "−$222.00"),
                OverviewRow("Rest of your budget", "−$640.00"),
                OverviewRow("Left at month end", "$1,280.63", isTotal = true),
            ),
            BudgetOverview.stillExpected(october),
        )
        assertEquals(
            "If the month goes to plan, you'll have $1,280.63 left after spending and saving. Put it toward your goals or add it to your savings.",
            BudgetOverview.suggestion(october),
        )
    }

    @Test
    fun an_ended_month_is_what_happened_and_nothing_is_still_to_come() {
        assertFalse(BudgetOverview.showsPlan(august))
        assertEquals("How the month went", BudgetOverview.soFarTitle(august))
        assertEquals("Left over", BudgetOverview.soFar(august)?.last()?.label)
        assertNull(BudgetOverview.stillExpected(august))
        assertEquals("Not seen this month", BudgetOverview.commitments(august).single().status)
        // $283.21 net, less the $500 moved to savings.
        assertEquals("You spent $216.79 more than came in this month.", BudgetOverview.suggestion(august))
        val kept = august.copy(monthLeft = "83.21")
        assertEquals(
            "After spending and saving, you had $83.21 left this month. Put it toward your goals or add it to your savings.",
            BudgetOverview.suggestion(kept),
        )
    }

    @Test
    fun an_ended_month_lists_every_category_spent_in_and_the_unfiled_rest() {
        assertEquals(
            listOf(
                OverviewSpend("Rent & mortgage", "rent", "$1,800.00"),
                OverviewSpend("Shopping", "shopping", "$1,249.99"),
                OverviewSpend("Not filed yet", null, "$12.34"),
            ),
            BudgetOverview.spent(august),
        )
    }

    @Test
    fun a_month_that_went_short_says_so_instead_of_suggesting() {
        val short = august.copy(monthLeft = "-200.00")
        assertEquals("You spent $200.00 more than came in this month.", BudgetOverview.suggestion(short))
        assertFalse(BudgetOverview.suggestsUsing(short))
        val heading = october.copy(projectedLeft = "-50.00")
        assertEquals("At this rate you'll spend $50.00 more than comes in this month.", BudgetOverview.suggestion(heading))
    }

    @Test
    fun an_older_server_shows_none_of_it() {
        val old = Budget(month = "2026-10-01", currency = "CAD")
        assertNull(BudgetOverview.soFar(old))
        assertNull(BudgetOverview.stillExpected(old))
        assertNull(BudgetOverview.suggestion(old))
        assertTrue(BudgetOverview.commitments(old).isEmpty())
    }

    @Test
    fun a_due_day_past_the_month_end_is_its_last_day() {
        val february = october.copy(month = "2027-02-01", commitments = listOf(BudgetCommitment(name = "Rent", expected = "1800.00", dueDay = 31)))
        assertEquals("Not seen yet · due Feb 28", BudgetOverview.commitments(february).single().status)
    }

    @Test
    fun a_savings_target_still_to_set_aside_is_counted_before_what_is_left() {
        val target = com.humblesolutions.finai.model.BudgetLine(categoryId = "cat-savings", slug = "savings", name = "Savings", allocated = "800.00", isUserSet = true)
        val withTarget = october.copy(savings = target, savingsDue = "300.00")
        assertEquals(
            OverviewRow("Savings still to set aside", "−$300.00"),
            BudgetOverview.stillExpected(withTarget)?.get(3),
        )
        assertNull(BudgetOverview.stillExpected(october)?.firstOrNull { it.label == "Savings still to set aside" }, "no target, no row")
    }

    @Test
    fun until_a_target_is_set_the_room_for_one_is_offered() {
        val room = october.copy(savingsRoom = "1562.00", savingsCategoryId = "cat-savings")
        assertEquals("Up to $1,562.00 is left after your other lines.", BudgetOverview.savingsRoom(room))
        val line = BudgetOverview.savingsTargetToSet(room)
        assertEquals("cat-savings", line?.categoryId)
        assertEquals("savings", line?.slug)
        assertNull(BudgetOverview.savingsTargetToSet(october), "an older server names no savings category")
    }
}
