package com.humblesolutions.finai.ui.review

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.ReviewReason
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CorrectionBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

/**
 * The review queue's model (#32), driven through [ReviewViewModel.bind] with
 * fake clients. Every rule the ticket names is asserted over the UI state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val groceries = Category(id = "cat-1", slug = "groceries", name = "Groceries")
    private val dining = Category(id = "cat-2", slug = "dining", name = "Dining out")

    private fun row(
        id: String,
        categoryId: String? = "cat-1",
        reason: ReviewReason? = ReviewReason.LOW_CONFIDENCE,
        day: String = "2026-09-28",
        amount: String = "12.40",
    ) = Transaction(
        id = id,
        occurredOn = day,
        amount = amount,
        currency = "CAD",
        direction = TransactionDirection.DEBIT,
        description = "TIM HORTONS",
        merchant = "Tim Hortons",
        categoryId = categoryId,
        needsReview = true,
        reviewReason = reason,
    )

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = ReviewViewModel(saved)

    // ── The list ────────────────────────────────────────────────────────

    @Test
    fun `the queue loads with its categories and groups by date`() {
        val model = model()
        model.bind("alice") {
            repositories(
                transactions = FakeTransactions(
                    pages = listOf(ReviewPage(rows = listOf(row("a"), row("b"), row("c", day = "2026-09-27")))),
                ),
            )
        }

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(3, state.rows.size)
        assertEquals(listOf("2026-09-28" to 2, "2026-09-27" to 1), state.byDate.map { it.first to it.second.size })
        assertEquals("Groceries", state.categoryNameFor(state.rows.first()))
        assertEquals("$12.40", state.amountFor(state.rows.first()))
    }

    @Test
    fun `an empty queue is a good outcome, not an error`() {
        val model = model()
        model.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage()))) }

        val state = model.uiState.value
        assertTrue(state.isEmpty)
        assertFalse(state.loadFailed)
        assertNull(state.errorKey)
    }

    @Test
    fun `a failed load offers a retry that works`() {
        val transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))), failFirstPage = true)
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        assertTrue(model.uiState.value.loadFailed)

        model.load()

        assertFalse(model.uiState.value.loadFailed)
        assertEquals(1, model.uiState.value.rows.size)
    }

    @Test
    fun `paging follows the cursor and never lists a row twice`() {
        val transactions = FakeTransactions(
            pages = listOf(
                ReviewPage(rows = listOf(row("a")), nextCursor = "c1"),
                // The server repeating a row across pages must not double it.
                ReviewPage(rows = listOf(row("a"), row("b")), nextCursor = null),
            ),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.loadMore()

        assertEquals(listOf("a", "b"), model.uiState.value.rows.map { it.id })
        assertEquals(listOf(null, "c1"), transactions.cursors)
        assertFalse(model.uiState.value.canLoadMore)
    }

    // ── Confirm all ─────────────────────────────────────────────────────

    @Test
    fun `confirm all sends only the rows with a category and says what cleared`() {
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b", categoryId = null), row("c")))),
            confirmed = 2,
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        assertEquals("Confirm 2 transactions", model.uiState.value.confirmAllLabel)

        model.confirmAll()

        assertEquals(listOf(listOf("a", "c")), transactions.confirmedAll)
        assertEquals(listOf("2 transactions confirmed"), model.uiState.value.announcements)
        // The uncategorized row stays, still asking for one.
        assertEquals(listOf("b"), model.uiState.value.rows.map { it.id })
    }

    @Test
    fun `a failed confirm all rolls the rows back visibly`() {
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))),
            failConfirmAll = ApiException.Network(RuntimeException("offline")),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.confirmAll()

        val state = model.uiState.value
        assertEquals(listOf("a", "b"), state.rows.map { it.id })
        assertFalse(state.confirmingAll)
        assertNotNull(state.errorKey)
    }

    @Test
    fun `when the server clears fewer than were sent the queue is re-read`() {
        val transactions = FakeTransactions(
            pages = listOf(
                ReviewPage(rows = listOf(row("a"), row("b"))),
                ReviewPage(rows = listOf(row("b", categoryId = null, reason = ReviewReason.UNKNOWN_CATEGORY))),
            ),
            confirmed = 1,
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.confirmAll()

        assertEquals("1 confirmed. 1 still need a category.", model.uiState.value.announcements.single())
        assertEquals(listOf("b"), model.uiState.value.rows.map { it.id })
    }

    @Test
    fun `confirming one uncategorized row leaves it asking for a category`() {
        // Finance-backend #48: answering the duplicate question does not let
        // a row leave with no category.
        val stayed = row("a", categoryId = null, reason = ReviewReason.UNKNOWN_CATEGORY)
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a", categoryId = null, reason = ReviewReason.SUSPECTED_DUPLICATE)))),
            confirmOne = PatchOutcome(transaction = stayed),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.confirm("a")

        val state = model.uiState.value
        assertEquals(listOf("a"), state.rows.map { it.id })
        assertEquals(ReviewReason.UNKNOWN_CATEGORY, state.rows.single().reviewReason)
        assertFalse(state.isBusy("a"))
    }

    @Test
    fun `a row that fails says so on the row and leaves the list alone`() {
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))),
            failConfirmOne = ApiException.Network(RuntimeException("offline")),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.confirm("a")

        val state = model.uiState.value
        assertNotNull(state.errorFor("a"))
        assertNull(state.errorFor("b"))
        assertEquals(listOf("a", "b"), state.rows.map { it.id })
        assertFalse(state.isBusy("a"))
    }

    // ── Correcting ──────────────────────────────────────────────────────

    @Test
    fun `a correction sends only what changed and the row leaves the queue`() {
        val done = row("a").copy(needsReview = false, categoryId = "cat-2")
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))),
            correction = PatchOutcome(transaction = done),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onCategoryChosen("cat-2")
        model.saveCorrection()

        assertEquals(TransactionPatch(categoryId = "cat-2"), transactions.corrections.single().second)
        assertEquals(listOf("b"), model.uiState.value.rows.map { it.id })
        assertNull(model.uiState.value.editing)
    }

    @Test
    fun `an amount correction round-trips as a decimal string`() {
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a", amount = "12.40")))),
            correction = PatchOutcome(transaction = row("a").copy(needsReview = false)),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onAmountChange("1,234.5")
        model.saveCorrection()

        assertEquals("1234.50", assertNotNull(transactions.corrections.single().second.amount))
    }

    @Test
    fun `save waits until something has actually changed`() {
        val model = model()
        model.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }

        model.edit("a")

        val state = model.uiState.value
        assertFalse(state.canSaveCorrection)
        assertEquals(CorrectionBlock.NOTHING_CHANGED, state.editBlock)
        // Not scolded for it before touching anything.
        assertNull(state.editNotice)
    }

    @Test
    fun `an invalid amount blocks the save and says why`() {
        val model = model()
        model.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }

        model.edit("a")
        model.onAmountChange("abc")

        assertFalse(model.uiState.value.canSaveCorrection)
        assertEquals(CorrectionBlock.AMOUNT_NOT_MONEY, model.uiState.value.editNotice)
    }

    @Test
    fun `a correction that moved other rows says so and re-reads the queue`() {
        val transactions = FakeTransactions(
            pages = listOf(
                ReviewPage(rows = listOf(row("a"), row("b"))),
                ReviewPage(rows = listOf(row("b", categoryId = "cat-2"))),
            ),
            correction = PatchOutcome(
                transaction = row("a").copy(needsReview = false),
                ruleRecorded = true,
                recategorized = 1,
            ),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onCategoryChosen("cat-2")
        model.saveCorrection()

        val state = model.uiState.value
        assertEquals(
            listOf("We'll file Tim Hortons this way from now on.", "Also applied to 1 other transaction waiting here."),
            state.announcements,
        )
        // Re-read, so the row that moved shows its new category.
        assertEquals("Dining out", state.categoryNameFor(state.rows.single()))
        assertEquals(2, transactions.pagesRead)
    }

    @Test
    fun `a correction that moved nothing costs no extra request`() {
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a")))),
            correction = PatchOutcome(transaction = row("a").copy(needsReview = false), ruleRecorded = true),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onDescriptionChange("Tim Hortons Oakville")
        model.saveCorrection()

        assertEquals(1, transactions.pagesRead)
    }

    @Test
    fun `each action says its own thing rather than stacking lines`() {
        // Working down a queue must not leave a wall of text above the button.
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))),
            correction = PatchOutcome(transaction = row("a").copy(needsReview = false), recategorized = 2),
            confirmOne = PatchOutcome(transaction = row("b").copy(needsReview = false), importFinished = true),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onCategoryChosen("cat-2")
        model.saveCorrection()
        val afterCorrection = model.uiState.value.announcements
        model.confirm("b")

        assertEquals(listOf("Also applied to 2 other transactions waiting here."), afterCorrection)
        assertEquals(
            listOf("Statement finished — everything from it has been looked at."),
            model.uiState.value.announcements,
        )
    }

    @Test
    fun `re-reading after an action keeps the list up instead of blanking it`() {
        // A refresh is not a first load: the coin belongs to opening the
        // screen, not to finishing a correction.
        val gate = CompletableDeferred<Unit>()
        val transactions = FakeTransactions(
            pages = listOf(
                ReviewPage(rows = listOf(row("a"), row("b"))),
                ReviewPage(rows = listOf(row("b", categoryId = "cat-2"))),
            ),
            correction = PatchOutcome(transaction = row("a").copy(needsReview = false), recategorized = 1),
            laterPageGate = gate,
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onCategoryChosen("cat-2")
        model.saveCorrection()

        // Caught while the re-read is still in flight — the end state looks
        // the same either way, which is how a blanking refresh hides.
        val mid = model.uiState.value
        assertFalse(mid.loading, "a refresh blanked the screen and showed the coin")
        assertTrue(mid.refreshing)
        // The corrected row has left the queue; the rest are still on screen
        // rather than replaced by an empty box.
        assertEquals(listOf("b"), mid.rows.map { it.id }, "the list went away during the refresh")

        gate.complete(Unit)

        val state = model.uiState.value
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertEquals(2, transactions.pagesRead)
        assertEquals(listOf("b"), state.rows.map { it.id })
    }

    @Test
    fun `a refresh that fails keeps the rows and says so`() {
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))),
            correction = PatchOutcome(transaction = row("a").copy(needsReview = false), recategorized = 1),
            failLaterPages = true,
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.edit("a")
        model.onCategoryChosen("cat-2")
        model.saveCorrection()

        val state = model.uiState.value
        // Stale beats blank: the corrected row is gone, the rest remain.
        assertEquals(listOf("b"), state.rows.map { it.id })
        assertFalse(state.loadFailed)
        assertNotNull(state.errorKey)
    }

    // ── A category of their own ─────────────────────────────────────────

    @Test
    fun `a new category is created and filed into straight away`() {
        val categories = FakeCategories()
        val model = model()
        model.bind("alice") {
            repositories(
                transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a"))))),
                categories = categories,
            )
        }

        model.edit("a")
        model.openNewCategory()
        model.onNewCategoryName("Side business")
        model.createCategory()

        assertEquals(listOf("Side business"), categories.created)
        assertEquals("cat-new", model.uiState.value.draft.categoryId)
        assertNull(model.uiState.value.newCategoryName)
    }

    @Test
    fun `a name already taken selects the category that exists`() {
        val categories = FakeCategories(failCreate = ApiException.CategoryExists("cat-2"))
        val model = model()
        model.bind("alice") {
            repositories(
                transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a"))))),
                categories = categories,
            )
        }

        model.edit("a")
        model.openNewCategory()
        model.onNewCategoryName("Dining out")
        model.createCategory()

        // Selected rather than refused: thinking of another name is busywork.
        assertEquals("cat-2", model.uiState.value.draft.categoryId)
        assertNull(model.uiState.value.newCategoryName)
    }

    // ── Deleting, with undo ─────────────────────────────────────────────

    @Test
    fun `undo inside the window sends nothing at all`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))))
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        runCurrent()

        model.delete("a")
        runCurrent()
        assertEquals(listOf("b"), model.uiState.value.visibleRows.map { it.id })

        model.undoDelete()
        advanceTimeBy(ReviewViewModel.UNDO_WINDOW_MS * 2)
        runCurrent()

        assertEquals(listOf("a", "b"), model.uiState.value.visibleRows.map { it.id })
        assertEquals(emptyList(), transactions.deleted)
    }

    @Test
    fun `the delete goes once the window closes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a"), row("b")))))
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        runCurrent()

        model.delete("a")
        advanceTimeBy(ReviewViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertEquals(listOf("a"), transactions.deleted)
        assertEquals(listOf("b"), model.uiState.value.rows.map { it.id })
    }

    @Test
    fun `leaving the screen sends the delete it was given`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        runCurrent()
        model.delete("a")
        runCurrent()

        model.flushPendingDelete()
        runCurrent()

        assertEquals(listOf("a"), transactions.deleted)
    }

    @Test
    fun `a delete the server refuses puts the row back where it was`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val transactions = FakeTransactions(
            pages = listOf(ReviewPage(rows = listOf(row("a"), row("b"), row("c")))),
            failDelete = ApiException.Network(RuntimeException("offline")),
        )
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        runCurrent()

        model.delete("b")
        advanceTimeBy(ReviewViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertEquals(listOf("a", "b", "c"), model.uiState.value.rows.map { it.id })
        assertNotNull(model.uiState.value.errorKey)
    }

    // ── Across process death ────────────────────────────────────────────

    @Test
    fun `a half-typed correction comes back after the process is killed`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }
        before.edit("a")
        before.onAmountChange("99.99")
        before.onDescriptionChange("Tim Hortons Oakville")

        val after = model(restart(saved))
        after.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }

        val state = after.uiState.value
        assertEquals("a", assertNotNull(state.editing).id)
        assertEquals("99.99", state.draft.amount)
        assertEquals("Tim Hortons Oakville", state.draft.description)
        assertTrue(state.canSaveCorrection)
    }

    @Test
    fun `a correction for a row that is gone is not restored`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }
        before.edit("a")
        before.onAmountChange("99.99")

        val after = model(restart(saved))
        after.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("b")))))) }

        assertNull(after.uiState.value.editing)
    }

    @Test
    fun `someone else's correction is never restored`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }
        before.edit("a")
        before.onAmountChange("99.99")

        val after = model(restart(saved))
        after.bind("bob") { repositories(transactions = FakeTransactions(pages = listOf(ReviewPage(rows = listOf(row("a")))))) }

        assertNull(after.uiState.value.editing)
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    private fun restart(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun repositories(
        transactions: FakeTransactions = FakeTransactions(pages = listOf(ReviewPage())),
        categories: FakeCategories = FakeCategories(),
    ) = ReviewRepositories(transactions, categories, FakeCapabilities())

    private class FakeTransactions(
        private val pages: List<ReviewPage>,
        private val confirmed: Int = 0,
        private val confirmOne: PatchOutcome? = null,
        private val correction: PatchOutcome? = null,
        private val failFirstPage: Boolean = false,
        private val failLaterPages: Boolean = false,
        private val laterPageGate: CompletableDeferred<Unit>? = null,
        private val failConfirmAll: ApiException? = null,
        private val failConfirmOne: ApiException? = null,
        private val failDelete: ApiException? = null,
    ) : TransactionsRepository {
        var pagesRead = 0
        val cursors = mutableListOf<String?>()
        val confirmedAll = mutableListOf<List<String>>()
        val corrections = mutableListOf<Pair<String, TransactionPatch>>()
        val deleted = mutableListOf<String>()

        override suspend fun create(entry: NewTransaction) = error("not this screen")

        override suspend fun review(cursor: String?): ReviewPage {
            cursors += cursor
            if ((failFirstPage && pagesRead == 0) || (failLaterPages && pagesRead > 0)) {
                pagesRead++
                throw ApiException.Network(RuntimeException("offline"))
            }
            if (pagesRead > 0) laterPageGate?.await()
            val page = pages[pagesRead.coerceAtMost(pages.lastIndex)]
            pagesRead++
            return page
        }

        override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome {
            corrections += id to patch
            return correction ?: PatchOutcome()
        }

        override suspend fun confirm(id: String): PatchOutcome {
            failConfirmOne?.let { throw it }
            return confirmOne ?: PatchOutcome()
        }

        override suspend fun confirmAll(ids: List<String>): ConfirmOutcome {
            failConfirmAll?.let { throw it }
            confirmedAll += ids
            return ConfirmOutcome(confirmed = confirmed)
        }

        override suspend fun delete(id: String): DeleteOutcome {
            failDelete?.let { throw it }
            deleted += id
            return DeleteOutcome()
        }

        override fun close() = Unit
    }

    private inner class FakeCategories(private val failCreate: ApiException? = null) : CategoriesRepository {
        val created = mutableListOf<String>()

        override suspend fun list() = listOf(groceries, dining)

        override suspend fun create(name: String): Category {
            failCreate?.let { throw it }
            created += name
            return Category(id = "cat-new", slug = "side_business", name = name, isSystem = false)
        }

        override fun close() = Unit
    }

    private class FakeCapabilities : CapabilitiesRepository {
        override suspend fun fetch() = Capabilities(currency = "CAD", locale = "en-CA")
        override fun close() = Unit
    }
}
