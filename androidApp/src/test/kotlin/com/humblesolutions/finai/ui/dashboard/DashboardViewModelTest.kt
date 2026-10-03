package com.humblesolutions.finai.ui.dashboard

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.Flow
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeDashboard(
        var answer: Dashboard = Dashboard(currency = "CAD", net = "100.00"),
    ) : DashboardRepository {
        val asked = mutableListOf<String?>()
        var fail: ApiException? = null
        var gate: CompletableDeferred<Unit>? = null

        /** When set, only this month's request waits on [gate]. */
        var gateFor: String? = null

        /** Per-month answers, so a superseded request can return something recognisable. */
        val answers = mutableMapOf<String?, Dashboard>()
        var closed = false

        override suspend fun read(month: String?): Dashboard {
            asked += month
            if (gateFor == null || gateFor == month) gate?.await()
            fail?.let { throw it }
            return answers[month] ?: answer
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeCapabilities(private val locale: String = "en") : CapabilitiesRepository {
        override suspend fun fetch(): Capabilities = Capabilities(locale = locale, currency = "CAD")
        override fun close() = Unit
    }

    private fun model(
        dashboard: FakeDashboard,
        capabilities: CapabilitiesRepository = FakeCapabilities(),
    ): DashboardViewModel {
        val model = DashboardViewModel()
        model.bind("alice") { DashboardRepositories(dashboard, capabilities) }
        return model
    }

    @Test
    fun binding_reads_the_month_that_is_running() = runTest {
        val repo = FakeDashboard()
        val model = model(repo)

        assertEquals(listOf<String?>(DashboardMonths.wire(DashboardMonths.current())), repo.asked.toList())
        assertFalse(model.uiState.value.loading)
        assertEquals("100.00", model.uiState.value.data.net)
    }

    @Test
    fun binding_again_as_the_same_person_does_not_re_read() = runTest {
        val repo = FakeDashboard()
        val model = model(repo)
        model.bind("alice") { DashboardRepositories(repo, FakeCapabilities()) }

        assertEquals(1, repo.asked.size)
    }

    @Test
    fun stepping_back_asks_for_the_month_before() = runTest {
        val repo = FakeDashboard()
        val model = model(repo)
        val expected = DashboardMonths.wire(DashboardMonths.previous(DashboardMonths.current()))

        model.showPreviousMonth()

        assertEquals(expected, repo.asked.last())
        assertEquals(DashboardMonths.previous(DashboardMonths.current()), model.uiState.value.month)
    }

    @Test
    fun there_is_no_stepping_forward_from_the_month_that_is_running() = runTest {
        val repo = FakeDashboard()
        val model = model(repo)

        model.showNextMonth()

        assertEquals(1, repo.asked.size, "a month that has not happened holds nothing")
    }

    @Test
    fun stepping_forward_works_once_there_is_somewhere_to_go() = runTest {
        val repo = FakeDashboard()
        val model = model(repo)

        model.showPreviousMonth()
        model.showNextMonth()

        assertEquals(DashboardMonths.current(), model.uiState.value.month)
        assertEquals(3, repo.asked.size)
    }

    @Test
    fun changing_month_keeps_the_figures_up_while_the_next_ones_arrive() = runTest {
        // The #32 mistake, not repeated: asserting the state AFTER the load
        // looks identical whether or not the screen blanked in between, so
        // this holds the request open and looks mid-flight.
        val repo = FakeDashboard(Dashboard(currency = "CAD", net = "100.00", income = Flow("900.00")))
        val model = model(repo)
        assertEquals("900.00", model.uiState.value.data.income.actual)

        repo.gate = CompletableDeferred()
        model.showPreviousMonth()

        val during = model.uiState.value
        assertTrue(during.refreshing, "a month change refreshes")
        assertFalse(during.loading, "and must not blank the screen")
        assertEquals("900.00", during.data.income.actual, "the old figures stay up")

        repo.gate?.complete(Unit)
    }

    @Test
    fun a_first_load_that_fails_says_so() = runTest {
        val repo = FakeDashboard().apply { fail = ApiException.Network(RuntimeException("offline")) }
        val model = model(repo)

        val state = model.uiState.value
        assertTrue(state.loadFailed)
        assertFalse(state.loading)
        assertFalse(state.showsEmptyState, "an error is not an empty account")
    }

    @Test
    fun a_refresh_that_fails_keeps_the_month_it_had() = runTest {
        val repo = FakeDashboard(Dashboard(currency = "CAD", net = "100.00", income = Flow("900.00")))
        val model = model(repo)

        repo.fail = ApiException.Network(RuntimeException("offline"))
        model.showPreviousMonth()

        val state = model.uiState.value
        assertEquals("900.00", state.data.income.actual, "a failed refresh must not blank a good month")
        assertFalse(state.loadFailed)
        assertTrue(state.errorKey != null, "but it still has to say something went wrong")
    }

    @Test
    fun an_answer_for_a_month_the_user_has_left_never_lands() = runTest {
        // Asserting the month here would prove nothing: `show` sets it
        // synchronously, so it is right whether or not the stale answer is
        // dropped. What the guard actually protects is the DATA, so that is
        // what this holds open and checks.
        val now = DashboardMonths.current()
        val oneBack = DashboardMonths.wire(DashboardMonths.previous(now))
        val twoBack = DashboardMonths.wire(DashboardMonths.previous(DashboardMonths.previous(now)))

        val repo = FakeDashboard(Dashboard(currency = "CAD", net = "100.00"))
        repo.answers[oneBack] = Dashboard(currency = "CAD", net = "7777.77")
        repo.answers[twoBack] = Dashboard(currency = "CAD", net = "2222.22")
        val model = model(repo)

        // Hold the month the user is about to leave.
        repo.gate = CompletableDeferred()
        repo.gateFor = oneBack
        model.showPreviousMonth()

        // They move on; this one answers immediately.
        model.showPreviousMonth()
        assertEquals("2222.22", model.uiState.value.data.net)

        // Now the abandoned month finally answers.
        repo.gate?.complete(Unit)

        assertEquals(
            "2222.22",
            model.uiState.value.data.net,
            "a superseded answer landed on top of the month being looked at",
        )
    }

    @Test
    fun capabilities_failing_does_not_fail_the_month() = runTest {
        // The locale is a nicety; the figures are the screen.
        val failing = object : CapabilitiesRepository {
            override suspend fun fetch(): Capabilities = throw ApiException.Network(RuntimeException("offline"))
            override fun close() = Unit
        }
        val repo = FakeDashboard()
        val model = model(repo, failing)

        assertFalse(model.uiState.value.loadFailed)
        assertEquals("100.00", model.uiState.value.data.net)
    }

    @Test
    fun a_blank_user_binds_to_nothing() = runTest {
        val repo = FakeDashboard()
        DashboardViewModel().bind("") { DashboardRepositories(repo, FakeCapabilities()) }

        assertTrue(repo.asked.isEmpty())
    }

    // ── Staying current with the ledger ─────────────────────────────────

    @Test
    fun a_write_elsewhere_brings_the_month_up_to_date() = runTest {
        // The whole point: import a statement or type a transaction in, come
        // back, and home shows what just happened rather than what it said
        // before.
        val repo = FakeDashboard(Dashboard(currency = "CAD", net = "100.00"))
        val model = model(repo)
        assertEquals(1, repo.asked.size)

        repo.answer = Dashboard(currency = "CAD", net = "900.00")
        LedgerChanged.announce()

        assertEquals(2, repo.asked.size)
        assertEquals("900.00", model.uiState.value.data.net)
    }

    @Test
    fun coming_up_to_date_does_not_blank_the_screen() = runTest {
        // A refresh, not a load. Flashing an empty dashboard on the way back
        // from an import reads as the import having wiped something.
        val repo = FakeDashboard(Dashboard(currency = "CAD", net = "100.00", income = Flow("900.00")))
        val model = model(repo)

        repo.gate = CompletableDeferred()
        LedgerChanged.announce()

        val during = model.uiState.value
        assertTrue(during.refreshing)
        assertFalse(during.loading)
        assertEquals("900.00", during.data.income.actual, "the figures already there stay up")
        repo.gate?.complete(Unit)
    }

    @Test
    fun the_month_being_looked_at_is_the_one_re_read() = runTest {
        // Not snapped back to today: somebody checking August should not be
        // thrown to October because a write landed.
        val repo = FakeDashboard()
        val model = model(repo)
        model.showPreviousMonth()
        val looking = DashboardMonths.wire(DashboardMonths.previous(DashboardMonths.current()))

        LedgerChanged.announce()

        assertEquals(looking, repo.asked.last())
    }

    @Test
    fun only_one_re_read_happens_per_change() = runTest {
        // A second collector would double every read — invisible on a fast
        // connection and a doubled bill on a slow one. Binding the SAME user
        // cannot reach the guard, because bind early-returns; a different user
        // is the way a second collector would ever be started.
        val repo = FakeDashboard()
        val model = model(repo)
        model.bind("bob") { DashboardRepositories(repo, FakeCapabilities()) }
        val readsBefore = repo.asked.size

        LedgerChanged.announce()

        assertEquals(readsBefore + 1, repo.asked.size, "one read per announcement, whoever is bound")
    }

    // ── The recent list ─────────────────────────────────────────────────

    private class FakeRecent(var rows: List<Transaction> = listOf(Transaction(id = "r1"))) : TransactionsRepository {
        val asked = mutableListOf<Int>()
        var fail: ApiException? = null

        override suspend fun recent(count: Int): List<Transaction> {
            asked += count
            fail?.let { throw it }
            return rows
        }

        override suspend fun list(statementImportId: String?, month: String?, needsReview: Boolean?, cursor: String?): ReviewPage = error("not called")
        override suspend fun create(entry: NewTransaction): Transaction = error("not called")
        override suspend fun review(cursor: String?): ReviewPage = error("not called")
        override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome = error("not called")
        override suspend fun confirm(id: String): PatchOutcome = error("not called")
        override suspend fun confirmAll(ids: List<String>): ConfirmOutcome = error("not called")
        override suspend fun delete(id: String): DeleteOutcome = error("not called")
        override fun close() = Unit
    }

    private class FakeCategories : CategoriesRepository {
        override suspend fun list(): List<Category> = listOf(Category(id = "c1", name = "Groceries"))
        override suspend fun create(name: String): Category = error("not called")
        override fun close() = Unit
    }

    private fun withRecent(dashboard: FakeDashboard, recent: FakeRecent): DashboardViewModel {
        val model = DashboardViewModel()
        model.bind("alice") { DashboardRepositories(dashboard, FakeCapabilities(), recent, FakeCategories()) }
        return model
    }

    @Test
    fun binding_reads_the_newest_rows_as_many_as_the_design_shows() = runTest {
        val recent = FakeRecent()
        val model = withRecent(FakeDashboard(), recent)

        assertEquals(listOf(RECENT_COUNT), recent.asked)
        assertEquals(listOf("r1"), model.uiState.value.recent.map { it.id })
        assertEquals("Groceries", model.uiState.value.categories.single().name)
    }

    @Test
    fun a_recent_list_that_will_not_load_leaves_the_month_standing() = runTest {
        // The figures are the screen; a list that could not load is simply
        // not drawn rather than turning home into an error.
        val recent = FakeRecent().apply { fail = ApiException.Network(RuntimeException("offline")) }
        val model = withRecent(FakeDashboard(), recent)

        val state = model.uiState.value
        assertFalse(state.loadFailed)
        assertNull(state.errorKey)
        assertTrue(state.recent.isEmpty())
    }

    @Test
    fun a_write_elsewhere_brings_the_recent_list_up_to_date() = runTest {
        val recent = FakeRecent()
        val model = withRecent(FakeDashboard(), recent)

        recent.rows = listOf(Transaction(id = "r2"))
        LedgerChanged.announce()

        assertEquals(listOf("r2"), model.uiState.value.recent.map { it.id })
    }

    @Test
    fun stepping_between_months_does_not_re_read_the_recent_list() = runTest {
        // Recent means most recent, whatever month is in view.
        val recent = FakeRecent()
        val model = withRecent(FakeDashboard(), recent)

        model.showPreviousMonth()

        assertEquals(1, recent.asked.size)
        assertEquals(listOf("r1"), model.uiState.value.recent.map { it.id })
    }
}
