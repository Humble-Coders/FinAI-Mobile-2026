package com.humblesolutions.finai.model

import com.humblesolutions.finai.data.FinAiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding what `GET /goals` returns, copied from backend #65's `GoalsOut`
 * (`app/schemas/goals.py`). A field renamed there shows up here as a decode
 * failure rather than as a zero on somebody's phone.
 */
class GoalTest {

    private val page = """
        {
          "goals": [
            {"id": "g-car", "name": "Car", "kind": "car", "horizon": "short_term",
             "target": "6000.00", "saved": "1200.00", "remaining": "4800.00",
             "target_date": "2027-09-01", "monthly_contribution": "250.00",
             "required_monthly": "400.00", "projected_completion": "2028-02",
             "progress_percent": 20, "status": "behind", "achieved_at": null, "priority": 0},
            {"id": "g-home", "name": "House deposit", "kind": "home", "horizon": "long_term",
             "target": "60000.00", "saved": "60000.00", "remaining": "0.00",
             "target_date": null, "monthly_contribution": null,
             "required_monthly": null, "projected_completion": null,
             "progress_percent": 100, "status": "achieved", "achieved_at": "2026-09-30", "priority": 1}
          ],
          "budget": {"need": "400.00", "set_aside": "900.00", "shortfall": null},
          "budget_reason": null,
          "disclaimer_version": "ca-v1",
          "projection_version": "v1",
          "assumes_growth": false
        }
    """.trimIndent()

    @Test
    fun the_server_s_page_decodes_field_for_field() {
        val decoded = FinAiJson.decodeFromString(GoalsPage.serializer(), page)

        val car = decoded.goals.first()
        assertEquals("Car", car.name)
        assertEquals(GoalKind.CAR, car.kind)
        assertEquals(GoalHorizon.SHORT_TERM, car.horizon)
        assertEquals("1200.00", car.saved)
        assertEquals("2027-09-01", car.targetDate)
        assertEquals("250.00", car.monthlyContribution)
        assertEquals("400.00", car.requiredMonthly)
        assertEquals("2028-02", car.projectedCompletion)
        assertEquals(GoalStatus.BEHIND, car.status)
        assertEquals(0.2f, car.fraction)

        assertEquals("400.00", decoded.budget?.need)
        assertEquals("900.00", decoded.budget?.setAside)
        assertNull(decoded.budget?.shortfall)
        assertEquals("ca-v1", decoded.disclaimerVersion)
        assertEquals("v1", decoded.projectionVersion)
        assertFalse(decoded.assumesGrowth)
    }

    /** The server's limit counts goals still being saved for, not reached ones. */
    @Test
    fun only_goals_not_yet_reached_count_toward_the_limit() {
        val decoded = FinAiJson.decodeFromString(GoalsPage.serializer(), page)

        assertEquals(1, decoded.openCount)
        assertTrue(decoded.goals.last().isAchieved)
        assertTrue(decoded.goals.last().isLongTerm)
    }

    @Test
    fun no_comparison_arrives_with_its_reason() {
        val decoded = FinAiJson.decodeFromString(
            GoalsPage.serializer(),
            """{"goals": [], "budget": null, "budget_reason": "learning", "projection_version": "v1", "assumes_growth": false}""",
        )

        assertNull(decoded.budget)
        assertEquals(GoalsBudgetReason.LEARNING, decoded.budgetReason)
    }

    /** A status, kind or horizon this build does not know must not break the screen. */
    @Test
    fun values_this_build_does_not_know_decode_as_unknown() {
        val goal = FinAiJson.decodeFromString(
            Goal.serializer(),
            """{"id": "g", "kind": "yacht", "horizon": "medium_term", "status": "paused"}""",
        )

        assertEquals(GoalKind.UNKNOWN, goal.kind)
        assertEquals(GoalHorizon.UNKNOWN, goal.horizon)
        assertEquals(GoalStatus.UNKNOWN, goal.status)
    }

    @Test
    fun a_page_missing_everything_optional_still_decodes() {
        val decoded = FinAiJson.decodeFromString(GoalsPage.serializer(), "{}")

        assertEquals(emptyList(), decoded.goals)
        assertFalse(decoded.assumesGrowth)
    }

    /** The bar is the server's percentage, held between empty and full. */
    @Test
    fun the_bar_comes_from_the_server_s_percentage() {
        assertEquals(0f, Goal(progressPercent = 0).fraction)
        assertEquals(0.37f, Goal(progressPercent = 37).fraction)
        assertEquals(1f, Goal(progressPercent = 140).fraction)
    }
}
