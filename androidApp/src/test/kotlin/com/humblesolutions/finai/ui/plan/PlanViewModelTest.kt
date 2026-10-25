package com.humblesolutions.finai.ui.plan

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.BalanceSource
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.DebtChanges
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.DebtsPage
import com.humblesolutions.finai.model.NewAccount
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanDebt
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanSplit
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.model.PlanSuggested
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.DebtsRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.BalanceBlock
import com.humblesolutions.finai.usecase.PlanChoiceBlock
import com.humblesolutions.finai.util.GoalsChanged
import com.humblesolutions.finai.util.PlanChanged
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ── Fakes ───────────────────────────────────────────────────────────

    private class FakePlan(var plan: Plan = plan()) : PlanRepository {
        var reads = 0
        val saved = mutableListOf<PlanPreferencesToSave>()
        var failRead: ApiException? = null
        var failSave: ApiException? = null

        override suspend fun plan(month: String?): Plan {
            reads++
            failRead?.let { throw it }
            return plan
        }

        override suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences {
            saved += choices
            failSave?.let { throw it }
            plan = plan.copy(preferences = plan.preferences.copy(saved = true, strategy = choices.strategy, buffer = choices.buffer))
            return plan.preferences
        }

        override suspend fun disclaimer(): Terms? = Terms(version = "ca-v1", body = "Educational guidance, not financial advice.")

        override suspend fun diagnostics(): PlanDiagnostics = PlanDiagnostics()

        override suspend fun runDiagnostic(kind: String, savingsTarget: String, savingsNow: String): PlanDiagnostic = throw UnsupportedOperationException()

        override fun close() = Unit
    }

    private class FakeDebts(var page: DebtsPage = DebtsPage("CAD", listOf(visa()))) : DebtsRepository {
        val updates = mutableListOf<Pair<String, DebtChanges>>()
        var failWrite: ApiException? = null

        override suspend fun list(): DebtsPage = page

        override suspend fun update(id: String, changes: DebtChanges): DebtRecord {
            updates += id to changes
            failWrite?.let { throw it }
            return page.debts.first { it.id == id }
        }

        override fun close() = Unit
    }

    private class FakeAccounts : AccountsRepository {
        override suspend fun list(): List<Account> = listOf(
            Account(id = "a-card", name = "Visa card", kind = AccountKind.CREDIT_CARD),
            Account(id = "a-chq", name = "Chequing", kind = AccountKind.CHEQUING),
        )

        override suspend fun create(account: NewAccount): Account = error("not used")

        override fun close() = Unit
    }

    private class FakeCapabilities : CapabilitiesRepository {
        override suspend fun fetch(): Capabilities = Capabilities(currency = "CAD", locale = "en")
        override fun close() = Unit
    }

    private class Fakes(val plan: FakePlan = FakePlan(), val debts: FakeDebts = FakeDebts())

    private fun model(fakes: Fakes = Fakes(), saved: SavedStateHandle = SavedStateHandle()): PlanViewModel = PlanViewModel(saved).also {
        it.bind("user-1") { PlanRepositories(fakes.plan, fakes.debts, FakeAccounts(), FakeCapabilities()) }
    }

    // ── Loading ─────────────────────────────────────────────────────────

    @Test
    fun a_bind_reads_the_plan_the_debts_and_the_accounts() = runTest {
        val state = model().uiState.value

        assertFalse(state.loading)
        assertTrue(state.showsSplit)
        assertEquals(listOf("d-visa"), state.debtRows.map { it.debtId })
        assertEquals(listOf("a-card"), state.linkableAccounts.map { it.id }, "a chequing account is never linkable")
        assertEquals("Educational guidance, not financial advice.", state.disclaimerText)
    }

    @Test
    fun a_failed_refresh_keeps_the_plan_on_screen() = runTest {
        val fakes = Fakes()
        val model = model(fakes)
        fakes.plan.failRead = ApiException.Server(503)

        model.load(refresh = true)

        val state = model.uiState.value
        assertFalse(state.loadFailed)
        assertTrue(state.plan != null)
    }

    // ── The choices ─────────────────────────────────────────────────────

    @Test
    fun saving_the_choices_tells_home_and_goals() = runTest {
        val fakes = Fakes()
        val model = model(fakes)
        val plans = mutableListOf<Unit>()
        val goals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { PlanChanged.events.collect { plans += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { GoalsChanged.events.collect { goals += it } }

        model.startChoices()
        model.onChoicesDraft(model.uiState.value.choicesDraft.copy(goalSharePercent = "30"))
        model.saveChoices()

        assertEquals("30.00", fakes.plan.saved.single().goalSharePercent)
        assertFalse(model.uiState.value.choosing)
        assertEquals(1, plans.size)
        assertEquals(1, goals.size, "a buffer is a goal: Goals reads again")
    }

    @Test
    fun a_refused_save_keeps_the_sheet_open_with_why() = runTest {
        val fakes = Fakes()
        fakes.plan.failSave = ApiException.GoalLimitReached(20)
        val model = model(fakes)

        model.startChoices()
        model.saveChoices()

        val state = model.uiState.value
        assertTrue(state.choosing)
        assertEquals(ApiException.GoalLimitReached(20).messageKey, state.choicesErrorKey)
    }

    @Test
    fun a_choice_the_server_would_refuse_never_leaves() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.startChoices()
        model.onChoicesDraft(model.uiState.value.choicesDraft.copy(rateLinePercent = "150"))
        model.saveChoices()

        assertEquals(PlanChoiceBlock.RATE_LINE_INVALID, model.uiState.value.choicesBlock)
        assertTrue(fakes.plan.saved.isEmpty())
    }

    @Test
    fun before_the_first_save_the_switch_opens_the_editor_instead() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.chooseStrategy(PlanStrategy.SNOWBALL)

        val state = model.uiState.value
        assertTrue(state.choosing, "the first save keeps a buffer as a goal: the person confirms it")
        assertEquals(PlanStrategy.SNOWBALL, state.choicesDraft.strategy)
        assertTrue(fakes.plan.saved.isEmpty())
    }

    @Test
    fun once_saved_the_switch_saves_only_the_strategy() = runTest {
        val fakes = Fakes(plan = FakePlan(plan().copy(preferences = preferences().copy(saved = true))))
        val model = model(fakes)

        model.chooseStrategy(PlanStrategy.SNOWBALL)

        val sent = fakes.plan.saved.single()
        assertEquals(PlanStrategy.SNOWBALL, sent.strategy)
        assertEquals("1000.00", sent.buffer)
        assertFalse(model.uiState.value.choosing)
    }

    // ── Debts ───────────────────────────────────────────────────────────

    @Test
    fun an_edit_sends_only_what_changed() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.editDebt("d-visa")
        model.onDebtDraft(model.uiState.value.debtDraft.copy(interestRatePercent = "19.99", accountId = "a-card"))
        model.saveDebt()

        val (id, changes) = fakes.debts.updates.single()
        assertEquals("d-visa", id)
        assertEquals("19.99", changes.interestRatePercent)
        assertEquals("a-card", changes.accountId)
        assertNull(changes.name)
        assertNull(model.uiState.value.editingDebtId)
    }

    @Test
    fun a_new_balance_is_sent_and_an_unchanged_one_is_not() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.startBalance("d-visa")
        model.onBalanceAmount("4000")
        assertEquals(BalanceBlock.UNCHANGED, model.uiState.value.balanceBlock)
        model.saveBalance()
        assertTrue(fakes.debts.updates.isEmpty())

        model.onBalanceAmount("3,500")
        model.saveBalance()

        assertEquals("3500.00", fakes.debts.updates.single().second.balance)
        assertNull(model.uiState.value.balanceDebtId)
    }

    @Test
    fun what_was_typed_survives_the_process_being_reclaimed() = runTest {
        val saved = SavedStateHandle()
        val first = model(saved = saved)
        first.startBalance("d-visa")
        first.onBalanceAmount("3500")

        val restored = model(saved = saved)

        assertEquals("d-visa", restored.uiState.value.balanceDebtId)
        assertEquals("3500", restored.uiState.value.balanceAmount)
    }

    private companion object {
        fun visa() = DebtRecord(
            id = "d-visa",
            name = "Visa",
            kind = DebtKind.CREDIT_CARD,
            balance = "4000.00",
            minimumPayment = "120.00",
            interestRatePercent = "20.99",
            balanceAsOf = "2026-08-31",
            balanceSource = BalanceSource.STATEMENT,
        )

        fun preferences() = PlanPreferences(
            saved = false,
            buffer = "1000.00",
            goalSharePercent = "20.00",
            rateLinePercent = "6.00",
            strategy = PlanStrategy.AVALANCHE,
            suggested = PlanSuggested(buffer = "1000.00", goalSharePercent = "20.00", rateLinePercent = "6.00"),
        )

        fun plan() = Plan(
            month = "2026-11",
            currency = "CAD",
            status = PlanStatus.READY,
            leftover = "1200.00",
            preferences = preferences(),
            split = PlanSplit(buffer = "800.00", extraDebt = "400.00", saveOrInvest = "0.00"),
            debts = listOf(PlanDebt(debtId = "d-visa", name = "Visa", ratePercent = "20.99", order = 1, getsExtraNow = true, paidOff = "2027-03", interest = "180.73", balance = "4000.00")),
        )
    }
}
