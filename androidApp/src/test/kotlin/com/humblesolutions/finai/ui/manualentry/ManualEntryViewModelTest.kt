package com.humblesolutions.finai.ui.manualentry

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.NewAccount
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.ReviewReason
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.ManualEntryBlock
import com.humblesolutions.finai.usecase.ManualEntrySaved
import com.humblesolutions.finai.usecase.NewAccountBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manual entry model, driven through [ManualEntryViewModel.bind] with fake
 * repositories: what Save sends, what the duplicate warning does, and what
 * survives the process being killed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualEntryViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate(2026, 9, 29)
    private val chequing = Account(id = "acct-1", name = "RBC Chequing", kind = AccountKind.CHEQUING, currency = "CAD")
    private val groceries = Category(id = "cat-1", slug = "groceries", name = "Groceries")

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = ManualEntryViewModel(saved) { today }

    /** Answers every question, the way a person filling the form would. */
    private fun ManualEntryViewModel.fillIn() {
        onAccountChosen(chequing.id)
        onToday()
        onAmountChange("1,200.5")
        onDirectionChosen(TransactionDirection.DEBIT)
        onDescriptionChange("  Rent  ")
    }

    // ── Opening ─────────────────────────────────────────────────────────

    @Test
    fun `the form opens with nothing answered and Save disabled without a scolding`() {
        val model = model()
        model.bind("alice") { repositories() }

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf(chequing), state.accounts)
        assertEquals("en-CA", state.locale)
        // No silent defaults: no account, no date, no direction.
        assertNull(state.draft.accountId)
        assertNull(state.draft.occurredOn)
        assertNull(state.draft.direction)
        assertFalse(state.canSave)
        assertEquals(ManualEntryBlock.NO_ACCOUNT, state.block)
        assertNull(state.notice)
    }

    @Test
    fun `binding the same user again does not reload over what was typed`() {
        val accounts = FakeAccounts()
        val model = model()
        model.bind("alice") { repositories(accounts = accounts) }
        model.onAmountChange("12")

        model.bind("alice") { repositories(accounts = accounts) }

        assertEquals(1, accounts.lists)
        assertEquals("12", model.uiState.value.draft.amount)
    }

    @Test
    fun `no accounts to be had says so and a retry loads them`() {
        val accounts = FakeAccounts(failList = true)
        val model = model()
        model.bind("alice") { repositories(accounts = accounts) }

        assertTrue(model.uiState.value.loadFailed)
        assertFalse(model.uiState.value.canSave)

        accounts.failList = false
        model.load()

        assertFalse(model.uiState.value.loadFailed)
        assertEquals(listOf(chequing), model.uiState.value.accounts)
    }

    @Test
    fun `categories that cannot be had leave the form usable`() {
        val model = model()
        model.bind("alice") { repositories(categories = FakeCategories(fail = true)) }
        model.fillIn()

        assertFalse(model.uiState.value.loadFailed)
        assertTrue(model.uiState.value.canSave)
    }

    // ── Save and its reason ─────────────────────────────────────────────

    @Test
    fun `Save stays disabled with the reason once the form is touched`() {
        val model = model()
        model.bind("alice") { repositories() }

        model.onAmountChange("12")

        val state = model.uiState.value
        assertFalse(state.canSave)
        assertEquals(ManualEntryBlock.NO_ACCOUNT, state.notice)
    }

    @Test
    fun `Today is the injected date and a future day is refused`() {
        val model = model()
        model.bind("alice") { repositories() }
        model.fillIn()

        assertEquals(today, model.uiState.value.draft.occurredOn)
        model.onDateChosen(LocalDate(2026, 9, 30))

        assertEquals(ManualEntryBlock.FUTURE_DATE, model.uiState.value.block)
        assertFalse(model.uiState.value.canSave)
    }

    @Test
    fun `a screen left open past midnight catches up without touching the entry`() {
        var clock = today
        val model = ManualEntryViewModel(SavedStateHandle()) { clock }
        model.bind("alice") { repositories() }
        model.fillIn()
        model.onDateChosen(LocalDate(2026, 9, 30))
        assertEquals(ManualEntryBlock.FUTURE_DATE, model.uiState.value.block)

        clock = LocalDate(2026, 9, 30)
        model.refreshToday()

        val state = model.uiState.value
        assertEquals(clock, state.today)
        assertNull(state.block)
        assertEquals(LocalDate(2026, 9, 30), state.draft.occurredOn)
    }

    @Test
    fun `saving while blocked sends nothing and shows why`() {
        val transactions = FakeTransactions()
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }

        model.save()

        assertEquals(emptyList(), transactions.sent)
        assertEquals(ManualEntryBlock.NO_ACCOUNT, model.uiState.value.notice)
    }

    @Test
    fun `a complete entry is sent as the shared rule builds it`() {
        val transactions = FakeTransactions()
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        model.fillIn()
        model.onCategoryChosen(groceries.id)

        model.save()

        assertEquals(
            listOf(
                NewTransaction(
                    accountId = "acct-1",
                    occurredOn = "2026-09-29",
                    amount = "1200.50",
                    direction = TransactionDirection.DEBIT,
                    description = "Rent",
                    categoryId = "cat-1",
                    allowDuplicate = false,
                ),
            ),
            transactions.sent,
        )
    }

    @Test
    fun `after a save the account stays chosen and everything else starts unanswered`() {
        val model = model()
        model.bind("alice") { repositories() }
        model.fillIn()

        model.save()

        val state = model.uiState.value
        assertEquals(ManualEntrySaved.SAVED, state.saved)
        assertEquals(chequing.id, state.draft.accountId)
        assertNull(state.draft.occurredOn)
        assertNull(state.draft.direction)
        assertEquals("", state.draft.amount)
        assertNull(state.notice)
    }

    @Test
    fun `a save the backend sent to review says why`() {
        // #38: no AI consent means no category, so the row waits in review.
        // "Transaction saved" alone would leave the person thinking it is filed.
        val model = model()
        model.bind("alice") {
            repositories(
                transactions = FakeTransactions(
                    answer = { Transaction(id = "t-new", needsReview = true, reviewReason = ReviewReason.UNKNOWN_CATEGORY) },
                ),
            )
        }
        model.fillIn()

        model.save()

        assertEquals(ManualEntrySaved.NEEDS_CATEGORY, model.uiState.value.saved)
        assertEquals(Strings.manual_entry_saved_needs_category, model.uiState.value.saved?.messageKey)
    }

    @Test
    fun `a save flagged as a possible duplicate says so`() {
        val model = model()
        model.bind("alice") {
            repositories(
                transactions = FakeTransactions(
                    answer = { Transaction(id = "t-new", needsReview = true, reviewReason = ReviewReason.SUSPECTED_DUPLICATE) },
                ),
            )
        }
        model.fillIn()

        model.save()

        assertEquals(ManualEntrySaved.LOOKS_LIKE_A_DUPLICATE, model.uiState.value.saved)
    }

    @Test
    fun `the saved line goes with the next change`() {
        val model = model()
        model.bind("alice") { repositories() }
        model.fillIn()
        model.save()

        model.onAmountChange("3")

        assertNull(model.uiState.value.saved)
    }

    @Test
    fun `a failed save keeps what was typed and says so`() {
        val model = model()
        model.bind("alice") { repositories(transactions = FakeTransactions(failWith = ApiException.Network(RuntimeException()))) }
        model.fillIn()

        model.save()

        val state = model.uiState.value
        assertFalse(state.saving)
        assertNull(state.saved)
        assertEquals("1,200.5", state.draft.amount)
        assertEquals(Strings.error_network, state.errorKey)
    }

    @Test
    fun `Save pressed twice while the first is on its way sends once`() {
        val gate = CompletableDeferred<Unit>()
        val transactions = FakeTransactions(gate = gate)
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        model.fillIn()

        model.save()
        model.save()
        gate.complete(Unit)

        assertEquals(1, transactions.sent.size)
    }

    // ── The duplicate warning ───────────────────────────────────────────

    @Test
    fun `a duplicate asks and keeping it resends the same entry marked as meant`() {
        val match = DuplicateMatch(id = "t-9", occurredOn = "2026-09-29", amount = "1200.50", description = "Rent")
        val transactions = FakeTransactions(failWith = ApiException.DuplicateTransaction(match))
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        model.fillIn()

        model.save()

        assertEquals(
            "You already have $1,200.50 on Sep 29, 2026: \"Rent\". Is this a second one?",
            model.uiState.value.duplicateMessage,
        )
        assertNull(model.uiState.value.saved)

        transactions.failWith = null
        model.keepDuplicate()

        assertEquals(listOf(false, true), transactions.sent.map { it.allowDuplicate })
        assertEquals(transactions.sent[0].copy(allowDuplicate = true), transactions.sent[1])
        assertNull(model.uiState.value.duplicate)
        assertEquals(ManualEntrySaved.SAVED, model.uiState.value.saved)
    }

    @Test
    fun `cancelling the duplicate warning saves nothing and keeps the entry`() {
        val transactions = FakeTransactions(failWith = ApiException.DuplicateTransaction(null))
        val model = model()
        model.bind("alice") { repositories(transactions = transactions) }
        model.fillIn()
        model.save()

        model.dismissDuplicate()

        assertNull(model.uiState.value.duplicate)
        assertEquals(1, transactions.sent.size)
        assertEquals("1,200.5", model.uiState.value.draft.amount)
    }

    // ── Surviving the process being killed ──────────────────────────────

    @Test
    fun `a half-typed entry comes back after the process is killed`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories() }
        before.fillIn()
        before.onCategoryChosen(groceries.id)

        val after = model(restart(saved))
        after.bind("alice") { repositories() }

        val draft = after.uiState.value.draft
        assertEquals(before.uiState.value.draft, draft)
        assertTrue(after.uiState.value.canSave)
    }

    @Test
    fun `an account being added comes back after the process is killed`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories() }
        before.openNewAccount()
        before.onNewAccountName("Visa")
        before.onNewAccountKind(AccountKind.CREDIT_CARD)

        val after = model(restart(saved))
        after.bind("alice") { repositories() }

        assertEquals("Visa", after.uiState.value.newAccount?.name)
        assertEquals(AccountKind.CREDIT_CARD, after.uiState.value.newAccount?.kind)
    }

    @Test
    fun `a restored entry is never handed to someone else`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories() }
        before.fillIn()

        val after = model(restart(saved))
        after.bind("bob") { repositories() }

        assertEquals("", after.uiState.value.draft.amount)
        assertNull(after.uiState.value.draft.accountId)
    }

    @Test
    fun `a restored account that no longer exists goes back to unanswered`() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.bind("alice") { repositories() }
        before.fillIn()

        val after = model(restart(saved))
        after.bind("alice") { repositories(accounts = FakeAccounts(stored = emptyList())) }

        assertNull(after.uiState.value.draft.accountId)
        assertEquals(ManualEntryBlock.NO_ACCOUNT, after.uiState.value.block)
    }

    @Test
    fun `leaving the screen on purpose starts the next visit clean`() {
        val model = model()
        model.bind("alice") { repositories() }
        model.fillIn()

        model.discard()

        assertEquals("", model.uiState.value.draft.amount)
        assertNull(model.uiState.value.draft.accountId)
        assertEquals(listOf(chequing), model.uiState.value.accounts)
    }

    @Test
    fun `a save that answers after someone else is bound changes nothing`() {
        val gate = CompletableDeferred<Unit>()
        val model = model()
        model.bind("alice") { repositories(transactions = FakeTransactions(gate = gate)) }
        model.fillIn()
        model.save()

        model.bind("bob") { repositories() }
        gate.complete(Unit)

        assertNull(model.uiState.value.saved)
        assertFalse(model.uiState.value.saving)
    }

    @Test
    fun `leaving while a save is on its way keeps the next visit clean`() {
        val gate = CompletableDeferred<Unit>()
        val model = model()
        model.bind("alice") { repositories(transactions = FakeTransactions(gate = gate)) }
        model.fillIn()
        model.save()

        model.discard()
        assertFalse(model.uiState.value.saving)
        gate.complete(Unit)

        val state = model.uiState.value
        assertNull(state.saved)
        assertNull(state.draft.accountId)
        assertEquals("", state.draft.amount)
    }

    @Test
    fun `an unknown user is not offered a retry that cannot help`() {
        val model = model()

        model.bind("") { repositories() }

        assertTrue(model.uiState.value.loadFailed)
        assertFalse(model.uiState.value.canRetry)
    }

    // ── Adding an account ───────────────────────────────────────────────

    @Test
    fun `a new account's kind starts unanswered and Add waits for it`() {
        val model = model()
        model.bind("alice") { repositories() }
        model.openNewAccount()
        model.onNewAccountName("Visa")

        assertNull(model.uiState.value.newAccount?.kind)
        assertFalse(model.uiState.value.canCreateAccount)
        assertEquals(NewAccountBlock.NO_KIND, model.uiState.value.newAccountNotice)
    }

    @Test
    fun `an added account is created and chosen for the entry`() {
        val accounts = FakeAccounts()
        val model = model()
        model.bind("alice") { repositories(accounts = accounts) }
        model.openNewAccount()
        model.onNewAccountName("  Visa ")
        model.onNewAccountKind(AccountKind.CREDIT_CARD)

        model.createAccount()

        assertEquals(listOf(NewAccount("Visa", AccountKind.CREDIT_CARD)), accounts.created)
        val state = model.uiState.value
        assertNull(state.newAccount)
        assertEquals("acct-new", state.draft.accountId)
        assertEquals("Visa", state.account?.name)
    }

    @Test
    fun `a name already used keeps the sheet open and says so`() {
        val model = model()
        model.bind("alice") { repositories(accounts = FakeAccounts(failCreate = ApiException.DuplicateAccountName())) }
        model.openNewAccount()
        model.onNewAccountName("RBC Chequing")
        model.onNewAccountKind(AccountKind.CHEQUING)

        model.createAccount()

        val state = model.uiState.value
        assertNotNull(state.newAccount)
        assertEquals(Strings.account_name_taken, state.newAccountErrorKey)
        assertNull(state.draft.accountId)
    }

    @Test
    fun `an account created after leaving is listed but not chosen`() {
        val gate = CompletableDeferred<Unit>()
        val model = model()
        model.bind("alice") { repositories(accounts = FakeAccounts(createGate = gate)) }
        model.openNewAccount()
        model.onNewAccountName("Visa")
        model.onNewAccountKind(AccountKind.CREDIT_CARD)
        model.createAccount()

        model.discard()
        gate.complete(Unit)

        val state = model.uiState.value
        assertTrue(state.accounts.any { it.id == "acct-new" })
        assertNull(state.draft.accountId)
        assertFalse(state.creatingAccount)
        assertFalse(state.touched)
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    /** What Android does on a restart: a new handle holding the old one's values. */
    private fun restart(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun repositories(
        accounts: FakeAccounts = FakeAccounts(),
        categories: FakeCategories = FakeCategories(),
        transactions: FakeTransactions = FakeTransactions(),
    ) = ManualEntryRepositories(accounts, categories, transactions, FakeCapabilities())

    private inner class FakeAccounts(
        private val stored: List<Account> = listOf(chequing),
        var failList: Boolean = false,
        private val failCreate: ApiException? = null,
        private val createGate: CompletableDeferred<Unit>? = null,
    ) : AccountsRepository {
        var lists = 0
        val created = mutableListOf<NewAccount>()

        override suspend fun list(): List<Account> {
            lists++
            if (failList) throw ApiException.Network(RuntimeException("offline"))
            return stored
        }

        override suspend fun create(account: NewAccount): Account {
            createGate?.await()
            failCreate?.let { throw it }
            created += account
            return Account(id = "acct-new", name = account.name, kind = account.kind, currency = "CAD")
        }

        override fun close() = Unit
    }

    private inner class FakeCategories(private val fail: Boolean = false) : CategoriesRepository {
        override suspend fun list(): List<Category> {
            if (fail) throw ApiException.Network(RuntimeException("offline"))
            return listOf(groceries)
        }

        override suspend fun create(name: String) = error("manual entry never makes a category")

        override fun close() = Unit
    }

    private class FakeTransactions(
        var failWith: ApiException? = null,
        private val gate: CompletableDeferred<Unit>? = null,
        private val answer: ((NewTransaction) -> Transaction)? = null,
    ) : TransactionsRepository {
        override suspend fun list(
            statementImportId: String?,
            needsReview: Boolean?,
            cursor: String?,
        ): ReviewPage = ReviewPage()

        val sent = mutableListOf<NewTransaction>()

        override suspend fun create(entry: NewTransaction): Transaction {
            sent += entry
            gate?.await()
            failWith?.let { throw it }
            answer?.let { return it(entry) }
            return Transaction(id = "t-new", accountId = entry.accountId, amount = entry.amount)
        }

        // The review queue's half of this interface (#32), unused here.
        override suspend fun review(cursor: String?) = error("manual entry never reads the queue")

        override suspend fun correct(id: String, patch: TransactionPatch) = error("not manual entry")

        override suspend fun confirm(id: String) = error("not manual entry")

        override suspend fun confirmAll(ids: List<String>) = error("not manual entry")

        override suspend fun delete(id: String) = error("not manual entry")

        override fun close() = Unit
    }

    private class FakeCapabilities : CapabilitiesRepository {
        override suspend fun fetch() = Capabilities(currency = "CAD", locale = "en-CA")
        override fun close() = Unit
    }
}
