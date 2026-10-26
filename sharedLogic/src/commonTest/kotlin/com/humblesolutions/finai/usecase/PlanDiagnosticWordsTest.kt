package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.DiagnosticGoal
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanDiagnosticWordsTest {

    private val allocation = PlanDiagnostic(
        kind = "allocation",
        currency = "CAD",
        leftover = "1562.00",
        savingsTarget = "1000.00",
        savingsNow = "400.00",
        savingsGap = "600.00",
        toSavings = "600.00",
        toGoals = "0.00",
        toDebt = "962.00",
        toInvest = "0.00",
        debtFirst = "Visa",
        debtFirstRatePercent = "20.99",
        per100 = "100.00",
        debtPer100 = "136.43",
        investPer100 = "32.50",
        assumedReturnPercent = "5.00",
        interestSaved = "1004.37",
        debtFree = "2030-06-01",
        debtFreeMinimums = "2031-12-01",
    )

    @Test
    fun the_money_left_over_goes_in_the_plan_order() {
        val rows = PlanDiagnosticWords.allocationRows(allocation)
        assertEquals(listOf("Savings", "Your goals", "Extra on Visa", "Save or invest"), rows.map { it.label })
        assertEquals("$600.00", rows[0].amount)
        assertEquals("$600.00 to go to reach $1,000.00", rows[0].detail)
        assertEquals("Highest rate first: 20.99%", rows[2].detail)
        assertTrue(rows[1].isZero)
        assertFalse(rows[2].isZero)
    }

    @Test
    fun each_goal_gets_its_own_row_by_priority() {
        val withGoals = allocation.copy(goals = listOf(DiagnosticGoal("g1", "Vacation", "120.00")))
        val rows = PlanDiagnosticWords.allocationRows(withGoals)
        assertEquals("Vacation", rows[1].label)
        assertEquals("By priority", rows[1].detail)
    }

    @Test
    fun paying_the_debt_is_set_beside_investing() {
        assertEquals(
            "Every $100.00 a month on Visa (20.99%) saves about $136.43 of interest in its first year. " +
                "Invested at an assumed 5.00% a year, it might earn about $32.50.",
            PlanDiagnosticWords.comparison(allocation),
        )
        assertNull(PlanDiagnosticWords.comparison(allocation.copy(debtFirst = null)))
        assertEquals(
            "Debt-free by Jun 2030 instead of Dec 2031, with $1,004.37 less interest.",
            PlanDiagnosticWords.payoff(allocation),
        )
    }

    @Test
    fun what_comes_before_investing_is_listed_done_or_not() {
        val invest = allocation.copy(
            kind = "invest",
            bufferFull = false,
            expensiveDebts = listOf("Visa"),
            goalsCovered = true,
            rateLinePercent = "6.00",
            existingInvestments = "8000.00",
        )
        val checks = PlanDiagnosticWords.investChecks(invest)
        assertEquals(
            listOf(
                DiagnosticCheck(false, "Savings $600.00 short of $1,000.00"),
                DiagnosticCheck(false, "Visa charges more than 6.00%"),
                DiagnosticCheck(true, "Your goals get what they need"),
            ),
            checks,
        )
        assertEquals(listOf("Free to invest each month", "Already invested"), PlanDiagnosticWords.investRows(invest).map { it.label })
    }

    @Test
    fun the_form_takes_amounts_or_blanks_and_counts_runs() {
        assertNull(PlanDiagnosticWords.blockingReason("1000", "", "CAD"))
        assertEquals(DiagnosticBlock.AMOUNT_INVALID, PlanDiagnosticWords.blockingReason("lots", "0", "CAD"))
        val diagnostics = PlanDiagnostics(runsLeft = mapOf("allocation" to 3, "invest" to 0))
        assertEquals("3 fresh runs left this month", PlanDiagnosticWords.runsLeft(diagnostics, "allocation"))
        assertEquals("No fresh runs left this month — the last answer stays.", PlanDiagnosticWords.runsLeft(diagnostics, "invest"))
    }

    @Test
    fun the_two_orders_sit_side_by_side_with_what_would_wait() {
        val choice = allocation.copy(
            toGoals = "120.00",
            goalSharePercent = "20.00",
            altAvailable = true,
            altToDebt = "1082.00",
            altInterestSaved = "1150.00",
            altDebtFree = "2029-03-01",
            goalsWaiting = listOf("Trip to Lisbon"),
            recommend = "debt_first",
            recommendWhy = "The card costs more than the trip can wait.",
        )
        assertEquals("AI suggests: pay debt before your goals", PlanDiagnosticWords.recommendation(choice))
        val rows = PlanDiagnosticWords.orders(choice)
        assertEquals("Your plan (20.00% to goals)", rows[0].label)
        assertEquals("$962.00 a month to debt", rows[0].amount)
        assertEquals("debt-free Jun 2030 · $1,004.37 less interest", rows[0].detail)
        assertEquals("Debt before goals", rows[1].label)
        assertEquals("debt-free Mar 2029 · $1,150.00 less interest", rows[1].detail)
        assertEquals("Would wait: Trip to Lisbon", PlanDiagnosticWords.waiting(choice))
        assertTrue(PlanDiagnosticWords.canApplyDebtFirst(choice))
        assertFalse(PlanDiagnosticWords.canApplyDebtFirst(choice.copy(goalSharePercent = "0.00")), "already debt first")
        assertFalse(PlanDiagnosticWords.canApplyDebtFirst(choice.copy(recommend = "plan")))
    }

    @Test
    fun with_no_choice_there_is_no_recommendation() {
        val none = allocation.copy(altAvailable = false, recommend = "debt_first")
        assertNull(PlanDiagnosticWords.recommendation(none))
        assertTrue(PlanDiagnosticWords.orders(none).isEmpty())
        assertNull(PlanDiagnosticWords.waiting(none))
        assertFalse(PlanDiagnosticWords.canApplyDebtFirst(none))
    }
}
