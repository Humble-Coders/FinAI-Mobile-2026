package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.i18n.Strings
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
import kotlin.test.assertTrue

/**
 * Who the wizard belongs to, driven through [SetupViewModel.bind] itself.
 *
 * The model is scoped to the activity so a rotation cannot lose a half-typed
 * figure — which means it also outlives a sign-out. These hold the other half of
 * that bargain: the next account must never see, or save, what the last one
 * typed, even when a reply for the last one arrives late.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val alice = FinancialSetup(currency = "CAD", income = "4000.00", monthlyExpense = "2500.00")
    private val bob = FinancialSetup(currency = "CAD")

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
        assertEquals("", model.uiState.value.draft.monthlyExpense)
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
    fun `a save that answers after someone else is bound neither writes nor moves on`() {
        val model = SetupViewModel()
        val late = CompletableDeferred<Unit>()
        model.bind("alice") { repositories(FakeSetup(alice, saveGate = late)) }
        model.continueStep(onFinished = {})
        assertTrue(model.uiState.value.busy)

        model.bind("bob") { repositories(FakeSetup(bob)) }
        late.complete(Unit)

        val state = model.uiState.value
        assertEquals(SetupStep.INCOME, state.step)
        assertEquals("", state.draft.income)
        assertFalse(state.busy)
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

    private fun repositories(setup: FakeSetup) = SetupRepositories(setup, FakeCapabilities())

    private class FakeSetup(
        private val stored: FinancialSetup,
        private val getGate: CompletableDeferred<Unit>? = null,
        private val saveGate: CompletableDeferred<Unit>? = null,
    ) : FinancialSetupRepository {
        var gets = 0
        var closed = false

        override suspend fun get(): FinancialSetup {
            gets++
            getGate?.await()
            return stored
        }

        override suspend fun save(setup: FinancialSetup): FinancialSetup {
            saveGate?.await()
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
