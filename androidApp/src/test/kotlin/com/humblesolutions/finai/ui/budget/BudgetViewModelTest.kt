package com.humblesolutions.finai.ui.budget

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.BudgetStatus
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Feature
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.model.LearningNeeds
import com.humblesolutions.finai.model.LearningProgress
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.usecase.BudgetBlock
import com.humblesolutions.finai.util.BudgetChanged
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
class BudgetViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ── Fakes ───────────────────────────────────────────────────────────

    private class FakeBudgets(var answer: Budget = ready()) : BudgetRepository {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Triple<String, String, String?>>()
        var failRead: ApiException? = null
        var failWrite: ApiException? = null

        override suspend fun get(month: String): Budget {
            reads += month
            failRead?.let { throw it }
            return answer
        }

        override suspend fun setLine(month: String, categoryId: String, amount: String): Budget {
            writes += Triple(month, categoryId, amount)
            failWrite?.let { throw it }
            return answer
        }

        override suspend fun resetLine(month: String, categoryId: String): Budget {
            writes += Triple(month, categoryId, null)
            failWrite?.let { throw it }
            return answer
        }

        override fun close() = Unit
    }

    private class FakeCategories(var answer: List<Category> = emptyList()) : CategoriesRepository {
        var fail: ApiException? = null
        override suspend fun list(): List<Category> {
            fail?.let { throw it }
            return answer
        }
        override suspend fun create(name: String): Category = throw UnsupportedOperationException()
        override fun close() = Unit
    }

    private class FakeCapabilities(var answer: Capabilities = enabled()) : CapabilitiesRepository {
        var fail: ApiException? = null
        override suspend fun fetch(): Capabilities {
            fail?.let { throw it }
            return answer
        }

        override fun close() = Unit
    }

    private fun model(
        budgets: FakeBudgets = FakeBudgets(),
        categories: FakeCategories = FakeCategories(),
        capabilities: FakeCapabilities = FakeCapabilities(),
    ): BudgetViewModel = BudgetViewModel(SavedStateHandle()).also {
        it.bind("user-1") { BudgetRepositories(budgets, categories, capabilities) }
    }

    // ── Loading ─────────────────────────────────────────────────────────

    @Test
    fun a_bind_reads_the_current_month_and_settles() = runTest {
        val budgets = FakeBudgets()
        val model = model(budgets)

        val state = model.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isReady)
        assertEquals(1, budgets.reads.size)
        assertEquals("CAD", state.currency)
    }

    @Test
    fun a_learning_household_keeps_the_lines_it_set_by_hand() = runTest {
        val model = model(FakeBudgets(answer = learning()))

        val state = model.uiState.value
        assertFalse(state.isReady)
        assertNotNull(state.learning)
        assertEquals(12, state.learning?.transactions)
        // Still shown: manual budgeting is available before the threshold.
        assertEquals(1, state.lines.size)
    }

    @Test
    fun changing_month_reads_that_month() = runTest {
        val budgets = FakeBudgets()
        val model = model(budgets)

        model.showMonth("2026-08")

        assertEquals("2026-08", model.uiState.value.month)
        assertEquals("2026-08", budgets.reads.last())
    }

    @Test
    fun a_failed_first_load_shows_its_own_message() = runTest {
        val budgets = FakeBudgets().apply { failRead = ApiException.Server(500) }
        val model = model(budgets)

        val state = model.uiState.value
        assertTrue(state.loadFailed)
        assertEquals(ApiException.Server(500).messageKey, state.errorKey)
    }

    /** Stale-and-labelled beats a fabricated zero (CLAUDE.md → Data & caching). */
    @Test
    fun a_failed_refresh_keeps_the_figures_it_had() = runTest {
        val budgets = FakeBudgets()
        val model = model(budgets)
        assertTrue(model.uiState.value.isReady)

        budgets.failRead = ApiException.Network(RuntimeException("offline"))
        model.load(refresh = true)

        val state = model.uiState.value
        assertFalse(state.loadFailed)
        assertNotNull(state.budget)
        assertEquals("1400.00", state.budget?.totalAllocated)
    }

    // ── Refusals ────────────────────────────────────────────────────────

    /**
     * The tab is gated by navigation; a 403 that still arrives — the feature
     * turned off since the bar was drawn — says why in the feature's own
     * words rather than "something went wrong".
     */
    @Test
    fun a_403_shows_the_feature_s_own_reason() = runTest {
        val refusal = ApiException.FeatureUnavailable("auto_budget", FeatureReason.NOT_IN_PLAN)
        val model = model(FakeBudgets().apply { failRead = refusal })

        val state = model.uiState.value
        assertTrue(state.loadFailed)
        assertEquals(refusal.messageKey, state.errorKey)
    }

    @Test
    fun capabilities_that_cannot_be_read_do_not_stop_the_budget() = runTest {
        val model = model(capabilities = FakeCapabilities().apply { fail = ApiException.Network(RuntimeException()) })

        assertTrue(model.uiState.value.isReady)
    }

    /**
     * An empty list from a failed categories call is not a household with
     * no categories. Overwriting the ones held would leave "Add a category"
     * offering nothing until the next successful read.
     */
    @Test
    fun a_categories_call_that_fails_keeps_the_categories_already_held() = runTest {
        val categories = FakeCategories(listOf(Category(id = "cat-pets", slug = "pets", name = "Pets")))
        val model = model(categories = categories)
        assertEquals(listOf("cat-pets"), model.uiState.value.pickable.map { it.id })

        categories.fail = ApiException.Network(RuntimeException("offline"))
        model.load(refresh = true)

        assertEquals(listOf("cat-pets"), model.uiState.value.pickable.map { it.id })
    }

    // ── Editing ─────────────────────────────────────────────────────────

    @Test
    fun opening_a_line_fills_the_draft_with_what_is_allocated() = runTest {
        val model = model()

        model.edit("cat-groceries")

        val state = model.uiState.value
        assertEquals("500.00", state.draft.amount)
        assertFalse(state.editingIsNew)
        // Unchanged, so Save is off — but the notice stays quiet until a touch.
        assertEquals(BudgetBlock.NOTHING_CHANGED, state.editBlock)
        assertNull(state.editNotice)
        assertFalse(state.canSave)
    }

    @Test
    fun a_saved_line_sends_the_amount_and_takes_the_budget_that_comes_back() = runTest {
        val budgets = FakeBudgets()
        val model = model(budgets)
        model.edit("cat-groceries")
        model.onAmountChange("650")

        assertTrue(model.uiState.value.canSave)
        budgets.answer = ready(allocated = "650.00")
        model.save()

        assertEquals(Triple(model.uiState.value.month, "cat-groceries", "650"), budgets.writes.single())
        val state = model.uiState.value
        assertNull(state.editing)
        assertFalse(state.saving)
        // Taken from the write's own answer — no second read.
        assertEquals(1, budgets.reads.size)
        assertEquals("650.00", state.lines.first { it.categoryId == "cat-groceries" }.allocated)
    }

    @Test
    fun a_failed_save_keeps_the_sheet_open_with_what_was_typed() = runTest {
        val budgets = FakeBudgets().apply { failWrite = ApiException.Validation(422) }
        val model = model(budgets)
        model.edit("cat-groceries")
        model.onAmountChange("650")

        model.save()

        val state = model.uiState.value
        assertNotNull(state.editing)
        assertEquals("650", state.draft.amount)
        assertFalse(state.saving)
        assertEquals(ApiException.Validation(422).messageKey, state.editErrorKey)
    }

    @Test
    fun a_draft_the_screen_would_refuse_is_never_sent() = runTest {
        val budgets = FakeBudgets()
        val model = model(budgets)
        model.edit("cat-groceries")

        model.onAmountChange("-5")
        model.save()
        model.onAmountChange("1.234")
        model.save()

        assertEquals(emptyList(), budgets.writes)
    }

    @Test
    fun using_the_suggestion_resets_the_line() = runTest {
        val budgets = FakeBudgets()
        val model = model(budgets)
        model.edit("cat-groceries")

        model.useSuggestion()

        assertEquals(Triple(model.uiState.value.month, "cat-groceries", null), budgets.writes.single())
        assertNull(model.uiState.value.editing)
    }

    @Test
    fun the_suggestion_is_offered_only_for_a_line_the_person_overrode() = runTest {
        val model = model()

        model.edit("cat-groceries")
        assertNotNull(model.uiState.value.useSuggestionLabel)

        model.edit("cat-transport")
        assertNull(model.uiState.value.useSuggestionLabel)
    }

    // ── Adding a category ───────────────────────────────────────────────

    @Test
    fun the_picker_offers_neither_income_transfers_nor_a_category_already_budgeted() = runTest {
        val categories = FakeCategories(
            listOf(
                Category(id = "cat-groceries", slug = "groceries", name = "Groceries"),
                Category(id = "cat-income", slug = "income", name = "Income"),
                Category(id = "cat-transfers", slug = "transfers", name = "Transfers"),
                Category(id = "cat-pets", slug = "pets", name = "Pets"),
            ),
        )
        val model = model(categories = categories)

        assertEquals(listOf("cat-pets"), model.uiState.value.pickable.map { it.id })
    }

    @Test
    fun picking_a_category_opens_an_editor_on_a_line_that_does_not_exist_yet() = runTest {
        val categories = FakeCategories(listOf(Category(id = "cat-pets", slug = "pets", name = "Pets")))
        val model = model(categories = categories)

        model.addCategory()
        assertTrue(model.uiState.value.picking)
        model.pickCategory("cat-pets")

        val state = model.uiState.value
        assertFalse(state.picking)
        assertTrue(state.editingIsNew)
        assertEquals("Pets", state.editing?.name)
        // Nothing to go back to, so no suggestion is offered.
        assertNull(state.useSuggestionLabel)
        // And an empty draft asks for an amount rather than claiming nothing changed.
        assertEquals(BudgetBlock.NO_AMOUNT, state.editBlock)
    }

    @Test
    fun a_category_that_cannot_hold_a_line_is_refused_before_the_request() = runTest {
        val categories = FakeCategories(listOf(Category(id = "cat-income", slug = "income", name = "Income")))
        val model = model(categories = categories)

        model.pickCategory("cat-income")

        assertNull(model.uiState.value.editing)
    }

    /**
     * A restored model is always bound straight afterwards, so the test binds
     * it too. The first version of this test did not, and passed while the
     * bind threw the restored draft away.
     */
    @Test
    fun a_half_typed_amount_comes_back_after_the_process_is_reclaimed() = runTest {
        val saved = SavedStateHandle()
        val first = BudgetViewModel(saved).also {
            it.bind("user-1") { BudgetRepositories(FakeBudgets(), FakeCategories(), FakeCapabilities()) }
        }
        first.edit("cat-groceries")
        first.onAmountChange("6")

        // Android reclaims the process; the handle is what comes back, and the
        // route binds the new model as soon as it is composed.
        val budgets = FakeBudgets()
        val second = BudgetViewModel(saved).also {
            it.bind("user-1") { BudgetRepositories(budgets, FakeCategories(), FakeCapabilities()) }
        }

        val state = second.uiState.value
        assertEquals("6", state.draft.amount)
        assertEquals("cat-groceries", state.editing?.categoryId)
        assertEquals(first.uiState.value.month, state.month)
        // The figures are not restored from disk — they are read again.
        assertEquals(1, budgets.reads.size)
        assertTrue(state.isReady)
    }

    /** Someone else signing in on the same phone must not inherit a draft. */
    @Test
    fun a_different_person_does_not_inherit_the_draft() = runTest {
        val saved = SavedStateHandle()
        BudgetViewModel(saved).also {
            it.bind("user-1") { BudgetRepositories(FakeBudgets(), FakeCategories(), FakeCapabilities()) }
            it.edit("cat-groceries")
            it.onAmountChange("6")
        }

        val second = BudgetViewModel(saved).also {
            it.bind("user-2") { BudgetRepositories(FakeBudgets(), FakeCategories(), FakeCapabilities()) }
        }

        assertNull(second.uiState.value.editing)
        assertEquals("", second.uiState.value.draft.amount)
    }

    // ── The signal ──────────────────────────────────────────────────────

    /**
     * Home's budget card (#46) listens for this. Announced only once the
     * server has confirmed, so the card never re-reads to find what it had.
     */
    @Test
    fun a_write_announces_that_an_allocation_moved() = runTest {
        val model = model()
        var announced = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { BudgetChanged.events.collect { announced++ } }
        model.edit("cat-groceries")
        model.onAmountChange("650")

        model.save()

        assertEquals(1, announced)
    }

    @Test
    fun a_failed_write_announces_nothing() = runTest {
        val model = model(FakeBudgets().apply { failWrite = ApiException.Server(500) })
        var announced = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { BudgetChanged.events.collect { announced++ } }
        model.edit("cat-groceries")
        model.onAmountChange("650")

        model.save()

        assertEquals(0, announced)
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private companion object {
        fun enabled() = Capabilities(locale = "en", features = mapOf("auto_budget" to Feature(enabled = true)))

        fun ready(allocated: String = "500.00") = Budget(
            status = BudgetStatus.READY,
            month = "2026-10-01",
            currency = "CAD",
            expectedIncome = "5000.00",
            lines = listOf(
                BudgetLine(
                    categoryId = "cat-groceries",
                    slug = "groceries",
                    name = "Groceries",
                    suggested = "440.00",
                    allocated = allocated,
                    isUserSet = true,
                    spent = "380.00",
                ),
                BudgetLine(
                    categoryId = "cat-transport",
                    slug = "transport",
                    name = "Transport",
                    suggested = "200.00",
                    allocated = "200.00",
                    isUserSet = false,
                    spent = "90.00",
                ),
            ),
            totalAllocated = "1400.00",
            totalSpent = "470.00",
            uncategorisedSpent = "0.00",
            uncategorisedCount = 0,
        )

        fun learning() = Budget(
            status = BudgetStatus.LEARNING,
            month = "2026-10-01",
            currency = "CAD",
            learning = LearningProgress(
                ready = false,
                completeMonths = 0,
                transactions = 12,
                needs = LearningNeeds(completeMonths = 1, transactions = 20),
            ),
            expectedIncome = "5000.00",
            lines = listOf(
                BudgetLine(
                    categoryId = "cat-groceries",
                    slug = "groceries",
                    name = "Groceries",
                    suggested = "0.00",
                    allocated = "300.00",
                    isUserSet = true,
                    spent = "40.00",
                ),
            ),
            totalAllocated = "300.00",
            totalSpent = "40.00",
            uncategorisedSpent = "0.00",
            uncategorisedCount = 0,
        )
    }
}
