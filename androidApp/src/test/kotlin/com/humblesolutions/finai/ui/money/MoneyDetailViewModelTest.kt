package com.humblesolutions.finai.ui.money

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Flow
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.Obligation
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CommitmentBlock
import com.humblesolutions.finai.usecase.CommitmentDraft
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.MoneyKind
import com.humblesolutions.finai.util.LedgerChanged
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
class MoneyDetailViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val month = DashboardMonths.current()
    private val wire = DashboardMonths.wire(month)

    private class FakeDashboard(var answer: Dashboard = Dashboard(currency = "CAD")) : DashboardRepository {
        val asked = mutableListOf<String?>()
        override suspend fun read(month: String?): Dashboard {
            asked += month
            return answer
        }
        override fun close() = Unit
    }

    private data class Browse(val month: String?, val direction: TransactionDirection?, val slug: String?, val cursor: String?)

    private class FakeTransactions(var page: ReviewPage = ReviewPage()) : TransactionsRepository {
        val asked = mutableListOf<Browse>()
        var fail: ApiException? = null
        override suspend fun browse(month: String?, direction: TransactionDirection?, categorySlug: String?, cursor: String?): ReviewPage {
            asked += Browse(month, direction, categorySlug, cursor)
            fail?.let { throw it }
            return page
        }
        override suspend fun create(entry: NewTransaction): Transaction = error("not called")
        override suspend fun review(cursor: String?): ReviewPage = error("not called")
        override suspend fun list(statementImportId: String?, month: String?, needsReview: Boolean?, cursor: String?): ReviewPage = error("browse, not list")
        override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome = error("not called")
        override suspend fun confirm(id: String): PatchOutcome = error("not called")
        override suspend fun confirmAll(ids: List<String>): ConfirmOutcome = error("not called")
        override suspend fun delete(id: String): DeleteOutcome = error("not called")
        override fun close() = Unit
    }

    private class FakeCategories(private val categories: List<Category> = emptyList()) : CategoriesRepository {
        override suspend fun list(): List<Category> = categories
        override suspend fun create(name: String): Category = error("not called")
        override fun close() = Unit
    }

    private class FakeSetup(var setup: FinancialSetup = FinancialSetup(currency = "CAD")) : FinancialSetupRepository {
        val saved = mutableListOf<FinancialSetup>()
        var fail: ApiException? = null
        override suspend fun get(): FinancialSetup = setup
        override suspend fun save(setup: FinancialSetup): FinancialSetup {
            fail?.let { throw it }
            saved += setup
            this.setup = setup
            return setup
        }
        override fun close() = Unit
    }

    private class FakeCapabilities : CapabilitiesRepository {
        override suspend fun fetch(): Capabilities = Capabilities(locale = "en", currency = "CAD")
        override fun close() = Unit
    }

    private class Wired(
        val model: MoneyDetailViewModel,
        val dashboard: FakeDashboard,
        val transactions: FakeTransactions,
        val setup: FakeSetup,
    )

    private fun wired(
        kind: MoneyKind,
        transactions: FakeTransactions = FakeTransactions(),
        categories: List<Category> = emptyList(),
        setup: FakeSetup = FakeSetup(),
        dashboard: FakeDashboard = FakeDashboard(),
    ): Wired {
        val model = MoneyDetailViewModel()
        model.bind("alice", kind) {
            MoneyDetailRepositories(dashboard, transactions, FakeCategories(categories), setup, FakeCapabilities())
        }
        return Wired(model, dashboard, transactions, setup)
    }

    @Test
    fun each_screen_asks_for_its_own_slice_of_the_month() = runTest {
        assertEquals(Browse(wire, TransactionDirection.CREDIT, null, null), wired(MoneyKind.INCOME).transactions.asked.single())
        assertEquals(Browse(wire, TransactionDirection.DEBIT, null, null), wired(MoneyKind.EXPENSES).transactions.asked.single())
        assertEquals(Browse(wire, null, "savings", null), wired(MoneyKind.INVESTMENTS).transactions.asked.single())
        assertEquals(Browse(wire, null, "debt_payment", null), wired(MoneyKind.DEBTS).transactions.asked.single())
    }

    @Test
    fun the_figures_come_from_the_month_on_the_dashboard() = runTest {
        val dashboard = FakeDashboard(Dashboard(currency = "CAD", income = Flow(actual = "5000.00", previous = "4000.00")))
        val state = wired(MoneyKind.INCOME, dashboard = dashboard).model.uiState.value

        assertEquals(listOf<String?>(wire), dashboard.asked)
        assertEquals("$5,000.00", state.headline)
        assertEquals("+25%", state.changeLabel)
        assertTrue(state.changeIsGood, "more income is good news")
    }

    @Test
    fun more_spending_is_not_good_news_and_the_words_say_which_way() = runTest {
        val dashboard = FakeDashboard(Dashboard(currency = "CAD", expenses = Flow(actual = "1100.00", previous = "1000.00")))
        val state = wired(MoneyKind.EXPENSES, dashboard = dashboard).model.uiState.value

        assertFalse(state.changeIsGood)
        assertEquals("Up 10% from last month", state.changeDescription)
    }

    @Test
    fun an_older_server_that_sends_everything_still_shows_only_this_screens_rows() = runTest {
        val rows = listOf(
            Transaction(id = "pay", occurredOn = "$wire-02", direction = TransactionDirection.CREDIT, merchant = "Payroll"),
            Transaction(id = "coffee", occurredOn = "$wire-03", direction = TransactionDirection.DEBIT, merchant = "Coffee"),
        )
        val state = wired(MoneyKind.INCOME, FakeTransactions(ReviewPage(rows = rows))).model.uiState.value

        assertEquals(listOf("pay"), state.visibleRows.map { it.id })
    }

    @Test
    fun searching_narrows_the_list_by_title() = runTest {
        val rows = listOf(
            Transaction(id = "a", occurredOn = "$wire-02", direction = TransactionDirection.DEBIT, merchant = "Loblaws"),
            Transaction(id = "b", occurredOn = "$wire-03", direction = TransactionDirection.DEBIT, merchant = "Uber"),
        )
        val wired = wired(MoneyKind.EXPENSES, FakeTransactions(ReviewPage(rows = rows)))

        wired.model.onQuery("lob")

        assertEquals(listOf("a"), wired.model.uiState.value.visibleRows.map { it.id })
    }

    @Test
    fun choosing_another_month_reads_that_month() = runTest {
        val wired = wired(MoneyKind.EXPENSES)
        val earlier = DashboardMonths.previous(month)

        wired.model.showMonth(earlier)

        assertEquals(DashboardMonths.wire(earlier), wired.dashboard.asked.last())
        assertEquals(DashboardMonths.wire(earlier), wired.transactions.asked.last().month)
    }

    @Test
    fun a_change_to_the_ledger_anywhere_re_reads_the_month() = runTest {
        val wired = wired(MoneyKind.EXPENSES)
        val before = wired.dashboard.asked.size

        LedgerChanged.announce()

        assertTrue(wired.dashboard.asked.size > before)
    }

    @Test
    fun a_first_load_that_fails_says_so() = runTest {
        val transactions = FakeTransactions().apply { fail = ApiException.Network(RuntimeException("offline")) }
        val state = wired(MoneyKind.EXPENSES, transactions).model.uiState.value

        assertTrue(state.loadFailed)
        assertEquals(Strings.error_network, state.errorKey)
    }

    @Test
    fun an_obligation_is_added_to_the_whole_list_with_its_due_day() = runTest {
        val setup = FakeSetup(FinancialSetup(currency = "CAD", obligations = listOf(Obligation("Phone", "65.00"))))
        val wired = wired(MoneyKind.EXPENSES, setup = setup)

        wired.model.openObligation()
        wired.model.onObligationChange(CommitmentDraft(name = "Rent", amount = "1800", dueDay = "5"))
        wired.model.saveObligation()

        val written = setup.saved.single().obligations
        assertEquals(listOf("Phone", "Rent"), written.map { it.name }, "the existing one is kept")
        assertEquals(5, written.last().dueDay)
        assertNull(wired.model.uiState.value.obligationDraft, "the sheet closes")
    }

    @Test
    fun a_due_day_no_month_has_is_refused_before_sending() = runTest {
        val setup = FakeSetup()
        val wired = wired(MoneyKind.EXPENSES, setup = setup)

        wired.model.openObligation()
        wired.model.onObligationChange(CommitmentDraft(name = "Rent", amount = "1800", dueDay = "40"))
        wired.model.saveObligation()

        assertTrue(setup.saved.isEmpty())
        assertEquals(CommitmentBlock.DUE_DAY_INVALID, wired.model.uiState.value.obligationNotice)
    }

    @Test
    fun marking_a_saved_entry_as_an_obligation_says_whether_it_worked() = runTest {
        val setup = FakeSetup()
        val wired = wired(MoneyKind.EXPENSES, setup = setup)

        wired.model.markAsObligation("Gym", "45.00", dueDay = 12)

        assertEquals(12, setup.saved.single().obligations.single().dueDay)
        assertEquals(Strings.money_obligation_added, wired.model.uiState.value.noticeKey)
    }

    @Test
    fun a_failed_obligation_after_a_saved_entry_does_not_pretend_the_entry_failed() = runTest {
        val setup = FakeSetup().apply { fail = ApiException.Network(RuntimeException("offline")) }
        val wired = wired(MoneyKind.EXPENSES, setup = setup)

        wired.model.markAsObligation("Gym", "45.00", dueDay = null)

        assertEquals(Strings.money_obligation_failed, wired.model.uiState.value.noticeKey)
    }
}
