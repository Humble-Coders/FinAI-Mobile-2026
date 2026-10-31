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
import com.humblesolutions.finai.model.PlanCategory
import com.humblesolutions.finai.model.PlanDebt
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanEmergencyShare
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanRow
import com.humblesolutions.finai.model.PlanRowLimit
import com.humblesolutions.finai.model.PlanSplit
import com.humblesolutions.finai.model.PlanSplitEdit
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.model.PlanWeight
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.DebtsRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.BalanceBlock
import com.humblesolutions.finai.usecase.PlanChoiceBlock
import com.humblesolutions.finai.usecase.RowEditBlock
import com.humblesolutions.finai.util.BudgetChanged
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
import kotlin.test.assertNotNull
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
        val edits = mutableListOf<PlanSplitEdit>()
        var failRead: ApiException? = null
        var failSave: ApiException? = null

        /** What `PUT /plan/split` answers with: the whole plan, rebalanced. */
        var edited: Plan? = null

        override suspend fun plan(month: String?): Plan {
            reads++
            failRead?.let { throw it }
            return plan
        }

        override suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences {
            saved += choices
            failSave?.let { throw it }
            plan = plan.copy(preferences = plan.preferences.copy(saved = true, strategy = choices.strategy, weights = choices.weights ?: plan.preferences.weights))
            return plan.preferences
        }

        override suspend fun editSplit(edit: PlanSplitEdit): Plan {
            edits += edit
            failSave?.let { throw it }
            plan = edited ?: plan
            return plan
        }

        override suspend fun disclaimer(): Terms? = Terms(version = "ca-v1", body = "Educational guidance, not financial advice.")

        override suspend fun diagnostics(): PlanDiagnostics = PlanDiagnostics()

        override suspend fun runDiagnostic(kind: String, savingsTarget: String?, savingsNow: String?, monthlySavings: String?): PlanDiagnostic = throw UnsupportedOperationException()

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

    // ── A row of the split ──────────────────────────────────────────────

    @Test
    fun the_split_reads_as_a_row_each_with_the_surplus_above() = runTest {
        val state = model().uiState.value

        assertEquals(listOf(PlanRow.EMERGENCY_FUND, PlanRow.DEBT, PlanRow.SAVE_OR_INVEST), state.splitRows.map { it.row })
        assertNotNull(state.surplus?.amount, "money to split: the figure, not the headline")
        assertEquals(2, state.surplus?.segments?.size, "a part with nothing in it has no segment")
        assertEquals(listOf(1, 2, 3, 4), state.weightRows.map { it.priority })
    }

    @Test
    fun applying_a_row_edit_sends_apply_and_replaces_the_plan() = runTest {
        val fakes = Fakes()
        val rebalanced = plan().copy(split = plan().split.copy(extraDebt = "500.00", emergencyFund = fund("700.00"), edited = true))
        fakes.plan.edited = rebalanced
        val model = model(fakes)
        val plans = mutableListOf<Unit>()
        val budgets = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { PlanChanged.events.collect { plans += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { BudgetChanged.events.collect { budgets += it } }
        val reads = fakes.plan.reads

        model.startRowEdit(model.uiState.value.splitRows.first { it.row == PlanRow.DEBT })
        assertEquals(RowEditBlock.UNCHANGED, model.uiState.value.rowBlock)
        model.onRowAmount("500")
        model.requestRowSave()
        assertTrue(model.uiState.value.askingApply)
        assertEquals("Update Extra debt payment too?", model.uiState.value.applyQuestion?.title)
        model.saveRow(apply = true)

        assertEquals(PlanSplitEdit(PlanRow.DEBT, null, "500.00", apply = true), fakes.plan.edits.single())
        val state = model.uiState.value
        assertEquals(rebalanced, state.plan)
        assertNull(state.rowDraft)
        assertFalse(state.askingApply)
        assertEquals(reads, fakes.plan.reads, "the answer is the plan: no read after it")
        assertEquals(1, plans.size)
        assertEquals(1, budgets.size, "the Budget's Debt line moved")
    }

    @Test
    fun only_changing_the_plan_sends_no_apply_and_leaves_goals_alone() = runTest {
        val fakes = Fakes()
        val model = model(fakes)
        val goals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { GoalsChanged.events.collect { goals += it } }

        model.startRowEdit(model.uiState.value.splitRows.first { it.row == PlanRow.EMERGENCY_FUND })
        model.onRowAmount("700")
        model.requestRowSave()
        model.saveRow(apply = false)

        assertEquals(PlanSplitEdit(PlanRow.EMERGENCY_FUND, null, "700.00", apply = false), fakes.plan.edits.single())
        assertTrue(goals.isEmpty())
    }

    @Test
    fun a_row_over_its_limit_is_never_sent() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.startRowEdit(model.uiState.value.splitRows.first { it.row == PlanRow.EMERGENCY_FUND })
        model.onRowAmount("900")
        model.requestRowSave()
        model.saveRow(apply = true)

        assertEquals(RowEditBlock.OVER_LIMIT, model.uiState.value.rowBlock)
        assertFalse(model.uiState.value.askingApply)
        assertTrue(fakes.plan.edits.isEmpty())
    }

    @Test
    fun a_refused_row_keeps_the_editor_open_with_why() = runTest {
        val fakes = Fakes()
        fakes.plan.failSave = ApiException.Server(503)
        val model = model(fakes)

        model.startRowEdit(model.uiState.value.splitRows.first { it.row == PlanRow.DEBT })
        model.onRowAmount("450")
        model.requestRowSave()
        model.saveRow(apply = false)

        val state = model.uiState.value
        assertTrue(state.rowDraft != null)
        assertEquals(ApiException.Server(503).messageKey, state.rowErrorKey)
    }

    @Test
    fun a_row_being_changed_survives_the_process_being_reclaimed() = runTest {
        val saved = SavedStateHandle()
        val first = model(saved = saved)
        first.startRowEdit(first.uiState.value.splitRows.first { it.row == PlanRow.DEBT })
        first.onRowAmount("450")
        first.requestRowSave()

        val restored = model(saved = saved).uiState.value

        assertEquals(PlanRow.DEBT, restored.rowDraft?.row)
        assertEquals("450", restored.rowDraft?.amount)
        assertEquals("400.00", restored.rowDraft?.original)
        assertTrue(restored.askingApply)
    }

    // ── The choices ─────────────────────────────────────────────────────

    @Test
    fun saving_the_choices_sends_the_weights_in_order_and_reads_the_plan_again() = runTest {
        val fakes = Fakes()
        val model = model(fakes)
        val plans = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { PlanChanged.events.collect { plans += it } }
        val reads = fakes.plan.reads

        model.startChoices()
        model.moveChoice(PlanCategory.DEBT, up = true)
        model.stepChoice(PlanCategory.DEBT, up = true)
        model.onChoicePercent(PlanCategory.SAVE_OR_INVEST, "5")
        model.saveChoices()

        val sent = fakes.plan.saved.single()
        assertEquals(PlanStrategy.AVALANCHE, sent.strategy, "the strategy is kept")
        assertEquals(
            listOf(
                PlanWeight(PlanCategory.DEBT, "45.00"),
                PlanWeight(PlanCategory.EMERGENCY_FUND, "40.00"),
                PlanWeight(PlanCategory.GOALS, "10.00"),
                PlanWeight(PlanCategory.SAVE_OR_INVEST, "5.00"),
            ),
            sent.weights,
        )
        assertFalse(model.uiState.value.choosing)
        assertEquals(1, plans.size)
        assertEquals(reads + 1, fakes.plan.reads, "the server reruns the split: read it again")
    }

    @Test
    fun shares_that_do_not_add_up_to_100_never_leave() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.startChoices()
        model.stepChoice(PlanCategory.GOALS, up = true)
        model.saveChoices()

        assertEquals(PlanChoiceBlock.NOT_100, model.uiState.value.choicesBlock)
        assertEquals("The shares add up to 105%. They need to add up to 100%.", model.uiState.value.choicesNotice)
        assertFalse(model.uiState.value.canSaveChoices)
        assertTrue(fakes.plan.saved.isEmpty())
    }

    @Test
    fun a_refused_save_keeps_the_sheet_open_with_why() = runTest {
        val fakes = Fakes()
        fakes.plan.failSave = ApiException.Server(503)
        val model = model(fakes)

        model.startChoices()
        model.saveChoices()

        val state = model.uiState.value
        assertTrue(state.choosing)
        assertEquals(ApiException.Server(503).messageKey, state.choicesErrorKey)
    }

    @Test
    fun the_choices_being_edited_survive_the_process_being_reclaimed() = runTest {
        val saved = SavedStateHandle()
        val first = model(saved = saved)
        first.startChoices()
        first.moveChoice(PlanCategory.GOALS, up = true)
        first.onChoicePercent(PlanCategory.GOALS, "12")

        val restored = model(saved = saved).uiState.value

        assertTrue(restored.choosing)
        assertEquals(listOf(PlanCategory.EMERGENCY_FUND, PlanCategory.GOALS, PlanCategory.DEBT, PlanCategory.SAVE_OR_INVEST), restored.weightsDraft.categories)
        assertEquals("12", restored.weightsDraft.percents[PlanCategory.GOALS])
    }

    @Test
    fun the_emergency_suggestion_shows_only_without_a_fund() = runTest {
        assertNull(model().uiState.value.emergencySuggestion)

        val without = plan().copy(preferences = preferences().copy(hasEmergencyFund = false))
        val state = model(Fakes(plan = FakePlan(without))).uiState.value
        assertEquals("Add an emergency fund", state.emergencySuggestion?.first)
    }

    @Test
    fun the_switch_saves_only_the_strategy() = runTest {
        val fakes = Fakes()
        val model = model(fakes)

        model.chooseStrategy(PlanStrategy.SNOWBALL)

        val sent = fakes.plan.saved.single()
        assertEquals(PlanStrategy.SNOWBALL, sent.strategy)
        assertNull(sent.weights, "the choices are kept")
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
            rateLinePercent = "6.00",
            strategy = PlanStrategy.AVALANCHE,
            weights = listOf(
                PlanWeight(PlanCategory.EMERGENCY_FUND, "40.00"),
                PlanWeight(PlanCategory.DEBT, "40.00"),
                PlanWeight(PlanCategory.GOALS, "10.00"),
                PlanWeight(PlanCategory.SAVE_OR_INVEST, "10.00"),
            ),
            hasEmergencyFund = true,
        )

        fun fund(amount: String) = PlanEmergencyShare(goalId = "g-fund", name = "Emergency fund", amount = amount, stillNeeded = "3000.00")

        fun plan() = Plan(
            month = "2026-11",
            currency = "CAD",
            status = PlanStatus.READY,
            leftover = "1200.00",
            preferences = preferences(),
            split = PlanSplit(
                extraDebt = "400.00",
                saveOrInvest = "0.00",
                emergencyFund = fund("800.00"),
                limits = listOf(PlanRowLimit(row = "emergency_fund", most = "800.00")),
            ),
            debts = listOf(PlanDebt(debtId = "d-visa", name = "Visa", ratePercent = "20.99", order = 1, getsExtraNow = true, paidOff = "2027-03", interest = "180.73", balance = "4000.00")),
        )
    }
}
