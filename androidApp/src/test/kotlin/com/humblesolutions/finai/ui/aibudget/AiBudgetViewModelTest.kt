package com.humblesolutions.finai.ui.aibudget

import com.humblesolutions.finai.model.AiAccept
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.AiProposalLine
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.usecase.AiBudgetBlock
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
class AiBudgetViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val proposal = AiProposal(
        month = "2026-10-01",
        currency = "CAD",
        income = "5000.00",
        lines = listOf(
            AiProposalLine("cat-rent", "rent", "Rent & mortgage", "need", "1800.00", "1800.00", null),
            AiProposalLine("cat-groceries", "groceries", "Groceries", "need", "500.00", "0.00", "Food at home."),
            AiProposalLine("cat-debt", "debt_payment", "Debt payment", "debt", "620.00", "620.00", null),
        ),
        savings = "2080.00",
        attemptsLeft = 2,
    )

    private class FakeBudgets(
        var stored: AiProposal? = null,
        var made: AiProposal? = null,
        var failSuggest: ApiException? = null,
    ) : BudgetRepository {
        var suggestions = 0
        var accepted: AiAccept? = null

        override suspend fun setupSuggestion(): AiProposal = stored ?: throw ApiException.NotFound()

        override suspend fun suggestSetup(): AiProposal {
            suggestions++
            failSuggest?.let { throw it }
            return made ?: throw ApiException.AiUnavailable()
        }

        override suspend fun acceptSetup(accept: AiAccept): Budget {
            accepted = accept
            return Budget()
        }

        override suspend fun get(month: String): Budget = Budget()
        override suspend fun setLine(month: String, categoryId: String, amount: String): Budget = Budget()
        override suspend fun resetLine(month: String, categoryId: String): Budget = Budget()
        override suspend fun review(month: String): Budget = Budget()
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

    private fun model(budgets: FakeBudgets, consent: FakeConsent = FakeConsent()) = AiBudgetViewModel().also {
        it.bind("user-1") { AiBudgetRepositories(budgets, consent) }
    }

    @Test
    fun a_suggestion_already_made_this_month_is_shown_without_asking_again() = runTest {
        val budgets = FakeBudgets(stored = proposal)
        val state = model(budgets).uiState.value

        assertEquals(proposal, state.proposal)
        assertEquals(0, budgets.suggestions, "asking costs money")
        assertTrue(state.canStart)
    }

    @Test
    fun with_none_stored_one_is_asked_for() = runTest {
        val budgets = FakeBudgets(made = proposal)
        val state = model(budgets).uiState.value

        assertEquals(1, budgets.suggestions)
        assertEquals("500.00", state.draft.lines[1].amount)
    }

    @Test
    fun without_consent_the_policy_is_shown_and_agreeing_asks_again() = runTest {
        val budgets = FakeBudgets(made = proposal, failSuggest = ApiException.ConsentRequired("ai-v2"))
        val consent = FakeConsent()
        val model = model(budgets, consent)
        assertTrue(model.uiState.value.needsConsent)

        budgets.failSuggest = null
        model.agree()
        assertNull(consent.agreed, "nothing is agreed to before the box is ticked")
        model.onConsentTicked(true)
        model.agree()

        assertEquals("ai-v2", consent.agreed)
        assertFalse(model.uiState.value.needsConsent)
        assertNotNull(model.uiState.value.proposal)
    }

    @Test
    fun a_line_raised_past_income_cannot_be_started() = runTest {
        val model = model(FakeBudgets(stored = proposal))

        model.onAmount(1, "3000")

        assertEquals(AiBudgetBlock.OVER_INCOME, model.uiState.value.block)
        assertFalse(model.uiState.value.canStart)
        assertEquals("Over your income by $2,500.00", model.uiState.value.leftLabel)
    }

    @Test
    fun start_sends_the_edited_lines_without_debt_then_finishes() = runTest {
        val budgets = FakeBudgets(stored = proposal)
        val model = model(budgets)
        var done = false

        model.onAmount(1, "450")
        model.onSavings("2130")
        model.start { done = true }

        assertTrue(done)
        val sent = assertNotNull(budgets.accepted)
        assertEquals(listOf("cat-rent" to "1800.00", "cat-groceries" to "450.00"), sent.lines.map { it.categoryId to it.amount })
        assertEquals("2130.00", sent.savings)
    }

    @Test
    fun budgets_off_means_nothing_to_suggest() = runTest {
        val budgets = FakeBudgets(failSuggest = ApiException.FeatureUnavailable("auto_budget", FeatureReason.fromWire("disabled")))
        val state = model(budgets).uiState.value

        assertTrue(state.unavailable)
        assertNull(state.proposal)
    }
}
