package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.model.GoalHorizon
import com.humblesolutions.finai.model.GoalKind
import com.humblesolutions.finai.model.GoalStatus
import com.humblesolutions.finai.model.GoalsBudget
import com.humblesolutions.finai.model.GoalsBudgetReason
import com.humblesolutions.finai.model.GoalsPage
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalEditTest {

    private val today = LocalDate(2026, 10, 6)
    private val words = GoalEdit.Words(currency = "CAD")

    private fun draft(
        name: String = "Car",
        horizon: GoalHorizon? = GoalHorizon.SHORT_TERM,
        target: String = "6000",
        saved: String = "",
        date: String? = null,
        contribution: String = "",
        kind: GoalKind? = GoalKind.CAR,
    ) = GoalDraft(name, kind, horizon, target, saved, date, contribution)

    private val car = Goal(
        id = "g-car",
        name = "Car",
        kind = GoalKind.CAR,
        horizon = GoalHorizon.SHORT_TERM,
        target = "6000.00",
        saved = "1200.00",
        remaining = "4800.00",
        targetDate = "2027-09-01",
        monthlyContribution = "250.00",
        requiredMonthly = "400.00",
        status = GoalStatus.BEHIND,
    )

    // ── A new goal ──────────────────────────────────────────────────────

    @Test
    fun a_complete_new_goal_can_be_saved() {
        assertNull(GoalEdit.blockingReasonForNew(draft(), "CAD", today, openGoals = 0))
    }

    /** Learned before typing five fields, not after. */
    @Test
    fun the_limit_is_the_first_thing_said() {
        assertEquals(GoalBlock.TOO_MANY, GoalEdit.blockingReasonForNew(draft(name = ""), "CAD", today, openGoals = 20))
        assertNull(GoalEdit.blockingReasonForNew(draft(), "CAD", today, openGoals = 19))
    }

    @Test
    fun each_refused_new_goal_names_its_own_reason() {
        fun reason(d: GoalDraft) = GoalEdit.blockingReasonForNew(d, "CAD", today, openGoals = 0)
        assertEquals(GoalBlock.NO_NAME, reason(draft(name = "   ")))
        assertEquals(GoalBlock.NAME_TOO_LONG, reason(draft(name = "x".repeat(256))))
        assertEquals(GoalBlock.NO_TARGET, reason(draft(target = "")))
        assertEquals(GoalBlock.TARGET_NOT_MONEY, reason(draft(target = "six grand")))
        assertEquals(GoalBlock.TARGET_NEGATIVE, reason(draft(target = "-10")))
        assertEquals(GoalBlock.TARGET_TOO_PRECISE, reason(draft(target = "10.999")))
        assertEquals(GoalBlock.TARGET_ZERO, reason(draft(target = "0")))
        assertEquals(GoalBlock.SAVED_NEGATIVE, reason(draft(saved = "-1")))
        assertEquals(GoalBlock.SAVED_TOO_PRECISE, reason(draft(saved = "1.001")))
        assertEquals(GoalBlock.CONTRIBUTION_NEGATIVE, reason(draft(contribution = "-5")))
        assertEquals(GoalBlock.CONTRIBUTION_NOT_MONEY, reason(draft(contribution = "lots")))
        assertEquals(GoalBlock.DATE_IN_PAST, reason(draft(date = "2026-10-05")))
    }

    /** Short or long term decides the disclaimer, so it is never assumed. */
    @Test
    fun a_new_goal_is_not_given_a_horizon_by_default() {
        assertNull(GoalDraft().horizon)
        assertEquals(GoalBlock.NO_HORIZON, GoalEdit.blockingReasonForNew(draft(horizon = null), "CAD", today, 0))
    }

    @Test
    fun today_is_a_valid_target_date_and_the_optional_fields_may_stay_empty() {
        assertNull(GoalEdit.blockingReasonForNew(draft(date = "2026-10-06"), "CAD", today, 0))
        assertNull(GoalEdit.blockingReasonForNew(draft(saved = "", contribution = "", kind = null), "CAD", today, 0))
    }

    @Test
    fun a_new_goal_sends_normalised_amounts_and_leaves_out_what_was_not_given() {
        val goal = assertNotNull(GoalEdit.goalToCreate(draft(name = " Car ", target = "6,000", contribution = "250"), "CAD"))

        assertEquals("Car", goal.name)
        assertEquals("6000.00", goal.target)
        assertNull(goal.saved)
        assertEquals("250.00", goal.monthlyContribution)
    }

    // ── Editing ─────────────────────────────────────────────────────────

    @Test
    fun an_edit_starts_from_the_goal_as_it_is_and_unchanged_cannot_be_saved() {
        val d = GoalEdit.draftOf(car)
        assertEquals(GoalBlock.NOTHING_CHANGED, GoalEdit.blockingReason(car, d, "CAD", today))
        // The same amount written differently is not a change.
        assertEquals(GoalBlock.NOTHING_CHANGED, GoalEdit.blockingReason(car, d.copy(target = "6000"), "CAD", today))
    }

    /** The server cannot clear saved, so an edit must not send it empty. */
    @Test
    fun saved_is_required_when_editing() {
        assertEquals(GoalBlock.NO_SAVED, GoalEdit.blockingReason(car, GoalEdit.draftOf(car).copy(saved = ""), "CAD", today))
    }

    /** An overdue goal can still have its name fixed: only a changed date is judged. */
    @Test
    fun an_untouched_past_date_does_not_block_an_edit() {
        val overdue = car.copy(targetDate = "2026-01-01", status = GoalStatus.OVERDUE)
        val renamed = GoalEdit.draftOf(overdue).copy(name = "Old car")

        assertNull(GoalEdit.blockingReason(overdue, renamed, "CAD", today))
        assertEquals(
            GoalBlock.DATE_IN_PAST,
            GoalEdit.blockingReason(overdue, renamed.copy(targetDate = "2026-02-01"), "CAD", today),
        )
    }

    @Test
    fun an_edit_sends_only_what_changed() {
        val changes = GoalEdit.changes(car, GoalEdit.draftOf(car).copy(name = "New car", target = "7000"), "CAD")

        assertEquals("New car", changes.name)
        assertEquals("7000.00", changes.target)
        assertNull(changes.saved)
        assertNull(changes.horizon)
        assertNull(changes.targetDate)
        assertFalse(changes.clearTargetDate)
        assertFalse(changes.clearMonthlyContribution)
    }

    /**
     * Removing a date, a monthly amount or a kind must reach the server as a
     * removal. Left as plain nulls they would be dropped on the way out and the
     * server would read them as "unchanged".
     */
    @Test
    fun clearing_an_optional_field_is_a_clear_not_a_no_change() {
        val cleared = GoalEdit.draftOf(car).copy(targetDate = null, monthlyContribution = "", kind = null)

        val changes = GoalEdit.changes(car, cleared, "CAD")

        assertTrue(changes.clearTargetDate)
        assertTrue(changes.clearMonthlyContribution)
        assertTrue(changes.clearKind)
        assertFalse(changes.isEmpty)
        assertNull(GoalEdit.blockingReason(car, cleared, "CAD", today))
    }

    @Test
    fun setting_a_field_that_was_empty_is_a_change() {
        val bare = car.copy(targetDate = null, monthlyContribution = null)
        val changes = GoalEdit.changes(bare, GoalEdit.draftOf(bare).copy(monthlyContribution = "300"), "CAD")

        assertEquals("300.00", changes.monthlyContribution)
        assertFalse(changes.clearMonthlyContribution)
    }

    /** The limit is said from the constant, never typed into the copy where it would go stale. */
    @Test
    fun each_reason_is_worded_with_the_figure_its_sentence_needs() {
        assertTrue(GoalEdit.blockText(GoalBlock.TOO_MANY, "CAD").contains(GoalEdit.OPEN_LIMIT.toString()))
        assertEquals("CAD doesn't use that many decimal places.", GoalEdit.blockText(GoalBlock.TARGET_TOO_PRECISE, "CAD"))
        assertEquals("Give the goal a name.", GoalEdit.blockText(GoalBlock.NO_NAME, "CAD"))
        assertEquals("JPY doesn't use that many decimal places.", GoalEdit.addBlockText(AddMoneyBlock.TOO_PRECISE, "JPY"))
        assertFalse(GoalEdit.blockText(GoalBlock.TOO_MANY, "CAD").contains("{0}"))
    }

    // ── Adding money ────────────────────────────────────────────────────

    @Test
    fun each_refused_amount_to_add_names_its_own_reason() {
        assertEquals(AddMoneyBlock.NO_AMOUNT, GoalEdit.blockingReasonForAdd("", "CAD"))
        assertEquals(AddMoneyBlock.NOT_MONEY, GoalEdit.blockingReasonForAdd("some", "CAD"))
        assertEquals(AddMoneyBlock.NEGATIVE, GoalEdit.blockingReasonForAdd("-20", "CAD"))
        assertEquals(AddMoneyBlock.TOO_PRECISE, GoalEdit.blockingReasonForAdd("20.001", "CAD"))
        assertEquals(AddMoneyBlock.ZERO, GoalEdit.blockingReasonForAdd("0", "CAD"))
        assertNull(GoalEdit.blockingReasonForAdd("20", "CAD"))
        assertEquals("20.00", GoalEdit.addAmount("20", "CAD"))
    }

    // ── Order ───────────────────────────────────────────────────────────

    @Test
    fun moving_a_goal_shifts_the_others_and_out_of_range_changes_nothing() {
        val ids = listOf("a", "b", "c", "d")

        assertEquals(listOf("c", "a", "b", "d"), GoalEdit.moved(ids, from = 2, to = 0))
        assertEquals(listOf("a", "c", "b", "d"), GoalEdit.moved(ids, from = 1, to = 2))
        assertEquals(ids, GoalEdit.moved(ids, from = 0, to = 9))
    }

    // ── Words ───────────────────────────────────────────────────────────

    /** Both figures are the server's; the gap between them is never shown. */
    @Test
    fun on_track_and_behind_show_both_server_figures_and_no_difference() {
        assertEquals("Behind · needs $400.00 a month, you plan $250.00", GoalEdit.statusLine(car, words))
        assertEquals(
            "On track · needs $400.00 a month, you plan $450.00",
            GoalEdit.statusLine(car.copy(status = GoalStatus.ON_TRACK, monthlyContribution = "450.00"), words),
        )
    }

    @Test
    fun an_open_goal_says_what_it_can_and_asks_for_the_rest() {
        val dateOnly = car.copy(status = GoalStatus.OPEN, monthlyContribution = null)
        val amountOnly = car.copy(status = GoalStatus.OPEN, targetDate = null, requiredMonthly = null, projectedCompletion = "2028-02")
        val neither = car.copy(status = GoalStatus.OPEN, targetDate = null, requiredMonthly = null, monthlyContribution = null)

        assertEquals("Needs $400.00 a month until Sep 2027", GoalEdit.statusLine(dateOnly, words))
        assertEquals("Done by Feb 2028 at $250.00 a month", GoalEdit.statusLine(amountOnly, words))
        assertEquals("Add a date or a monthly amount to see a plan", GoalEdit.statusLine(neither, words))
    }

    @Test
    fun overdue_and_reached_are_said_in_words() {
        val overdue = car.copy(status = GoalStatus.OVERDUE, targetDate = "2026-09-01", requiredMonthly = null)

        assertTrue(GoalEdit.statusLine(overdue, words).startsWith("Target date "))
        assertTrue(GoalEdit.statusLine(overdue, words).endsWith("· $4,800.00 to go"))
        assertEquals("Goal reached", GoalEdit.statusLine(car.copy(status = GoalStatus.ACHIEVED), words))
        assertEquals("", GoalEdit.statusLine(car.copy(status = GoalStatus.UNKNOWN), words))
    }

    @Test
    fun a_screen_reader_hears_the_goal_once_with_its_standing() {
        assertEquals(
            "Car, saved $1,200.00 of $6,000.00, Behind · needs $400.00 a month, you plan $250.00",
            GoalEdit.description(car, words),
        )
    }

    /** Home's eye toggle hides every figure, goals included. */
    @Test
    fun hidden_amounts_mask_every_figure() {
        val hidden = GoalEdit.Words(currency = "CAD", amountsHidden = true)

        assertFalse(GoalEdit.amountsLine(car, hidden).contains("$"))
        assertFalse(GoalEdit.statusLine(car, hidden).contains("$"))
    }

    @Test
    fun only_long_term_goals_still_being_saved_for_say_they_count_no_growth() {
        val page = GoalsPage(goals = listOf(car), assumesGrowth = false)
        val longTerm = car.copy(horizon = GoalHorizon.LONG_TERM)

        assertTrue(GoalEdit.saysNoGrowth(longTerm, page))
        assertFalse(GoalEdit.saysNoGrowth(car, page))
        assertFalse(GoalEdit.saysNoGrowth(longTerm.copy(status = GoalStatus.ACHIEVED), page))
        assertFalse(GoalEdit.saysNoGrowth(longTerm, page.copy(assumesGrowth = true)))
    }

    @Test
    fun the_comparison_says_covered_short_or_why_it_is_missing() {
        val covered = GoalsPage(goals = listOf(car), budget = GoalsBudget(need = "400.00", setAside = "900.00"))
        val short = covered.copy(budget = GoalsBudget(need = "400.00", setAside = "300.00", shortfall = "100.00"))

        assertEquals("Your goals need $400.00 a month · your budget sets aside $900.00", GoalEdit.comparisonLine(covered, words))
        assertEquals("Your goals need $400.00 a month — $100.00 more than your budget sets aside", GoalEdit.comparisonLine(short, words))
        assertNotNull(GoalEdit.comparisonLine(GoalsPage(goals = listOf(car), budgetReason = GoalsBudgetReason.LEARNING), words))
        // Budgets switched off: nothing to say, rather than an explanation of a feature they don't have.
        assertNull(GoalEdit.comparisonLine(GoalsPage(goals = listOf(car), budgetReason = GoalsBudgetReason.UNAVAILABLE), words))
        // No open goals: nothing to compare.
        assertNull(GoalEdit.comparisonLine(covered.copy(goals = listOf(car.copy(status = GoalStatus.ACHIEVED))), words))
    }

    @Test
    fun home_shows_the_first_three_goals_still_being_saved_for() {
        val goals = (1..5).map { car.copy(id = "g$it", status = if (it == 1) GoalStatus.ACHIEVED else GoalStatus.OPEN) }

        assertEquals(listOf("g2", "g3", "g4"), GoalEdit.forHome(GoalsPage(goals = goals)).map { it.id })
    }

    @Test
    fun home_s_card_words_each_goal_the_way_the_goals_tab_does() {
        val card = GoalEdit.homeCard(GoalsPage(goals = listOf(car)), words)
        val row = card.rows.single()

        assertEquals("Car", row.name)
        assertEquals(GoalEdit.amountsLine(car, words), row.amounts)
        assertEquals(GoalEdit.statusLine(car, words), row.status)
        assertEquals(GoalEdit.description(car, words), row.accessibility)
        assertEquals(CategoryIcon.CAR, row.icon)
    }

    /** No goals at all is an invitation, not a missing card. */
    @Test
    fun home_s_card_with_no_goals_is_empty_rather_than_absent() {
        assertTrue(GoalEdit.homeCard(GoalsPage(), words).isEmpty)
    }

    /** A card of nothing would read as no goals at all. */
    @Test
    fun home_shows_reached_goals_when_nothing_else_is_left() {
        val reached = listOf(car.copy(id = "g1", status = GoalStatus.ACHIEVED))

        assertEquals(listOf("g1"), GoalEdit.forHome(GoalsPage(goals = reached)).map { it.id })
    }
}
