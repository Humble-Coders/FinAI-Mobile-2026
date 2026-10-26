package com.humblesolutions.finai.ui.plan

import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.DiagnosticBlock
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
class DiagnosticViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val answer = PlanDiagnostic(
        kind = "allocation",
        currency = "CAD",
        leftover = "1562.00",
        savingsTarget = "1000.00",
        savingsNow = "400.00",
        savingsGap = "600.00",
        toSavings = "600.00",
        toDebt = "962.00",
        debtFirst = "Visa",
        headline = "Fill your savings first.",
    )

    private class FakePlan(var failRun: ApiException? = null, val answer: PlanDiagnostic) : PlanRepository {
        val runs = mutableListOf<Triple<String, String, String>>()
        var listed = PlanDiagnostics(currency = "CAD", savingsTarget = "1000.00", savingsNow = "400.00", runsLeft = mapOf("allocation" to 4, "invest" to 4))

        override suspend fun diagnostics(): PlanDiagnostics = listed

        override suspend fun runDiagnostic(kind: String, savingsTarget: String, savingsNow: String): PlanDiagnostic {
            runs += Triple(kind, savingsTarget, savingsNow)
            failRun?.let { throw it }
            listed = listed.copy(allocation = answer, runsLeft = mapOf("allocation" to 3, "invest" to 4))
            return answer
        }

        override suspend fun plan(month: String?): Plan = throw UnsupportedOperationException()
        override suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences = throw UnsupportedOperationException()
        override suspend fun disclaimer(): Terms? = null
        override fun close() = Unit
    }

    private class FakeConsent : AiConsentRepository {
        var agreed: String? = null
        override suspend fun policy() = AiPolicy(version = "ai-v2", body = "What is sent, and why.")
        override suspend fun consent(version: String) {
            agreed = version
        }
        override suspend fun status() = AiConsentStatus(consented = agreed != null, version = "ai-v2")
        override fun close() = Unit
    }

    private fun model(plan: FakePlan, consent: FakeConsent = FakeConsent()) = DiagnosticViewModel().also {
        it.bind("user-1", PlanDiagnostics.ALLOCATION) { DiagnosticRepositories(plan, consent) }
    }

    @Test
    fun the_form_starts_from_the_figures_last_used() = runTest {
        val state = model(FakePlan(answer = answer)).uiState.value

        assertEquals("1000.00", state.savingsTarget)
        assertEquals("400.00", state.savingsNow)
        assertEquals("4 fresh runs left this month", state.runsLeftLabel)
        assertNull(state.result)
    }

    @Test
    fun running_shows_the_answer_and_the_runs_left() = runTest {
        val plan = FakePlan(answer = answer)
        val model = model(plan)

        model.onSavingsTarget("1500")
        model.run()

        assertEquals(Triple("allocation", "1500.00", "400.00"), plan.runs.single())
        val state = model.uiState.value
        assertEquals("Fill your savings first.", state.result?.headline)
        assertEquals("Extra on Visa", state.rows[2].label)
        assertEquals("3 fresh runs left this month", state.runsLeftLabel)
    }

    @Test
    fun an_amount_that_is_not_one_cannot_be_run() = runTest {
        val plan = FakePlan(answer = answer)
        val model = model(plan)

        model.onSavingsNow("lots")
        model.run()

        assertEquals(DiagnosticBlock.AMOUNT_INVALID, model.uiState.value.block)
        assertFalse(model.uiState.value.canRun)
        assertTrue(plan.runs.isEmpty())
    }

    @Test
    fun without_consent_the_policy_is_shown_and_agreeing_runs() = runTest {
        val plan = FakePlan(failRun = ApiException.ConsentRequired("ai-v2"), answer = answer)
        val consent = FakeConsent()
        val model = model(plan, consent)

        model.run()
        assertNotNull(model.uiState.value.policy)
        plan.failRun = null
        model.onConsentTicked(true)
        model.agree()

        assertEquals("ai-v2", consent.agreed)
        assertNull(model.uiState.value.policy)
        assertEquals(2, plan.runs.size)
        assertNotNull(model.uiState.value.result)
    }

    @Test
    fun past_the_monthly_runs_the_reason_is_said() = runTest {
        val model = model(FakePlan(failRun = ApiException.DiagnosticLimitReached(), answer = answer))

        model.run()

        assertEquals(ApiException.DiagnosticLimitReached().messageKey, model.uiState.value.errorKey)
    }
}
