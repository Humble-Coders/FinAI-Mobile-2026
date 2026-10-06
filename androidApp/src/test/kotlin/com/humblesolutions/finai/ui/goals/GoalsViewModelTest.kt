package com.humblesolutions.finai.ui.goals

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.model.GoalChanges
import com.humblesolutions.finai.model.GoalHorizon
import com.humblesolutions.finai.model.GoalKind
import com.humblesolutions.finai.model.GoalStatus
import com.humblesolutions.finai.model.GoalsPage
import com.humblesolutions.finai.model.NewGoal
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.GoalsRepository
import com.humblesolutions.finai.usecase.GoalBlock
import com.humblesolutions.finai.util.GoalsChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ── Fakes ───────────────────────────────────────────────────────────

    private class FakeGoals(var page: GoalsPage = page()) : GoalsRepository {
        var lists = 0
        val created = mutableListOf<NewGoal>()
        val updates = mutableListOf<Pair<String, GoalChanges>>()
        val adds = mutableListOf<Pair<String, String>>()
        val orders = mutableListOf<List<String>>()
        val deletes = mutableListOf<String>()
        var disclaimers = 0
        var disclaimerText: Terms? = Terms(version = "ca-v1", body = "Educational guidance, not financial advice.")
        var failList: ApiException? = null
        var failWrite: ApiException? = null

        override suspend fun list(): GoalsPage {
            lists++
            failList?.let { throw it }
            return page
        }

        override suspend fun create(goal: NewGoal): Goal {
            created += goal
            failWrite?.let { throw it }
            return car().copy(id = "g-new", name = goal.name)
        }

        override suspend fun update(id: String, changes: GoalChanges): Goal {
            updates += id to changes
            failWrite?.let { throw it }
            return page.goals.first { it.id == id }.copy(name = changes.name ?: "Car")
        }

        override suspend fun add(id: String, amount: String): Goal {
            adds += id to amount
            failWrite?.let { throw it }
            return page.goals.first { it.id == id }.copy(saved = "1250.00")
        }

        override suspend fun reorder(ids: List<String>): GoalsPage {
            orders += ids
            failWrite?.let { throw it }
            return page.copy(goals = ids.map { id -> page.goals.first { it.id == id } })
        }

        override suspend fun delete(id: String) {
            deletes += id
            failWrite?.let { throw it }
        }

        override suspend fun disclaimer(): Terms? {
            disclaimers++
            return disclaimerText
        }

        override fun close() = Unit
    }

    private class FakeCapabilities(var answer: Capabilities = Capabilities(currency = "CAD", locale = "en")) : CapabilitiesRepository {
        override suspend fun fetch(): Capabilities = answer
        override fun close() = Unit
    }

    private fun model(goals: FakeGoals = FakeGoals(), saved: SavedStateHandle = SavedStateHandle(), user: String = "user-1"): GoalsViewModel = GoalsViewModel(saved).also { it.bind(user) { GoalsRepositories(goals, FakeCapabilities()) } }

    // ── Loading ─────────────────────────────────────────────────────────

    @Test
    fun a_bind_reads_the_goals_in_the_household_s_currency() = runTest {
        val model = model()

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("g-car", "g-home"), state.goals.map { it.id })
        assertEquals("CAD", state.currency)
        assertEquals("$1,200.00 of $6,000.00", state.amountsOf(state.goals.first()))
    }

    /** Stale-and-labelled beats a fabricated zero (CLAUDE.md → Data & caching). */
    @Test
    fun a_failed_refresh_keeps_the_goals_it_had() = runTest {
        val goals = FakeGoals()
        val model = model(goals)

        goals.failList = ApiException.Network(RuntimeException("offline"))
        model.load(refresh = true)

        val state = model.uiState.value
        assertFalse(state.loadFailed)
        assertEquals(2, state.goals.size)
    }

    @Test
    fun a_403_shows_the_feature_s_own_reason() = runTest {
        val refusal = ApiException.FeatureUnavailable("goals", FeatureReason.NOT_IN_PLAN)
        val model = model(FakeGoals().apply { failList = refusal })

        assertTrue(model.uiState.value.loadFailed)
        assertEquals(refusal.messageKey, model.uiState.value.errorKey)
    }

    @Test
    fun the_disclaimer_is_read_only_when_a_long_term_goal_needs_it() = runTest {
        val withLongTerm = FakeGoals()
        model(withLongTerm)
        assertEquals(1, withLongTerm.disclaimers)

        val shortOnly = FakeGoals(page().copy(disclaimerVersion = null))
        model(shortOnly)
        assertEquals(0, shortOnly.disclaimers)
    }

    @Test
    fun a_long_term_goal_shows_the_disclaimer_and_says_it_counts_no_growth() = runTest {
        val model = model()
        val home = model.uiState.value.goals.first { it.id == "g-home" }

        assertEquals("Doesn't include investment growth", model.uiState.value.growthLineOf(home))
        assertEquals("Educational guidance, not financial advice.", model.uiState.value.disclaimerOf(home))
        assertNull(model.uiState.value.disclaimerOf(model.uiState.value.goals.first { it.id == "g-car" }))
    }

    /** A region with no disclaimer is not an error; the line is simply absent. */
    @Test
    fun no_disclaimer_for_the_region_leaves_the_line_out() = runTest {
        val model = model(FakeGoals().apply { disclaimerText = null })
        val home = model.uiState.value.goals.first { it.id == "g-home" }

        assertNull(model.uiState.value.disclaimerOf(home))
        assertNull(model.uiState.value.errorKey)
    }

    // ── Creating and editing ────────────────────────────────────────────

    @Test
    fun a_new_goal_starts_with_no_horizon_and_says_so() = runTest {
        val model = model()
        model.startNew()
        model.onDraftChange(model.uiState.value.draft.copy(name = "Trip", target = "2000"))

        assertEquals(GoalBlock.NO_HORIZON, model.uiState.value.editBlock)
        assertFalse(model.uiState.value.canSave)
    }

    @Test
    fun a_new_goal_is_sent_and_the_list_is_read_again() = runTest {
        val goals = FakeGoals()
        val model = model(goals)
        val readsBefore = goals.lists
        model.startNew()
        model.onDraftChange(model.uiState.value.draft.copy(name = "Trip", horizon = GoalHorizon.SHORT_TERM, target = "2000"))

        model.save()

        assertEquals("2000.00", goals.created.single().target)
        assertFalse(model.uiState.value.editorOpen)
        // Where a new goal goes, and the comparison, are the server's to say.
        assertTrue(goals.lists > readsBefore)
    }

    /** The bug the shared layer guards, end to end: a removed date reaches the server as a removal. */
    @Test
    fun removing_a_date_sends_a_clear() = runTest {
        val goals = FakeGoals()
        val model = model(goals)
        model.edit("g-car")
        model.onDraftChange(model.uiState.value.draft.copy(targetDate = null))

        model.save()

        val (id, changes) = goals.updates.single()
        assertEquals("g-car", id)
        assertTrue(changes.clearTargetDate)
    }

    @Test
    fun a_failed_save_keeps_the_sheet_open_with_what_was_typed() = runTest {
        val goals = FakeGoals().apply { failWrite = ApiException.Validation(422) }
        val model = model(goals)
        model.edit("g-car")
        model.onDraftChange(model.uiState.value.draft.copy(name = "Faster car"))

        model.save()

        val state = model.uiState.value
        assertTrue(state.editorOpen)
        assertEquals("Faster car", state.draft.name)
        assertFalse(state.saving)
        assertEquals(ApiException.Validation(422).messageKey, state.editErrorKey)
    }

    /** Lowering what was saved can re-open a reached goal, which can meet the limit. */
    @Test
    fun the_limit_can_answer_an_edit_and_says_so() = runTest {
        val goals = FakeGoals().apply { failWrite = ApiException.GoalLimitReached(20) }
        val model = model(goals)
        model.edit("g-home")
        model.onDraftChange(model.uiState.value.draft.copy(saved = "100"))

        model.save()

        assertEquals(ApiException.GoalLimitReached(20).messageKey, model.uiState.value.editErrorKey)
        assertTrue(model.uiState.value.editorOpen)
    }

    @Test
    fun at_twenty_open_goals_a_new_one_is_refused_before_anything_is_typed() = runTest {
        val twenty = (1..20).map { car().copy(id = "g$it") }
        val model = model(FakeGoals(page().copy(goals = twenty)))

        assertTrue(model.uiState.value.atLimit)
        model.startNew()
        assertEquals(GoalBlock.TOO_MANY, model.uiState.value.editBlock)
    }

    // ── Adding money ────────────────────────────────────────────────────

    @Test
    fun adding_money_sends_the_normalised_amount_and_closes_the_sheet() = runTest {
        val goals = FakeGoals()
        val model = model(goals)
        model.startAdd("g-car")
        model.onAddAmountChange("50")

        model.add()

        assertEquals("g-car" to "50.00", goals.adds.single())
        assertNull(model.uiState.value.addingTo)
    }

    @Test
    fun a_failed_add_keeps_the_amount_and_the_error() = runTest {
        val goals = FakeGoals().apply { failWrite = ApiException.Server(500) }
        val model = model(goals)
        model.startAdd("g-car")
        model.onAddAmountChange("50")

        model.add()

        assertEquals("50", model.uiState.value.addAmount)
        assertEquals(ApiException.Server(500).messageKey, model.uiState.value.addErrorKey)
    }

    // ── Delete and order ────────────────────────────────────────────────

    @Test
    fun a_goal_is_deleted_only_once_confirmed() = runTest {
        val goals = FakeGoals()
        val model = model(goals)

        model.askDelete("g-car")
        assertEquals(emptyList(), goals.deletes)
        model.delete()

        assertEquals(listOf("g-car"), goals.deletes)
        assertNull(model.uiState.value.confirmingDelete)
    }

    @Test
    fun moving_a_goal_sends_the_new_order_and_shows_the_server_s() = runTest {
        val goals = FakeGoals()
        val model = model(goals)

        model.moveDown(0)

        assertEquals(listOf("g-home", "g-car"), goals.orders.single())
        assertEquals(listOf("g-home", "g-car"), model.uiState.value.goals.map { it.id })
    }

    /** The goals changed on another phone: read them again, and say so. */
    @Test
    fun an_order_out_of_date_reads_the_goals_again() = runTest {
        val goals = FakeGoals().apply { failWrite = ApiException.OrderMismatch() }
        val model = model(goals)
        val readsBefore = goals.lists

        model.moveDown(0)

        // Still said after the fresh read lands — the read must not wipe it.
        assertEquals(ApiException.OrderMismatch().messageKey, model.uiState.value.noticeKey)
        assertTrue(goals.lists > readsBefore)
    }

    // ── Saved state ─────────────────────────────────────────────────────

    /**
     * A restored model is bound straight away, so this binds it too — the
     * test #47 first wrote did not, and passed while the bind threw the
     * draft away.
     */
    @Test
    fun a_half_typed_goal_comes_back_after_the_process_is_reclaimed() = runTest {
        val saved = SavedStateHandle()
        model(saved = saved).also {
            it.startNew()
            it.onDraftChange(it.uiState.value.draft.copy(name = "Trip", kind = GoalKind.VACATION, horizon = GoalHorizon.SHORT_TERM, target = "20"))
        }

        val goals = FakeGoals()
        val restored = model(goals, saved = saved)

        val state = restored.uiState.value
        assertTrue(state.creating)
        assertEquals("Trip", state.draft.name)
        assertEquals(GoalKind.VACATION, state.draft.kind)
        assertEquals(GoalHorizon.SHORT_TERM, state.draft.horizon)
        assertEquals("20", state.draft.target)
        // The goals themselves are read again, not restored.
        assertEquals(1, goals.lists)
    }

    @Test
    fun a_different_person_does_not_inherit_the_draft() = runTest {
        val saved = SavedStateHandle()
        model(saved = saved).also {
            it.startNew()
            it.onDraftChange(it.uiState.value.draft.copy(name = "Trip"))
        }

        val other = model(saved = saved, user = "user-2")

        assertFalse(other.uiState.value.creating)
        assertEquals("", other.uiState.value.draft.name)
    }

    // ── The signal ──────────────────────────────────────────────────────

    @Test
    fun a_confirmed_write_tells_home_and_a_failed_one_does_not() = runTest {
        var announced = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { GoalsChanged.events.collect { announced++ } }

        val failing = model(FakeGoals().apply { failWrite = ApiException.Server(500) })
        failing.startAdd("g-car")
        failing.onAddAmountChange("50")
        failing.add()
        assertEquals(0, announced)

        val working = model()
        working.startAdd("g-car")
        working.onAddAmountChange("50")
        working.add()
        assertTrue(announced >= 1)
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private companion object {
        fun car() = Goal(
            id = "g-car",
            name = "Car",
            kind = GoalKind.CAR,
            horizon = GoalHorizon.SHORT_TERM,
            target = "6000.00",
            saved = "1200.00",
            remaining = "4800.00",
            targetDate = "2099-09-01",
            monthlyContribution = "250.00",
            requiredMonthly = "400.00",
            progressPercent = 20,
            status = GoalStatus.BEHIND,
        )

        fun page() = GoalsPage(
            goals = listOf(
                car(),
                Goal(
                    id = "g-home",
                    name = "House deposit",
                    kind = GoalKind.HOME,
                    horizon = GoalHorizon.LONG_TERM,
                    target = "60000.00",
                    saved = "5000.00",
                    remaining = "55000.00",
                    progressPercent = 8,
                    status = GoalStatus.OPEN,
                    priority = 1,
                ),
            ),
            disclaimerVersion = "ca-v1",
            projectionVersion = "v1",
            assumesGrowth = false,
        )
    }
}
