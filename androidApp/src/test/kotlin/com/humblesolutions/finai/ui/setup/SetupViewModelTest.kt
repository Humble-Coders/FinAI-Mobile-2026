package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.FinancialSetupRepository
import com.humblesolutions.finai.usecase.SetupStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wizard's model, driven through [SetupViewModel.bind] itself with fake
 * repositories: who the wizard belongs to, and how Continue saves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val alice = FinancialSetup(currency = "CAD", income = "4000.00", monthlyExpense = "2500.00")
    private val bob = FinancialSetup(currency = "CAD")

    // ── Who the wizard belongs to ───────────────────────────────────────

    @Test
    fun `binding loads that user's saved figures`() {
        val model = SetupViewModel()
        model.bind("alice") { repositories(FakeSetup(alice)) }

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals("4000.00", state.draft.income)
        assertEquals("en-CA", state.locale)
    }

    @Test
    fun `a different user is loaded fresh and the last one's clients are closed`() {
        val model = SetupViewModel()
        val alicesClient = FakeSetup(alice)
        model.bind("alice") { repositories(alicesClient) }
        model.onIncomeChange("9999")

        model.bind("bob") { repositories(FakeSetup(bob)) }

        assertEquals("", model.uiState.value.draft.income)
        assertTrue(alicesClient.closed)
    }

    @Test
    fun `the same user binding again does not reload over what was typed`() {
        val model = SetupViewModel()
        val client = FakeSetup(alice)
        var built = 0
        model.bind("alice") { built++; repositories(client) }
        model.onIncomeChange("9999")

        // What a rotation does: the recreated screen binds again.
        model.bind("alice") { built++; repositories(FakeSetup(bob)) }

        assertEquals("9999", model.uiState.value.draft.income)
        assertEquals(1, built)
        assertEquals(1, client.gets)
    }

    @Test
    fun `a load that answers after someone else is bound is ignored`() {
        val model = SetupViewModel()
        val late = CompletableDeferred<Unit>()
        model.bind("alice") { repositories(FakeSetup(alice, getGate = late)) }

        model.bind("bob") { repositories(FakeSetup(bob)) }
        late.complete(Unit)

        assertEquals("", model.uiState.value.draft.income)
        assertFalse(model.uiState.value.loading)
    }

    @Test
    fun `an unknown user gets an error rather than a wait that never ends`() {
        val model = SetupViewModel()
        model.bind("alice") { repositories(FakeSetup(alice)) }

        model.bind("") { error("nothing should be built for an unknown user") }

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(Strings.error_unexpected, state.errorKey)
        assertEquals("", state.draft.income)
    }

    // ── Continue and saving ─────────────────────────────────────────────

    @Test
    fun `continue moves to the next step at once and saves behind it`() {
        val model = SetupViewModel()
        val gate = CompletableDeferred<Unit>()
        val client = FakeSetup(bob, saveGate = gate)
        model.bind("bob") { repositories(client) }
        model.onIncomeChange("4000")

        model.continueStep(onFinished = {})

        // The step has moved while the save is still out.
        assertEquals(SetupStep.EXPENSES, model.uiState.value.step)
        assertTrue(model.uiState.value.syncing)
        assertFalse(model.uiState.value.busy)

        gate.complete(Unit)
        assertFalse(model.uiState.value.syncing)
        assertEquals(1, client.saves)
    }

    @Test
    fun `nothing the server already holds is sent again`() {
        val model = SetupViewModel()
        val client = FakeSetup(alice)
        model.bind("alice") { repositories(client) }
        // Both figures are saved, so the wizard reopens on step 3; walk back.
        model.goTo(SetupStep.INCOME)

        model.continueStep(onFinished = {})

        assertEquals(SetupStep.EXPENSES, model.uiState.value.step)
        assertFalse(model.uiState.value.syncing)
        assertEquals(0, client.saves)
    }

    @Test
    fun `a failed save keeps what was typed and says so`() {
        val model = SetupViewModel()
        model.bind("bob") { repositories(FakeSetup(bob, failSave = true)) }
        model.onIncomeChange("4000")

        model.continueStep(onFinished = {})

        val state = model.uiState.value
        assertEquals("4000", state.draft.income)
        assertFalse(state.syncing)
        assertNotNull(state.errorKey)
    }

    @Test
    fun `completing waits for the save and only then hands back`() {
        val model = SetupViewModel()
        val gate = CompletableDeferred<Unit>()
        model.bind("alice") { repositories(FakeSetup(alice, saveGate = gate)) }
        model.onIncomeChange("5000")
        model.goTo(SetupStep.PORTFOLIO)
        var finished = false

        model.continueStep(onFinished = { finished = true })
        assertTrue(model.uiState.value.busy)
        assertFalse(finished)

        gate.complete(Unit)
        assertTrue(finished)
        assertFalse(model.uiState.value.busy)
    }

    @Test
    fun `a save that answers after someone else is bound neither writes nor hands back`() {
        val model = SetupViewModel()
        val gate = CompletableDeferred<Unit>()
        model.bind("alice") { repositories(FakeSetup(alice, saveGate = gate)) }
        model.onIncomeChange("5000")
        model.goTo(SetupStep.PORTFOLIO)
        var finished = false
        model.continueStep(onFinished = { finished = true })

        model.bind("bob") { repositories(FakeSetup(bob)) }
        gate.complete(Unit)

        val state = model.uiState.value
        assertFalse(finished)
        assertEquals(SetupStep.INCOME, state.step)
        assertEquals("", state.draft.income)
        assertFalse(state.busy)
        assertFalse(state.syncing)
    }

    @Test
    fun `cancelling says setup is required and going back opens step one with the figures kept`() {
        val model = SetupViewModel()
        model.bind("alice") { repositories(FakeSetup(alice)) }
        model.goTo(SetupStep.EXPENSES)

        model.cancel()
        assertTrue(model.uiState.value.cancelled)

        model.resume()
        val state = model.uiState.value
        assertFalse(state.cancelled)
        assertEquals(SetupStep.INCOME, state.step)
        assertEquals("4000.00", state.draft.income)
    }

    private fun repositories(setup: FakeSetup) = SetupRepositories(setup, FakeCapabilities())

    private class FakeSetup(
        private val stored: FinancialSetup,
        private val getGate: CompletableDeferred<Unit>? = null,
        private val saveGate: CompletableDeferred<Unit>? = null,
        private val failSave: Boolean = false,
    ) : FinancialSetupRepository {
        var gets = 0
        var saves = 0
        var closed = false

        override suspend fun get(): FinancialSetup {
            gets++
            getGate?.await()
            return stored
        }

        override suspend fun save(setup: FinancialSetup): FinancialSetup {
            saves++
            saveGate?.await()
            if (failSave) throw ApiException.Network(RuntimeException("offline"))
            return setup.copy(currency = stored.currency)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeCapabilities : CapabilitiesRepository {
        override suspend fun fetch() = Capabilities(currency = "CAD", locale = "en-CA")
        override fun close() = Unit
    }
}
