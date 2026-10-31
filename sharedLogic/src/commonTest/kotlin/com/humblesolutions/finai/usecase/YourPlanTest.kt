package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanCategory
import com.humblesolutions.finai.model.PlanDebt
import com.humblesolutions.finai.model.PlanEmergencyShare
import com.humblesolutions.finai.model.PlanGoalShare
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanRow
import com.humblesolutions.finai.model.PlanRowLimit
import com.humblesolutions.finai.model.PlanSplit
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.model.PlanSuggested
import com.humblesolutions.finai.model.PlanTotals
import com.humblesolutions.finai.model.PlanWeight
import com.humblesolutions.finai.util.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YourPlanTest {

    private val words = YourPlan.Words(currency = "CAD")

    private fun money(amount: String) = Money.format(amount, "CAD")

    private val weights = listOf(
        PlanWeight(PlanCategory.EMERGENCY_FUND, "20.00"),
        PlanWeight(PlanCategory.DEBT, "50.00"),
        PlanWeight(PlanCategory.GOALS, "20.00"),
        PlanWeight(PlanCategory.SAVE_OR_INVEST, "10.00"),
    )

    private val preferences = PlanPreferences(
        saved = true,
        rateLinePercent = "6.00",
        strategy = PlanStrategy.AVALANCHE,
        suggested = PlanSuggested(rateLinePercent = "6.00", weights = weights),
        weights = weights,
        hasEmergencyFund = true,
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
            emergencyFund = PlanEmergencyShare("g-fund", "Rainy day", "240.00", stillNeeded = "300.00"),
            goals = listOf(PlanGoalShare("g-house", "House", "240.00")),
            extraDebt = "600.00",
            saveOrInvest = "120.00",
            source = "ai",
            why = "The card costs the most.",
            limits = listOf(
                PlanRowLimit("debt", most = "1200.00"),
                PlanRowLimit("emergency_fund", most = "300.00"),
                PlanRowLimit("goal", "g-house", "1200.00"),
                PlanRowLimit("save_or_invest", most = "1200.00"),
            ),
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
    fun `the surplus card shows the month the amount and a bar of where it goes`() {
        val card = YourPlan.surplusCard(plan, words)

        assertEquals("Monthly surplus", card.title)
        assertEquals(money("1200.00"), card.amount)
        assertEquals(listOf(SplitKind.EMERGENCY, SplitKind.GOAL, SplitKind.EXTRA_DEBT, SplitKind.SAVE_OR_INVEST), card.segments.map { it.kind })
        assertEquals(0.5f, card.segments[2].fraction)
        assertEquals(1f, card.segments.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `with nothing to split the card has no amount and says why`() {
        val card = YourPlan.surplusCard(plan.copy(status = PlanStatus.LEARNING), words)

        assertNull(card.amount)
        assertTrue(card.segments.isEmpty())
        assertTrue(card.headline.startsWith("Your plan starts"))
    }

    @Test
    fun `the split has a row each and the emergency fund first`() {
        val rows = YourPlan.splitRows(plan, words)

        assertEquals(listOf(PlanRow.EMERGENCY_FUND, PlanRow.GOAL, PlanRow.DEBT, PlanRow.SAVE_OR_INVEST), rows.map { it.row })
        assertEquals("Rainy day", rows[0].label)
        assertEquals("${money("300.00")} still needed", rows[0].detail)
        assertEquals("On top of the minimums, to Visa first", rows[2].detail)
        assertEquals("600.00", rows[2].rawAmount)
    }

    @Test
    fun `without an emergency fund goal there is no row for it`() {
        val none = plan.copy(split = plan.split.copy(emergencyFund = null))

        assertTrue(YourPlan.splitRows(none, words).none { it.row == PlanRow.EMERGENCY_FUND })
    }

    @Test
    fun `the note says who worked it out`() {
        assertEquals(SplitNote("Worked out by AI from your choices", "The card costs the most."), YourPlan.splitNote(plan, words))
        assertEquals("Worked out from your choices", YourPlan.splitNote(plan.copy(split = plan.split.copy(source = "rules")), words)?.text)
        assertEquals("Includes amounts you changed", YourPlan.splitNote(plan.copy(split = plan.split.copy(edited = true)), words)?.text)
    }

    @Test
    fun `home shows the parts with money in them`() {
        val card = YourPlan.homeCard(plan, words)

        assertEquals(listOf(SplitKind.EMERGENCY, SplitKind.GOAL, SplitKind.EXTRA_DEBT), card.rows.map { it.kind })
        assertTrue(YourPlan.homeCard(plan.copy(status = PlanStatus.LEARNING), words).rows.isEmpty())
    }

    @Test
    fun `a goal shows what the plan gives it`() {
        assertEquals("Your plan suggests ${money("240.00")} this month", YourPlan.suggestedFor("g-house", plan, words))
        assertEquals("Your plan suggests ${money("240.00")} this month", YourPlan.suggestedFor("g-fund", plan, words))
        assertNull(YourPlan.suggestedFor("g-other", plan, words))
        assertNull(YourPlan.suggestedFor("g-house", plan.copy(shortfall = "10.00"), words))
    }

    // ── A row set by hand ───────────────────────────────────────────────

    private fun debtDraft(amount: String) = YourPlan.editDraftOf(YourPlan.splitRows(plan, words)[2]).copy(amount = amount)

    @Test
    fun `a row's new amount is checked against what it can take`() {
        assertNull(YourPlan.editBlockingReason(plan, debtDraft("700"), "CAD"))
        assertEquals(RowEditBlock.BLANK, YourPlan.editBlockingReason(plan, debtDraft(""), "CAD"))
        assertEquals(RowEditBlock.NOT_MONEY, YourPlan.editBlockingReason(plan, debtDraft("lots"), "CAD"))
        assertEquals(RowEditBlock.NEGATIVE, YourPlan.editBlockingReason(plan, debtDraft("-5"), "CAD"))
        assertEquals(RowEditBlock.OVER_LIMIT, YourPlan.editBlockingReason(plan, debtDraft("1300"), "CAD"))
        assertEquals(RowEditBlock.UNCHANGED, YourPlan.editBlockingReason(plan, debtDraft("600"), "CAD"))
    }

    @Test
    fun `the emergency fund cannot be set past what it still needs`() {
        val fund = YourPlan.editDraftOf(YourPlan.splitRows(plan, words)[0]).copy(amount = "350")

        assertEquals(RowEditBlock.OVER_LIMIT, YourPlan.editBlockingReason(plan, fund, "CAD"))
        assertEquals("That's more than this can take this month: at most ${money("300.00")}.", YourPlan.editBlockText(RowEditBlock.OVER_LIMIT, plan, fund, words))
    }

    @Test
    fun `the alert names where the amount would go`() {
        val goal = YourPlan.editDraftOf(YourPlan.splitRows(plan, words)[1]).copy(amount = "300")

        assertEquals("Update House too?", YourPlan.applyQuestion(goal, words).title)
        assertTrue(YourPlan.applyQuestion(goal, words).body.contains("House"))
        assertTrue(YourPlan.applyQuestion(debtDraft("700"), words).body.contains("Debt line"))
        val save = YourPlan.editDraftOf(YourPlan.splitRows(plan, words)[3]).copy(amount = "50")
        assertTrue(YourPlan.applyQuestion(save, words).body.contains("savings target"))
    }

    @Test
    fun `an edit is sent with its goal only for a goal`() {
        val goal = YourPlan.editDraftOf(YourPlan.splitRows(plan, words)[1]).copy(amount = "300")

        assertEquals("g-house", YourPlan.toEdit(goal, apply = true, currency = "CAD").goalId)
        assertEquals("300.00", YourPlan.toEdit(goal, apply = true, currency = "CAD").amount)
        assertNull(YourPlan.toEdit(debtDraft("700"), apply = false, currency = "CAD").goalId)
    }

    // ── The choices ─────────────────────────────────────────────────────

    @Test
    fun `the choices are listed in the person's order with their shares`() {
        val rows = YourPlan.weightRows(preferences, words)

        assertEquals(listOf("Emergency fund", "Debt payoff", "Goals", "Save or invest"), rows.map { it.label })
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.priority })
        assertEquals("50%", rows[1].percent)
    }

    @Test
    fun `an emergency fund is suggested only while there is none`() {
        assertNull(YourPlan.emergencySuggestion(preferences, words))
        assertNotNull(YourPlan.emergencySuggestion(preferences.copy(hasEmergencyFund = false), words))
    }

    @Test
    fun `the shares must add up to 100`() {
        val draft = YourPlan.weightsDraftOf(preferences)

        assertEquals(PlanChoiceBlock.NOTHING_CHANGED, YourPlan.blockingReason(preferences, draft))
        assertNull(YourPlan.blockingReason(preferences.copy(saved = false), draft), "the suggestions are a real answer")
        val short = YourPlan.withPercent(draft, PlanCategory.DEBT, "45")
        assertEquals(PlanChoiceBlock.NOT_100, YourPlan.blockingReason(preferences, short))
        assertEquals("The shares add up to 95%. They need to add up to 100%.", YourPlan.blockText(PlanChoiceBlock.NOT_100, short))
        assertEquals(PlanChoiceBlock.PERCENT_INVALID, YourPlan.blockingReason(preferences, YourPlan.withPercent(draft, PlanCategory.DEBT, "abc")))
    }

    @Test
    fun `moving and stepping change the order and the share`() {
        val draft = YourPlan.weightsDraftOf(preferences)

        val moved = YourPlan.moved(draft, PlanCategory.DEBT, up = true)
        assertEquals(PlanCategory.DEBT, moved.categories.first())
        assertEquals(moved, YourPlan.moved(moved, PlanCategory.DEBT, up = true), "already first")
        val stepped = YourPlan.stepped(YourPlan.stepped(moved, PlanCategory.DEBT, up = true), PlanCategory.SAVE_OR_INVEST, up = false)
        assertEquals("55", stepped.percents[PlanCategory.DEBT])
        assertEquals("5", stepped.percents[PlanCategory.SAVE_OR_INVEST])
        assertNull(YourPlan.blockingReason(preferences, stepped))
        assertEquals(PlanCategory.DEBT, YourPlan.toSave(stepped, PlanStrategy.AVALANCHE).weights?.first()?.category)
    }

    @Test
    fun `a share never steps past 0 or 100`() {
        val draft = YourPlan.withPercent(YourPlan.weightsDraftOf(preferences), PlanCategory.SAVE_OR_INVEST, "0")

        assertEquals("0", YourPlan.stepped(draft, PlanCategory.SAVE_OR_INVEST, up = false).percents[PlanCategory.SAVE_OR_INVEST])
    }

    @Test
    fun `the strategy switch keeps the saved choices`() {
        assertNull(YourPlan.strategyOnly(PlanStrategy.SNOWBALL).weights)
    }

    @Test
    fun `debt first gives debt the goals share and puts it first`() {
        val sent = assertNotNull(YourPlan.debtFirstChoices(preferences))

        assertEquals(PlanWeight(PlanCategory.DEBT, "70.00"), sent.weights?.first())
        assertEquals(PlanWeight(PlanCategory.GOALS, "0.00"), sent.weights?.last())
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
}
