package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetCommitment
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.CategorySpend
import com.humblesolutions.finai.model.UnbudgetedSpend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetBoardTest {

    private fun line(id: String, name: String, allocated: String, spent: String = "0.00") = BudgetLine(categoryId = id, slug = id, name = name, allocated = allocated, spent = spent)

    private val october = Budget(
        month = "2026-10-01",
        currency = "CAD",
        expectedIncome = "5000.00",
        monthIncome = "0.00",
        monthSpending = "0.00",
        monthSaved = "0.00",
        monthLeft = "0.00",
        incomeExpected = "5000.00",
        commitmentsDue = "1936.48",
        budgetRest = "1135.00",
        savingsDue = "500.00",
        projectedLeft = "1428.52",
        totalAllocated = "3435.00",
        totalSpent = "0.00",
        lines = listOf(
            line("rent", "Rent & mortgage", "1800.00"),
            line("groceries", "Groceries", "400.00"),
            line("utilities", "Utilities", "215.00"),
            line("transport", "Transport", "120.00"),
            line("dining", "Dining", "500.00"),
            line("shopping", "Shopping", "400.00"),
        ),
        commitments = listOf(BudgetCommitment(name = "Rent", expected = "1800.00"), BudgetCommitment(name = "Gym", expected = "40.00", paidOn = "2026-10-02")),
        savingsCategoryId = "savings",
    )

    @Test
    fun the_month_so_far_names_what_is_left_now() {
        val soFar = BudgetBoard.soFar(october)!!
        assertEquals("In progress", soFar.badge)
        assertEquals("Income received", soFar.income.label)
        assertEquals(1, soFar.income.sign)
        assertEquals("Left now", soFar.leftLabel)
        assertEquals("$0.00", soFar.left)
        assertFalse(soFar.leftNegative)
        assertEquals("Final", BudgetBoard.soFar(october.copy(monthEnded = true, monthLeft = "-20.00"))!!.badge)
        assertTrue(BudgetBoard.soFar(october.copy(monthEnded = true, monthLeft = "-20.00"))!!.leftNegative)
    }

    @Test
    fun the_forecast_lands_as_a_share_of_income() {
        val forecast = BudgetBoard.forecast(october)!!
        assertEquals(listOf("Expected income", "Commitments due", "Rest of your budget", "Savings still to set aside").take(3), forecast.rows.map { it.label })
        assertEquals("$1,428.52", forecast.left)
        assertTrue(forecast.surplus)
        assertEquals("Surplus", forecast.statusLabel)
        assertEquals(29, forecast.percentLeft)
        assertEquals("What's still to come in Oct", forecast.subtitle)
        val short = BudgetBoard.forecast(october.copy(projectedLeft = "-250.00"))!!
        assertEquals("$250.00", short.left)
        assertEquals("Short", short.statusLabel)
        assertEquals(0f, short.fraction)
        assertNull(BudgetBoard.forecast(october.copy(monthEnded = true)))
    }

    @Test
    fun the_donut_names_four_and_folds_the_rest_and_adds_up() {
        val donut = BudgetBoard.donut(october)!!
        assertEquals(listOf("Rent & mortgage", "Dining", "Groceries", "Shopping", "Other (2)"), donut.slices.map { it.label })
        assertEquals(100, donut.slices.sumOf { it.percent })
        assertEquals("$3.4k", donut.centre)
        assertEquals("$0.00 / $3,435.00", donut.totals)
        assertEquals("52% allotted", BudgetBoard.allotted(october.lines[0], october))
        assertEquals(0, BudgetBoard.colorIndexOf(october.lines[0], october))
        assertEquals(1, BudgetBoard.colorIndexOf(october.lines[4], october), "Dining is second largest")
    }

    @Test
    fun an_ended_month_draws_what_was_spent() {
        val august = october.copy(
            monthEnded = true,
            spendingByCategory = listOf(CategorySpend("rent", "rent", "Rent & mortgage", "1800.00"), CategorySpend(null, null, null, "200.00")),
        )
        val donut = BudgetBoard.donut(august)!!
        assertEquals(listOf(90, 10), donut.slices.map { it.percent })
        assertEquals("Spent", donut.centreCaption)
        assertEquals("90% of spending", BudgetBoard.shareOfSpending(0, august))
    }

    @Test
    fun whole_percents_always_make_a_hundred() {
        assertEquals(listOf(34, 33, 33), BudgetBoard.wholePercents(listOf(1.0 / 3, 1.0 / 3, 1.0 / 3)))
        assertEquals(100, BudgetBoard.wholePercents(listOf(0.524, 0.116, 0.063, 0.035, 0.262)).sum())
    }

    @Test
    fun the_next_steps_end_on_the_money_left() {
        val busy = october.copy(
            monthIncome = "4000.00",
            uncategorisedCount = 3,
            unbudgeted = listOf(UnbudgetedSpend(categoryId = "electronics", slug = "electronics", name = "Electronics", spent = "1200.00")),
        )
        val steps = BudgetBoard.nextSteps(busy, planAvailable = true)
        assertEquals(3, steps.size)
        assertEquals(listOf(NextStepAction.REVIEW, NextStepAction.IMPORT, NextStepAction.PLAN), steps.map { it.action })
        assertEquals("Sort 3 transactions", steps[0].title)
        assertEquals("Commitments not seen yet: 1", steps[1].title)
        assertEquals("Put $1,428.52 toward your goals", steps[2].title)
        assertTrue(steps[2].emphasized)

        val noPlan = BudgetBoard.nextSteps(busy, planAvailable = false)
        assertEquals(NextStepAction.ADD_UNBUDGETED, noPlan[2].action)
        assertEquals("electronics", noPlan[2].categoryId)
        assertEquals("1 thing worth doing", BudgetBoard.nextStepsSubtitle(1))
    }

    @Test
    fun a_month_with_nothing_imported_asks_for_its_statement_first() {
        val steps = BudgetBoard.nextSteps(october, planAvailable = false)
        assertEquals(NextStepAction.IMPORT, steps[0].action)
        assertEquals("Import this month's statement", steps[0].title)
        assertEquals(NextStepAction.SAVINGS_TARGET, steps[1].action)
    }
}
