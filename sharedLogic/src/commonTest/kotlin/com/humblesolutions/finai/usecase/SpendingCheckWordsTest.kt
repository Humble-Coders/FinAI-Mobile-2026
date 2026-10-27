package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.CategoryTip
import com.humblesolutions.finai.model.SpendingCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendingCheckWordsTest {

    private val check = SpendingCheck(
        month = "2026-09-01",
        currency = "CAD",
        income = "5000.00",
        needs = "2600.00",
        wants = "2050.00",
        saved = "150.00",
        needsPercent = 52,
        wantsPercent = 41,
        savedPercent = 3,
        needsOver = true,
        wantsOver = true,
        savingsShort = true,
        runsLeft = 4,
    )

    @Test
    fun each_share_sits_beside_its_aim() {
        val rows = SpendingCheckWords.rows(check)
        assertEquals(listOf("Needs", "Wants", "Saved"), rows.map { it.label })
        assertEquals("$2,050.00", rows[1].amount)
        assertEquals("41%", rows[1].share)
        assertEquals("Aim up to 30%", rows[1].aim)
        assertEquals("Aim at least 20%", rows[2].aim)
        assertEquals(SpendingStatus.OVER, rows[1].status)
        assertEquals(0.41f, rows[1].fraction)
        assertEquals(0.3f, rows[1].aimFraction)
    }

    @Test
    fun a_running_month_has_savings_to_go_and_an_ended_one_is_short() {
        assertEquals(SpendingStatus.TO_GO, SpendingCheckWords.rows(check)[2].status)
        assertEquals("To go", SpendingCheckWords.rows(check)[2].statusLabel)
        assertEquals(SpendingStatus.SHORT, SpendingCheckWords.rows(check.copy(monthEnded = true))[2].status)
        assertEquals(SpendingStatus.ON_TRACK, SpendingCheckWords.rows(check.copy(savingsShort = false))[2].status)
    }

    @Test
    fun asking_is_offered_first_and_again_only_when_the_figures_moved() {
        assertTrue(SpendingCheckWords.canAsk(check))
        assertEquals("Ask AI how to spend", SpendingCheckWords.askLabel(check))
        val advised = check.copy(headline = "Wants are high.")
        assertFalse(SpendingCheckWords.canAsk(advised))
        assertTrue(SpendingCheckWords.canAsk(advised.copy(stale = true)))
        assertFalse(SpendingCheckWords.canAsk(advised.copy(stale = true, runsLeft = 0)))
        assertEquals("Check again", SpendingCheckWords.askLabel(advised))
        assertEquals("No checks left this month. The last one stays.", SpendingCheckWords.runsLeft(advised.copy(runsLeft = 0)))
    }

    @Test
    fun a_tip_belongs_to_its_category() {
        val tipped = check.copy(categories = listOf(CategoryTip("c-dining", "dining", "Plan two meals out a month.")))
        assertEquals("AI: Plan two meals out a month.", SpendingCheckWords.tipFor(tipped, "c-dining"))
        assertNull(SpendingCheckWords.tipFor(tipped, "c-groceries"))
        assertNull(SpendingCheckWords.tipFor(null, "c-dining"))
    }

    @Test
    fun only_a_line_that_is_not_savings_asks_how_long() {
        assertTrue(SpendingCheckWords.asksHowLong(BudgetLine(slug = "dining")))
        assertFalse(SpendingCheckWords.asksHowLong(BudgetLine(slug = "savings")))
        assertFalse(SpendingCheckWords.asksHowLong(null))
        assertEquals("This month only", SpendingCheckWords.thisMonthOnly(BudgetLine(thisMonthOnly = true)))
        assertNull(SpendingCheckWords.thisMonthOnly(BudgetLine()))
    }

    @Test
    fun a_one_off_is_named_only_when_there_is_one() {
        assertEquals("Includes $1,200.00 one-off", SpendingCheckWords.oneOff("1200.00", "CAD"))
        assertNull(SpendingCheckWords.oneOff(null, "CAD"))
        assertNull(SpendingCheckWords.oneOff("0.00", "CAD"))
    }
}
