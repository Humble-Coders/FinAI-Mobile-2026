package com.humblesolutions.finai.ui.aibudget

import com.humblesolutions.finai.model.AiAccept
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.AiProposalLine
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.model.SpendingCheck
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AiBudgetDraftStore
import com.humblesolutions.finai.repository.BudgetRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.LegalRepository
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
        override suspend fun setLine(month: String, categoryId: String, amount: String, thisMonthOnly: Boolean): Budget = Budget()
        override suspend fun spendingCheck(month: String): SpendingCheck = SpendingCheck()
        override suspend fun tickOff(month: String, commitmentId: String, transactionId: String): Budget = Budget()
        override suspend fun tickOffWithNew(month: String, commitmentId: String, payment: com.humblesolutions.finai.model.CommitmentPayment): Budget = Budget()
        override suspend fun untick(month: String, commitmentId: String): Budget = Budget()
        override suspend fun runSpendingCheck(month: String): SpendingCheck = SpendingCheck()
        override suspend fun resetLine(month: String, categoryId: String): Budget = Budget()
        override suspend fun review(month: String): Budget = Budget()
        override fun close() = Unit
    }

    private class FakeCategories : CategoriesRepository {
        override suspend fun list() = listOf(
            Category("cat-rent", "rent", "Rent & mortgage"),
            Category("cat-groceries", "groceries", "Groceries"),
            Category("cat-gifts", "gifts", "Gifts"),
            Category("cat-income", "income", "Income"),
        )
        override suspend fun create(name: String) = Category("cat-new", "new", name, isSystem = false)
        override fun close() = Unit
    }

    /** The phone's storage, shared across models so a second one is "coming back". */
    private class FakeDrafts : AiBudgetDraftStore {
        val kept = mutableMapOf<String, String>()
        override fun load(userId: String) = kept[userId]
        override fun save(userId: String, draft: String) {
            kept[userId] = draft
        }
        override fun clear(userId: String) {
            kept.remove(userId)
        }
    }

    private fun model(budgets: FakeBudgets, drafts: FakeDrafts = FakeDrafts(), legal: FakeLegal? = null) = AiBudgetViewModel().also {
        it.bind("user-1") { AiBudgetRepositories(budgets, FakeCategories(), drafts, legal) }
    }

    /** Setup's one-time AI consent: the policy, and what was agreed to. */
    private class FakeLegal : LegalRepository {
        var agreed: String? = null
        override suspend fun terms() = Terms()
        override suspend fun disclaimer(): Terms? = null
        override suspend fun aiPolicy() = AiPolicy(version = "ai-v2", body = "What is sent, and why.")
        override suspend fun aiStatus() = AiConsentStatus(consented = agreed != null, version = "ai-v2")
        override suspend fun consentToAi(version: String) {
            agreed = version
        }
        override suspend fun withdrawAi() = AiConsentStatus(consented = false, version = "ai-v2")
        override fun close() = Unit
    }

    @Test
    fun in_setup_the_ai_is_agreed_to_once_and_the_budget_suggested() = runTest {
        val budgets = FakeBudgets(made = proposal, failSuggest = ApiException.ConsentRequired("ai-v2"))
        val legal = FakeLegal()
        val model = model(budgets, legal = legal)
        assertTrue(model.uiState.value.aiOff)
        assertEquals("ai-v2", model.uiState.value.policy?.version)

        budgets.failSuggest = null
        model.agreeToAi()
        assertNull(legal.agreed, "nothing is agreed to before the box is ticked")
        model.onConsentTicked(true)
        model.agreeToAi()

        assertEquals("ai-v2", legal.agreed)
        assertFalse(model.uiState.value.aiOff)
        assertEquals(proposal, model.uiState.value.proposal)
    }

    @Test
    fun lines_added_removed_and_typed_come_back_without_asking_the_ai_again() = runTest {
        val budgets = FakeBudgets(stored = proposal)
        val drafts = FakeDrafts()
        val first = model(budgets, drafts = drafts)
        assertEquals(listOf("Gifts"), first.uiState.value.addable.map { it.name })
        assertFalse(first.uiState.value.canRemove(0), "rent holds commitments")
        assertTrue(first.uiState.value.canRemove(1))

        first.onAdd(first.uiState.value.addable.single())
        first.onAmount(3, "40")
        first.onRemove(1)

        val back = model(budgets, drafts = drafts).uiState.value
        assertEquals(listOf("Rent & mortgage", "Debt payment", "Gifts"), back.draft.lines.map { it.line.name })
        assertEquals("40", back.draft.lines.last().amount)
        assertEquals(0, budgets.suggestions, "coming back never asks the AI")
    }

    @Test
    fun starting_the_budget_forgets_the_kept_draft() = runTest {
        val drafts = FakeDrafts()
        val model = model(FakeBudgets(stored = proposal), drafts = drafts)
        model.onAmount(1, "450")
        assertTrue(drafts.kept.isNotEmpty())

        model.start {}

        assertTrue(drafts.kept.isEmpty())
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
    fun without_consent_ai_is_said_to_be_off_and_coming_back_asks_again() = runTest {
        val budgets = FakeBudgets(made = proposal, failSuggest = ApiException.ConsentRequired("ai-v2"))
        val model = model(budgets)
        assertTrue(model.uiState.value.aiOff, "consent is given in Profile, not here")
        assertNull(model.uiState.value.proposal)

        // Turned on in Profile; back here, the suggestion is asked for again.
        budgets.failSuggest = null
        model.retryAfterAi()

        assertFalse(model.uiState.value.aiOff)
        assertNotNull(model.uiState.value.proposal)
        assertEquals(2, budgets.suggestions)
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
