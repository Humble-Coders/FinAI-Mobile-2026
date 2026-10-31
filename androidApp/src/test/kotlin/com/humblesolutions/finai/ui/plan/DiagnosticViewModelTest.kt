package com.humblesolutions.finai.ui.plan

import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanCategory
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanSplitEdit
import com.humblesolutions.finai.model.PlanWeight
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.PlanRepository
import com.humblesolutions.finai.usecase.DiagnosticBlock
import com.humblesolutions.finai.util.BudgetChanged
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

    /** One run as sent: kind, savings target, savings now, monthly saving. */
    private data class Run(val kind: String, val target: String?, val now: String?, val monthly: String?)

    private class FakePlan(var failRun: ApiException? = null, val answer: PlanDiagnostic) : PlanRepository {
        val runs = mutableListOf<Run>()
        var listed = PlanDiagnostics(
            currency = "CAD",
            savingsTarget = "1000.00",
            savingsNow = "400.00",
            runsLeft = mapOf("allocation" to 4, "invest" to 4),
            monthlySavings = "300.00",
        )
        var preferences = PlanPreferences(
            weights = listOf(
                PlanWeight(PlanCategory.EMERGENCY_FUND, "30.00"),
                PlanWeight(PlanCategory.DEBT, "40.00"),
                PlanWeight(PlanCategory.GOALS, "20.00"),
                PlanWeight(PlanCategory.SAVE_OR_INVEST, "10.00"),
            ),
        )

        override suspend fun diagnostics(): PlanDiagnostics = listed

        override suspend fun runDiagnostic(kind: String, savingsTarget: String?, savingsNow: String?, monthlySavings: String?): PlanDiagnostic {
            runs += Run(kind, savingsTarget, savingsNow, monthlySavings)
            failRun?.let { throw it }
            listed = listed.copy(allocation = answer, runsLeft = mapOf("allocation" to 3, "invest" to 4))
            return answer
        }

        val saved = mutableListOf<PlanPreferencesToSave>()

        override suspend fun plan(month: String?): Plan = Plan(preferences = preferences)

        override suspend fun savePreferences(choices: PlanPreferencesToSave): PlanPreferences {
            saved += choices
            return preferences.copy(saved = true, weights = choices.weights ?: preferences.weights)
        }

        override suspend fun editSplit(edit: PlanSplitEdit): Plan = error("not used")
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

    private fun model(plan: FakePlan, consent: FakeConsent = FakeConsent(), kind: String = PlanDiagnostics.ALLOCATION) = DiagnosticViewModel().also {
        it.bind("user-1", kind) { DiagnosticRepositories(plan, consent) }
    }

    @Test
    fun the_form_starts_from_the_budgets_savings_target() = runTest {
        val state = model(FakePlan(answer = answer)).uiState.value

        assertEquals("300.00", state.monthlySavings)
        assertEquals("How much do you save each month?", state.monthlyLabel)
        assertEquals("4 fresh runs left this month", state.runsLeftLabel)
        assertNull(state.result)
    }

    @Test
    fun the_allocation_sends_only_the_monthly_saving_and_tells_the_budget() = runTest {
        val plan = FakePlan(answer = answer)
        val model = model(plan)
        val budgets = mutableListOf<Unit>()
        val plans = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { BudgetChanged.events.collect { budgets += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { PlanChanged.events.collect { plans += it } }

        model.onMonthlySavings("1,500")
        model.run()

        assertEquals(Run("allocation", null, null, "1500.00"), plan.runs.single())
        val state = model.uiState.value
        assertEquals("Fill your savings first.", state.result?.headline)
        assertEquals("3 fresh runs left this month", state.runsLeftLabel)
        assertEquals(1, budgets.size, "the monthly saving is the Budget's savings target now")
        assertEquals(1, plans.size)
    }

    @Test
    fun a_blank_monthly_saving_leaves_it_to_the_budget() = runTest {
        val plan = FakePlan(answer = answer)
        val model = model(plan)

        model.onMonthlySavings("")
        model.run()

        assertEquals(Run("allocation", null, null, null), plan.runs.single())
    }

    @Test
    fun the_invest_diagnostic_keeps_its_two_figures() = runTest {
        val plan = FakePlan(answer = answer.copy(kind = "invest"))
        val model = model(plan, kind = PlanDiagnostics.INVEST)

        model.onSavingsTarget("1500")
        model.run()

        assertEquals(Run("invest", "1500.00", "400.00", null), plan.runs.single())
    }

    @Test
    fun an_amount_that_is_not_one_cannot_be_run() = runTest {
        val plan = FakePlan(answer = answer)
        val model = model(plan)

        model.onMonthlySavings("lots")
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

    @Test
    fun pay_debt_before_goals_saves_the_debt_first_choices() = runTest {
        val recommended = answer.copy(
            toGoals = "120.00",
            goalSharePercent = "20.00",
            altAvailable = true,
            altToDebt = "1082.00",
            recommend = "debt_first",
            recommendWhy = "The card costs more than the trip can wait.",
        )
        val plan = FakePlan(answer = recommended)
        val model = model(plan)
        model.run()
        assertTrue(model.uiState.value.canApplyDebtFirst)
        assertEquals("AI suggests: pay debt before your goals", model.uiState.value.recommendation)

        model.applyDebtFirst()

        val sent = plan.saved.single()
        assertEquals(
            listOf(
                PlanWeight(PlanCategory.DEBT, "60.00"),
                PlanWeight(PlanCategory.EMERGENCY_FUND, "30.00"),
                PlanWeight(PlanCategory.SAVE_OR_INVEST, "10.00"),
                PlanWeight(PlanCategory.GOALS, "0.00"),
            ),
            sent.weights,
            "debt to the top with the goals' share, the goals given none",
        )
        assertTrue(model.uiState.value.applied)
        assertFalse(model.uiState.value.canApplyDebtFirst, "offered once")
    }

    @Test
    fun without_a_debt_choice_pay_debt_before_goals_saves_nothing() = runTest {
        val recommended = answer.copy(goalSharePercent = "20.00", altAvailable = true, recommend = "debt_first")
        val plan = FakePlan(answer = recommended)
        plan.preferences = PlanPreferences(weights = listOf(PlanWeight(PlanCategory.GOALS, "50.00"), PlanWeight(PlanCategory.SAVE_OR_INVEST, "50.00")))
        val model = model(plan)
        model.run()

        model.applyDebtFirst()

        assertTrue(plan.saved.isEmpty())
        assertFalse(model.uiState.value.applying)
        assertFalse(model.uiState.value.applied)
    }
}
