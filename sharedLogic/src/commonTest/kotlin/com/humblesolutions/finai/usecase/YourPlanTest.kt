package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanDebt
import com.humblesolutions.finai.model.PlanGoalShare
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanSplit
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.model.PlanSuggested
import com.humblesolutions.finai.model.PlanTotals
import com.humblesolutions.finai.util.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YourPlanTest {

    private val words = YourPlan.Words(currency = "CAD")

    private fun money(amount: String) = Money.format(amount, "CAD")

    private val suggested = PlanSuggested(buffer = "1000.00", goalSharePercent = "20.00", rateLinePercent = "6.00", strategy = PlanStrategy.AVALANCHE)

    private val preferences = PlanPreferences(
        saved = true,
        buffer = "1000.00",
        bufferGoalId = "g-fund",
        goalSharePercent = "20.00",
        rateLinePercent = "6.00",
        strategy = PlanStrategy.AVALANCHE,
        suggested = suggested,
    )

    private val visa = PlanDebt(debtId = "d-visa", name = "Visa", ratePercent = "20.99", order = 1, getsExtraNow = true, paidOff = "2027-03", interest = "180.73", balance = "4000.00")
    private val loc = PlanDebt(debtId = "d-loc", name = "Line of credit", ratePercent = "9.50", order = 2, paidOff = "2027-07", interest = "246.03", balance = "5000.00")
    private val mortgage = PlanDebt(debtId = "d-home", name = "Mortgage", ratePercent = "4.50", minimumOnly = true, paidOff = "2050-01", interest = "90000.00", balance = "300000.00")
    private val unknown = PlanDebt(debtId = "d-x", name = "Store card", missingRate = true, balance = "300.00")

    private val plan = Plan(
        month = "2026-11",
        currency = "CAD",
        status = PlanStatus.READY,
        leftover = "1200.00",
        preferences = preferences,
        split = PlanSplit(
            buffer = "800.00",
            bufferSaved = "200.00",
            bufferTarget = "1000.00",
            goals = listOf(PlanGoalShare("g-house", "House", "80.00")),
            extraDebt = "320.00",
            saveOrInvest = "0.00",
        ),
        debts = listOf(unknown, mortgage, loc, visa),
        totals = PlanTotals(totalInterest = null, debtFree = null, interestSaved = "719.27", otherStrategy = PlanStrategy.SNOWBALL, otherStrategyDifference = "42.10"),
    )

    @Test
    fun `the headline says what is left over or why nothing is`() {
        assertEquals("${money("1200.00")} left over this month", YourPlan.headline(plan, words))
        assertTrue(YourPlan.headline(plan.copy(status = PlanStatus.LEARNING), words).startsWith("Your plan starts"))
        assertTrue(YourPlan.headline(plan.copy(shortfall = "300.00"), words).contains(money("300.00")))
        assertEquals("Nothing is left over this month", YourPlan.headline(plan.copy(leftover = "0.00"), words))
    }

    @Test
    fun `the split is in the order the money is divided`() {
        val rows = YourPlan.splitRows(plan, words)

        assertEquals(listOf(SplitKind.BUFFER, SplitKind.GOAL, SplitKind.EXTRA_DEBT, SplitKind.SAVE_OR_INVEST), rows.map { it.kind })
        assertEquals("On top of the minimums, to Visa first", rows[2].detail)
        assertTrue(rows[3].isZero, "nothing to save or invest yet: shown quieter, not hidden")
        assertEquals("${money("200.00")} of ${money("1000.00")} saved", rows[0].detail)
    }

    @Test
    fun `no buffer means no buffer row`() {
        val rows = YourPlan.splitRows(plan.copy(preferences = preferences.copy(buffer = null)), words)

        assertTrue(rows.none { it.kind == SplitKind.BUFFER })
    }

    @Test
    fun `home shows the parts with money in them`() {
        val card = YourPlan.homeCard(plan, words)

        assertEquals(listOf(SplitKind.BUFFER, SplitKind.GOAL, SplitKind.EXTRA_DEBT), card.rows.map { it.kind })
        assertTrue(YourPlan.homeCard(plan.copy(status = PlanStatus.LEARNING), words).rows.isEmpty())
    }

    @Test
    fun `a goal shows what the plan gives it`() {
        assertEquals("Your plan suggests ${money("80.00")} this month", YourPlan.suggestedFor("g-house", plan, words))
        assertEquals("Your plan suggests ${money("800.00")} this month", YourPlan.suggestedFor("g-fund", plan, words))
        assertNull(YourPlan.suggestedFor("g-other", plan, words))
        assertNull(YourPlan.suggestedFor("g-house", plan.copy(shortfall = "10.00"), words))
    }

    @Test
    fun `debts are listed in payoff order then minimum only then missing a rate`() {
        val rows = YourPlan.debtRows(plan, words)

        assertEquals(listOf("d-visa", "d-loc", "d-home", "d-x"), rows.map { it.debtId })
        assertEquals("Step 1", rows[0].step)
        assertEquals("Your extra payment goes here this month.", rows[0].note)
        assertEquals("Minimum only: 4.5% is under your 6% rate line.", rows[2].note)
        assertEquals("Add the interest rate to plan this debt.", rows[3].payoff)
        assertTrue(rows[3].payoffNeedsAttention)
    }

    @Test
    fun `a debt never paid off says so`() {
        val never = plan.copy(debts = listOf(visa.copy(paidOff = null, neverPaidOff = true, order = null)))

        val row = YourPlan.debtRows(never, words).single()

        assertTrue(row.payoff.startsWith("Never paid off"))
        assertTrue(row.payoffNeedsAttention)
    }

    @Test
    fun `the totals say the debt-free date or why there is none`() {
        assertEquals("Add every interest rate to see when you'll be debt-free.", YourPlan.debtFreeLine(plan, words))
        val known = plan.copy(debts = listOf(visa), totals = plan.totals.copy(debtFree = "2027-03"))
        assertEquals("Debt-free by Mar 2027", YourPlan.debtFreeLine(known, words))
        assertNull(YourPlan.debtFreeLine(plan.copy(debts = emptyList()), words))
        assertEquals("${money("719.27")} less interest than paying only the minimums", YourPlan.interestSavedLine(plan, words))
    }

    @Test
    fun `the comparison is said from the chosen side`() {
        assertEquals("Smallest balance first would cost ${money("42.10")} more in interest.", YourPlan.comparisonLine(plan, words))
        val cheaper = plan.copy(totals = plan.totals.copy(otherStrategy = PlanStrategy.AVALANCHE, otherStrategyDifference = "-42.10"))
        assertEquals("Highest rate first would cost ${money("42.10")} less in interest.", YourPlan.comparisonLine(cheaper, words))
        val same = plan.copy(totals = plan.totals.copy(otherStrategyDifference = "0.00"))
        assertEquals("Smallest balance first would cost the same in interest.", YourPlan.comparisonLine(same, words))
    }

    @Test
    fun `a choice that differs from the suggestion says the suggestion`() {
        val rows = YourPlan.choiceRows(preferences.copy(goalSharePercent = "30.00"), words)

        assertEquals(3, rows.size, "goal share, rate line and strategy: no buffer")
        assertEquals("30%", rows[0].value)
        assertEquals("Suggested: 20%", rows[0].suggestion)
        assertNull(rows[1].suggestion)
        assertEquals("Highest rate first", rows[2].value)
    }

    @Test
    fun `saving unchanged choices is refused once saved but not before`() {
        val draft = YourPlan.draftOf(preferences)

        assertEquals(PlanChoiceBlock.NOTHING_CHANGED, YourPlan.blockingReason(preferences, draft, "CAD"))
        assertNull(YourPlan.blockingReason(preferences.copy(saved = false), draft, "CAD"), "accepting the suggestions is an answer")
    }

    @Test
    fun `each choice is checked`() {
        val draft = YourPlan.draftOf(preferences)

        assertFalse(draft.hasBuffer, "no buffer step to choose")
        assertEquals(PlanChoiceBlock.GOAL_SHARE_INVALID, YourPlan.blockingReason(preferences, draft.copy(goalSharePercent = "101"), "CAD"))
        assertEquals(PlanChoiceBlock.RATE_LINE_INVALID, YourPlan.blockingReason(preferences, draft.copy(rateLinePercent = ""), "CAD"))
        assertNull(YourPlan.blockingReason(preferences, draft.copy(goalSharePercent = "25"), "CAD"))
    }

    @Test
    fun `no buffer and every share are sent as chosen`() {
        val draft = YourPlan.draftOf(preferences).copy(hasBuffer = false, goalSharePercent = "0", rateLinePercent = "100", strategy = PlanStrategy.SNOWBALL)

        val sent = YourPlan.toSave(draft, "CAD")

        assertNull(sent.buffer)
        assertEquals("0.00", sent.goalSharePercent)
        assertEquals("100.00", sent.rateLinePercent)
        assertEquals(PlanStrategy.SNOWBALL, sent.strategy)
    }

    @Test
    fun `the switch alone never makes the first save`() {
        assertNull(YourPlan.strategyOnly(preferences.copy(saved = false), PlanStrategy.SNOWBALL))
        val sent = assertNotNull(YourPlan.strategyOnly(preferences, PlanStrategy.SNOWBALL))
        assertEquals("1000.00" to PlanStrategy.SNOWBALL, sent.buffer to sent.strategy)
    }

    @Test
    fun `saving never makes a buffer goal any more`() {
        val fresh = preferences.copy(bufferGoalId = null)

        assertNull(YourPlan.bufferGoalNote(fresh, YourPlan.draftOf(fresh)))
        assertNull(YourPlan.toSave(YourPlan.draftOf(fresh), "CAD").buffer)
    }
}
