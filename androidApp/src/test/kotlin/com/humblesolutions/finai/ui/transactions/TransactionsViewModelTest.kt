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
import com.humblesolutions.finai.model.TransactionDirection
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
import kotlin.test.assertNotNull
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

        /** Pages by the cursor asked with, for paging tests; null uses [page]. */
        var pagesByCursor: Map<String?, ReviewPage>? = null

        override suspend fun list(
            statementImportId: String?,
            month: String?,
            needsReview: Boolean?,
            cursor: String?,
        ): ReviewPage {
            asks += Ask(statementImportId, month, cursor)
            fail?.let { throw it }
            return pagesByCursor?.get(cursor) ?: page
        }

        override suspend fun create(entry: NewTransaction): Transaction = error("not called")
        override suspend fun review(cursor: String?): ReviewPage = error("not called")
        val patches = mutableListOf<Pair<String, TransactionPatch>>()
        var correctFails: ApiException? = null

        override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome {
            correctFails?.let { throw it }
            patches += id to patch
            return PatchOutcome()
        }
        override suspend fun confirm(id: String): PatchOutcome = error("not called")
        override suspend fun confirmAll(ids: List<String>): ConfirmOutcome = error("not called")
        val deleted = mutableListOf<String>()
        var deleteFails: ApiException? = null

        override suspend fun delete(id: String): DeleteOutcome {
            deleteFails?.let { throw it }
            deleted += id
            page = page.copy(rows = page.rows.filterNot { it.id == id })
            return DeleteOutcome()
        }
        override fun close() = Unit
    }

    private class FakeImports(var imports: List<StatementImportSummary>) : StatementImportRepository {
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
        override suspend fun create(name: String): Category = Category(id = "mine", name = name)
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

        // The slice is read again — and the months too, which is another
        // request, so this counts the slice's read rather than all of them.
        assertTrue(repo.asks.drop(before).any { it.cursor == null && it.month != null }, "the month was read again")
        assertEquals(listOf("r2"), model.uiState.value.rows.map { it.id })
    }

    // ── Editing any row ─────────────────────────────────────────────────

    private val loblaws = Transaction(
        id = "r1",
        occurredOn = "2026-10-02",
        amount = "86.40",
        currency = "CAD",
        direction = TransactionDirection.DEBIT,
        description = "LOBLAWS 1234",
        categoryId = "c1",
    )

    private fun editable(repo: FakeTransactions = FakeTransactions(ReviewPage(rows = listOf(loblaws)))) = model(repo) to repo

    @Test
    fun a_row_opens_in_the_editor_as_it_stands() = runTest {
        val (model, _) = editable()

        model.edit("r1")

        val editor = assertNotNull(model.uiState.value.editor)
        assertEquals("r1", editor.row.id)
        assertEquals("86.40", editor.draft.amount)
        assertFalse(editor.canSave, "nothing has changed yet")
        assertNull(editor.notice, "and that is not worth saying before a touch")
    }

    @Test
    fun saving_sends_only_what_changed_and_closes_the_editor() = runTest {
        val (model, repo) = editable()
        model.edit("r1")

        model.onAmountChange("90.00")
        model.saveEdit()

        val (id, patch) = repo.patches.single()
        assertEquals("r1", id)
        assertEquals("90.00", patch.amount)
        assertNull(patch.description, "an untouched field is not re-sent")
        assertNull(model.uiState.value.editor)
    }

    @Test
    fun a_saved_edit_re_reads_the_list_and_tells_home() = runTest {
        // Announced, so home re-reads its month; this list hears it too and
        // re-reads its slice — which also drops a row whose new date took it
        // out of the month being looked at.
        val (model, repo) = editable()
        model.edit("r1")
        val before = repo.asks.size

        model.onAmountChange("90.00")
        model.saveEdit()

        // The slice is read again — and the months too, which is another
        // request, so this counts the slice's read rather than all of them.
        assertTrue(repo.asks.drop(before).any { it.cursor == null && it.month != null }, "the month was read again")
    }

    @Test
    fun an_edit_that_would_duplicate_stays_open_and_says_so() = runTest {
        val (model, repo) = editable()
        repo.correctFails = ApiException.WouldDuplicate("t-9")
        model.edit("r1")

        model.onAmountChange("90.00")
        model.saveEdit()

        val editor = assertNotNull(model.uiState.value.editor, "the draft is what they need to fix")
        assertEquals(ApiException.WouldDuplicate("t-9").messageKey, editor.errorKey)
        assertFalse(editor.saving)
    }

    @Test
    fun cancelling_closes_without_sending() = runTest {
        val (model, repo) = editable()
        model.edit("r1")
        model.onAmountChange("90.00")

        model.cancelEdit()

        assertNull(model.uiState.value.editor)
        assertTrue(repo.patches.isEmpty())
    }

    @Test
    fun a_new_category_is_made_and_the_row_filed_into_it() = runTest {
        val (model, _) = editable()
        model.edit("r1")
        model.openNewCategory()

        model.onNewCategoryName("Pets")
        model.createCategory()

        val editor = assertNotNull(model.uiState.value.editor)
        assertEquals("mine", editor.draft.categoryId)
        assertNull(editor.newCategoryName)
    }

    // ── By category ─────────────────────────────────────────────────────

    @Test
    fun by_category_reads_every_page_before_grouping() = runTest {
        // A category's total over half the ledger is a wrong total.
        val repo = FakeTransactions()
        val model = model(repo)
        repo.pagesByCursor = mapOf(
            null to ReviewPage(rows = listOf(Transaction(id = "a")), nextCursor = "c1"),
            "c1" to ReviewPage(rows = listOf(Transaction(id = "b")), nextCursor = "c2"),
            "c2" to ReviewPage(rows = listOf(Transaction(id = "c")), nextCursor = null),
        )

        model.showMode(TransactionBrowsing.Mode.BY_CATEGORY)

        assertEquals(listOf("a", "b", "c"), model.uiState.value.rows.map { it.id })
        assertNull(model.uiState.value.nextCursor)
        assertNull(repo.asks.last { it.cursor == null }.month, "by category asks for every month")
    }

    @Test
    fun by_category_stops_at_its_cap_and_offers_more() = runTest {
        val repo = FakeTransactions()
        val model = model(repo)
        // Every page points at another: the cap is what stops it.
        repo.page = ReviewPage(rows = listOf(Transaction(id = "x")), nextCursor = "again")

        model.showMode(TransactionBrowsing.Mode.BY_CATEGORY)

        assertEquals(CATEGORY_PAGES, model.uiState.value.rows.size)
        assertTrue(model.uiState.value.canLoadMore)
    }

    @Test
    fun a_month_shows_only_its_own_rows_even_when_the_server_sends_more() = runTest {
        // A server without the month filter returns every month.
        val now = DashboardMonths.current()
        val elsewhere = DashboardMonths.previous(now)
        val repo = FakeTransactions(
            ReviewPage(
                rows = listOf(
                    Transaction(id = "here", occurredOn = DashboardMonths.wire(now) + "-01"),
                    Transaction(id = "there", occurredOn = DashboardMonths.wire(elsewhere) + "-01"),
                ),
            ),
        )
        val model = model(repo)

        assertEquals(listOf("here"), model.uiState.value.visibleRows.map { it.id })
    }

    @Test
    fun months_with_transactions_are_offered_and_the_newest_opens() = runTest {
        // The reported bug: months came from when statements were imported,
        // so a statement of earlier months, imported this month, offered only
        // this month — empty — and no way to reach the months it covered.
        val now = DashboardMonths.current()
        val last = DashboardMonths.previous(now)
        val before = DashboardMonths.previous(last)
        val repo = FakeTransactions(
            ReviewPage(
                rows = listOf(
                    Transaction(id = "a", occurredOn = DashboardMonths.wire(last) + "-15"),
                    Transaction(id = "b", occurredOn = DashboardMonths.wire(before) + "-03"),
                ),
            ),
        )

        val model = model(repo, FakeImports(emptyList()))

        assertEquals(listOf(now, last, before), model.uiState.value.months)
        assertEquals(last, model.uiState.value.month, "not an empty current month")
        assertEquals(DashboardMonths.wire(last), repo.asks.last().month)
    }

    // ── After an import ─────────────────────────────────────────────────

    private val now = DashboardMonths.current()
    private val earlier = DashboardMonths.previous(DashboardMonths.previous(now))

    private fun on(id: String, month: kotlinx.datetime.LocalDate) = Transaction(id = id, occurredOn = DashboardMonths.wire(month) + "-03")

    @Test
    fun an_import_while_an_older_month_is_shown_opens_the_month_it_landed_in() = runTest {
        // The reported bug: October's rows imported while August was on
        // screen, and the list stayed on August — the rows were there,
        // behind a chip nobody tapped.
        val repo = FakeTransactions(ReviewPage(rows = listOf(on("old", now), on("a", earlier))))
        val model = model(repo)
        model.showMonth(earlier)

        repo.page = ReviewPage(rows = listOf(on("new", now), on("old", now), on("a", earlier)))
        LedgerChanged.announce()

        assertEquals(now, model.uiState.value.month)
        assertEquals(DashboardMonths.wire(now), repo.asks.last().month)
        assertEquals(listOf("new", "old"), model.uiState.value.visibleRows.map { it.id })
    }

    @Test
    fun an_edit_elsewhere_keeps_the_month_being_looked_at() = runTest {
        val repo = FakeTransactions(ReviewPage(rows = listOf(on("old", now), on("a", earlier))))
        val model = model(repo)
        model.showMonth(earlier)

        LedgerChanged.announce()

        assertEquals(earlier, model.uiState.value.month)
    }

    @Test
    fun an_import_selects_its_statement() = runTest {
        val imports = FakeImports(listOf(statement("imp-1")))
        val model = model(imports = imports)
        model.showMode(TransactionBrowsing.Mode.BY_STATEMENT)

        imports.imports = listOf(statement("imp-2"), statement("imp-1"))
        LedgerChanged.announce()

        assertEquals("imp-2", model.uiState.value.statementId)
    }

    // ── Deleting from the editor ────────────────────────────────────────

    @Test
    fun deleting_from_the_editor_sends_it_closes_the_editor_and_tells_home() = runTest {
        val (model, repo) = editable()
        model.edit("r1")
        val before = repo.asks.size

        model.deleteEditing()

        assertEquals(listOf("r1"), repo.deleted)
        assertNull(model.uiState.value.editing)
        assertTrue(model.uiState.value.rows.none { it.id == "r1" })
        // Announced: this list re-reads, as home does.
        assertTrue(repo.asks.drop(before).any { it.cursor == null && it.month != null }, "the month was read again")
    }

    @Test
    fun a_delete_the_server_refuses_keeps_the_editor_open_and_says_why() = runTest {
        val (model, repo) = editable()
        model.edit("r1")
        repo.deleteFails = ApiException.Network(RuntimeException("offline"))

        model.deleteEditing()

        assertEquals("r1", model.uiState.value.editing?.id)
        assertFalse(model.uiState.value.deleting)
        assertEquals(ApiException.Network(RuntimeException("offline")).messageKey, model.uiState.value.editErrorKey)
    }

    @Test
    fun the_delete_question_names_the_row() {
        val state = TransactionsUiState(editing = loblaws)
        assertEquals("LOBLAWS 1234", state.editor?.deleteSummary?.first())
        assertEquals(3, state.editor?.deleteSummary?.size)
    }
}
