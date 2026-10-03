package com.humblesolutions.finai.ui.transactions

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.model.StatementImportSummary
import com.humblesolutions.finai.model.StatementImports
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.StatementImportRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.TransactionBrowsing
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
class TransactionsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** What each call was filtered by, which is the thing worth asserting. */
    private data class Ask(val statementId: String?, val month: String?, val cursor: String?)

    private class FakeTransactions(var page: ReviewPage = ReviewPage()) : TransactionsRepository {
        val asks = mutableListOf<Ask>()
        var fail: ApiException? = null

        override suspend fun list(
            statementImportId: String?,
            month: String?,
            needsReview: Boolean?,
            cursor: String?,
        ): ReviewPage {
            asks += Ask(statementImportId, month, cursor)
            fail?.let { throw it }
            return page
        }

        override suspend fun create(entry: NewTransaction): Transaction = error("not called")
        override suspend fun review(cursor: String?): ReviewPage = error("not called")
        override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome = error("not called")
        override suspend fun confirm(id: String): PatchOutcome = error("not called")
        override suspend fun confirmAll(ids: List<String>): ConfirmOutcome = error("not called")
        override suspend fun delete(id: String): DeleteOutcome = error("not called")
        override fun close() = Unit
    }

    private class FakeImports(private val imports: List<StatementImportSummary>) : StatementImportRepository {
        var fail: ApiException? = null

        override suspend fun list(): StatementImports {
            fail?.let { throw it }
            return StatementImports(imports)
        }

        override suspend fun parse(upload: StatementUpload): ParsedStatement = error("not called")
        override suspend fun save(importId: String, rows: RowsToSave): SaveOutcome = error("not called")
        override fun close() = Unit
    }

    private class FakeCategories : CategoriesRepository {
        override suspend fun list(): List<Category> = listOf(Category(id = "c1", name = "Groceries"))
        override suspend fun create(name: String): Category = error("not called")
        override fun close() = Unit
    }

    private fun statement(id: String = "imp-1", saved: Int = 24, needsReview: Int = 0) = StatementImportSummary(
        id = id,
        createdAt = "2026-08-14T09:30:00Z",
        saved = saved,
        needsReview = needsReview,
    )

    private fun model(
        transactions: FakeTransactions = FakeTransactions(),
        imports: FakeImports = FakeImports(listOf(statement())),
    ): TransactionsViewModel {
        val model = TransactionsViewModel()
        model.bind("alice") { TransactionsRepositories(transactions, imports, FakeCategories()) }
        return model
    }

    @Test
    fun it_opens_on_this_month() = runTest {
        // The month is the slice that always has something to show; a
        // statement picker can be empty on a household that has imported none.
        val repo = FakeTransactions()
        val model = model(repo)

        assertEquals(TransactionBrowsing.Mode.BY_MONTH, model.uiState.value.mode)
        assertEquals(DashboardMonths.wire(DashboardMonths.current()), repo.asks.last().month)
        assertNull(repo.asks.last().statementId)
    }

    @Test
    fun switching_to_statements_asks_by_statement_and_not_by_month() = runTest {
        val repo = FakeTransactions()
        val model = model(repo)

        model.showMode(TransactionBrowsing.Mode.BY_STATEMENT)

        assertEquals("imp-1", repo.asks.last().statementId)
        assertNull(repo.asks.last().month, "a statement is not narrowed to a month")
    }

    @Test
    fun switching_back_asks_by_month_and_not_by_statement() = runTest {
        val repo = FakeTransactions()
        val model = model(repo)
        model.showMode(TransactionBrowsing.Mode.BY_STATEMENT)

        model.showMode(TransactionBrowsing.Mode.BY_MONTH)

        assertNull(repo.asks.last().statementId)
        assertEquals(DashboardMonths.wire(DashboardMonths.current()), repo.asks.last().month)
    }

    @Test
    fun picking_another_month_asks_for_it() = runTest {
        val repo = FakeTransactions()
        val model = model(repo)
        val previous = DashboardMonths.previous(DashboardMonths.current())

        model.showMonth(previous)

        assertEquals(DashboardMonths.wire(previous), repo.asks.last().month)
    }

    @Test
    fun picking_the_slice_already_shown_does_not_ask_again() = runTest {
        val repo = FakeTransactions()
        val model = model(repo)
        val before = repo.asks.size

        model.showMonth(DashboardMonths.current())
        model.showMode(TransactionBrowsing.Mode.BY_MONTH)

        assertEquals(before, repo.asks.size)
    }

    @Test
    fun statements_that_saved_nothing_are_not_offered() = runTest {
        val model = model(
            imports = FakeImports(
                listOf(statement(id = "full", saved = 24), statement(id = "empty", saved = 0)),
            ),
        )

        assertEquals(listOf("full"), model.uiState.value.statements.map { it.id })
    }

    @Test
    fun the_pickers_failing_still_leaves_a_browsable_screen() = runTest {
        // The pickers steer the screen; the rows are the screen. Browsing by
        // month always offers the current one.
        val repo = FakeTransactions()
        val model = model(repo, FakeImports(emptyList()).apply { fail = ApiException.Network(RuntimeException("offline")) })

        val state = model.uiState.value
        assertTrue(state.statements.isEmpty())
        assertEquals(listOf(DashboardMonths.current()), state.months)
        assertFalse(state.loadFailed)
    }

    @Test
    fun changing_slice_keeps_the_rows_up_while_the_next_ones_arrive() = runTest {
        val repo = FakeTransactions(ReviewPage(rows = listOf(Transaction(id = "r1"))))
        val model = model(repo)
        assertEquals(listOf("r1"), model.uiState.value.rows.map { it.id })

        repo.fail = ApiException.Network(RuntimeException("offline"))
        model.showMonth(DashboardMonths.previous(DashboardMonths.current()))

        val state = model.uiState.value
        assertEquals(listOf("r1"), state.rows.map { it.id }, "a failed change must not blank the list")
        assertFalse(state.loadFailed)
        assertTrue(state.errorKey != null, "but it still has to say something went wrong")
    }

    @Test
    fun a_first_load_that_fails_says_so() = runTest {
        val repo = FakeTransactions().apply { fail = ApiException.Network(RuntimeException("offline")) }
        val model = model(repo)

        assertTrue(model.uiState.value.loadFailed)
        assertFalse(model.uiState.value.showsEmpty, "an error is not an empty slice")
    }

    @Test
    fun more_pages_follow_the_cursor_and_append() = runTest {
        val repo = FakeTransactions(ReviewPage(rows = listOf(Transaction(id = "r1")), nextCursor = "abc"))
        val model = model(repo)

        repo.page = ReviewPage(rows = listOf(Transaction(id = "r2")), nextCursor = null)
        model.loadMore()

        assertEquals(listOf("r1", "r2"), model.uiState.value.rows.map { it.id })
        assertEquals("abc", repo.asks.last().cursor)
        assertNull(model.uiState.value.nextCursor)
    }

    @Test
    fun a_further_page_keeps_the_slice_it_is_paging_through() = runTest {
        // Dropping the filter here would page from the whole ledger into a
        // list headed by one statement.
        val repo = FakeTransactions(ReviewPage(rows = listOf(Transaction(id = "r1")), nextCursor = "abc"))
        val model = model(repo)
        model.showMode(TransactionBrowsing.Mode.BY_STATEMENT)

        model.loadMore()

        assertEquals("imp-1", repo.asks.last().statementId)
    }

    @Test
    fun a_write_elsewhere_brings_the_list_up_to_date() = runTest {
        val repo = FakeTransactions(ReviewPage(rows = listOf(Transaction(id = "r1"))))
        val model = model(repo)
        val before = repo.asks.size

        repo.page = ReviewPage(rows = listOf(Transaction(id = "r2")))
        LedgerChanged.announce()

        assertEquals(before + 1, repo.asks.size)
        assertEquals(listOf("r2"), model.uiState.value.rows.map { it.id })
    }
}
