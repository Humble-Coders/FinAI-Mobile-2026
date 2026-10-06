package com.humblesolutions.finai.ui.dashboard

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.BudgetStatus
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.CategorySpend
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.DashboardScore
import com.humblesolutions.finai.model.Feature
import com.humblesolutions.finai.model.HealthScore
import com.humblesolutions.finai.model.ScoreComponent
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.DashboardRepository
import com.humblesolutions.finai.repository.HealthScoreRepository
import com.humblesolutions.finai.usecase.HomeInsights
import com.humblesolutions.finai.util.BudgetChanged
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

/** Home's score, budget, spending and breakdown (#46), as the view model drives them. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardInsightsTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val scored = Dashboard(
        currency = "CAD",
        net = "100.00",
        healthScore = DashboardScore(status = BudgetStatus.READY, score = 72, previousScore = 68),
        spendByCategory = listOf(CategorySpend("g", "groceries", "Groceries", "150.01")),
    )

    private class FakeDashboard(val answer: Dashboard) : DashboardRepository {
        var reads = 0
        override suspend fun read(month: String?): Dashboard {
            reads++
            return answer
        }
        override fun close() = Unit
    }

    private class FakeCapabilities(var answer: Capabilities?) : CapabilitiesRepository {
        override suspend fun fetch(): Capabilities = answer ?: throw ApiException.NotFound()
        override fun close() = Unit
    }

    private class FakeHealth(var answer: HealthScore? = null) : HealthScoreRepository {
        var reads = 0
        override suspend fun current(): HealthScore {
            reads++
            return answer ?: throw ApiException.NotFound()
        }
        override fun close() = Unit
    }

    private val scoreOn = Capabilities(
        locale = "en",
        features = mapOf(HomeInsights.SCORE_FEATURE to Feature(enabled = true)),
    )

    private fun model(
        dashboard: FakeDashboard = FakeDashboard(scored),
        capabilities: FakeCapabilities = FakeCapabilities(scoreOn),
        health: FakeHealth = FakeHealth(),
    ): DashboardViewModel {
        val model = DashboardViewModel()
        model.bind("alice") {
            DashboardRepositories(dashboard, capabilities, healthScore = health)
        }
        return model
    }

    @Test
    fun the_score_card_is_drawn_once_capabilities_say_so() {
        val model = model()

        assertEquals("72", model.uiState.value.sections.score?.score)
        assertEquals("+4 since last month", model.uiState.value.sections.score?.change)
    }

    @Test
    fun capabilities_that_say_off_draw_no_score_card() {
        val model = model(capabilities = FakeCapabilities(Capabilities(locale = "en")))

        assertNull(model.uiState.value.sections.score)
        assertNotNull(model.uiState.value.sections.spending, "spending is never gated")
    }

    @Test
    fun a_failed_capabilities_read_keeps_the_payload_it_had() {
        val capabilities = FakeCapabilities(scoreOn)
        val model = model(capabilities = capabilities)
        capabilities.answer = null

        model.load(refresh = true)

        assertNotNull(model.uiState.value.capabilities)
        assertNotNull(model.uiState.value.sections.score)
    }

    @Test
    fun the_breakdown_is_read_only_when_opened() {
        val health = FakeHealth(
            HealthScore(
                status = BudgetStatus.READY,
                score = 72,
                formulaVersion = "v1",
                components = listOf(ScoreComponent("savings_consistency", 80, "40.00", true)),
            ),
        )
        val model = model(health = health)
        assertEquals(0, health.reads)

        model.openBreakdown()

        assertEquals(1, health.reads)
        assertTrue(model.uiState.value.breakdownOpen)
        assertEquals("Saving regularly", model.uiState.value.breakdownView?.rows?.single()?.title)
        model.closeBreakdown()
        assertFalse(model.uiState.value.breakdownOpen)
    }

    @Test
    fun a_failed_breakdown_says_so_and_can_be_retried() {
        val health = FakeHealth()
        val model = model(health = health)

        model.openBreakdown()

        assertTrue(model.uiState.value.breakdownFailed)
        assertFalse(model.uiState.value.breakdownLoading)
        health.answer = HealthScore(status = BudgetStatus.READY, score = 50)
        model.retryBreakdown()
        assertFalse(model.uiState.value.breakdownFailed)
        assertEquals("50", model.uiState.value.breakdownView?.score)
    }

    @Test
    fun a_budget_change_re_reads_the_month() {
        val dashboard = FakeDashboard(scored)
        model(dashboard = dashboard)
        val before = dashboard.reads

        BudgetChanged.announce()

        assertEquals(before + 1, dashboard.reads)
    }

    @Test
    fun show_all_toggles_where_it_went() {
        val model = model()

        model.toggleSpending()
        assertTrue(model.uiState.value.spendingExpanded)
        model.toggleSpending()
        assertFalse(model.uiState.value.spendingExpanded)
    }
}
