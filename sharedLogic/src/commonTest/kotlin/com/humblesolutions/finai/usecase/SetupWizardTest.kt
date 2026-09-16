package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Debt
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Investment
import com.humblesolutions.finai.model.Obligation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wizard's gates and its payload. Both platforms read these, so a wrong
 * answer here is wrong twice — and it decides whether someone is held at a step
 * or lands on a dashboard with nothing to reason from.
 */
class SetupWizardTest {

    private val complete = SetupDraft(income = "4000", monthlyExpense = "2500")

    @Test
    fun `income is required and must be money`() {
        assertEquals(
            SetupBlock.INCOME_MISSING,
            SetupWizard.blockingReason(SetupStep.INCOME, SetupDraft()),
        )
        assertEquals(
            SetupBlock.INCOME_NOT_MONEY,
            SetupWizard.blockingReason(SetupStep.INCOME, SetupDraft(income = "a lot")),
        )
        assertNull(SetupWizard.blockingReason(SetupStep.INCOME, SetupDraft(income = "1,200")))
    }

    @Test
    fun `the expense step also refuses a half finished obligation`() {
        assertEquals(
            SetupBlock.EXPENSE_MISSING,
            SetupWizard.blockingReason(SetupStep.EXPENSES, SetupDraft(income = "4000")),
        )
        val nameless = complete.copy(obligations = listOf(ItemDraft(name = " ", amount = "900")))
        assertEquals(SetupBlock.ITEM_NAME_MISSING, SetupWizard.blockingReason(SetupStep.EXPENSES, nameless))
        val amountless = complete.copy(obligations = listOf(ItemDraft(name = "Rent", amount = "")))
        assertEquals(SetupBlock.ITEM_AMOUNT_INVALID, SetupWizard.blockingReason(SetupStep.EXPENSES, amountless))
        val good = complete.copy(obligations = listOf(ItemDraft(name = "Rent", amount = "900")))
        assertNull(SetupWizard.blockingReason(SetupStep.EXPENSES, good))
    }

    @Test
    fun `the last step holds nobody up with lists they never opened`() {
        assertNull(SetupWizard.blockingReason(SetupStep.PORTFOLIO, complete))
    }

    @Test
    fun `a debt row that exists must be complete`() {
        val started = complete.copy(debts = listOf(ItemDraft(name = "Card", amount = "")))
        assertEquals(SetupBlock.ITEM_AMOUNT_INVALID, SetupWizard.blockingReason(SetupStep.PORTFOLIO, started))
        val rate = complete.copy(debts = listOf(ItemDraft(name = "Card", amount = "500", interestRatePercent = "120")))
        assertEquals(SetupBlock.RATE_INVALID, SetupWizard.blockingReason(SetupStep.PORTFOLIO, rate))
        val ok = complete.copy(debts = listOf(ItemDraft(name = "Card", amount = "500", interestRatePercent = "19.99")))
        assertNull(SetupWizard.blockingReason(SetupStep.PORTFOLIO, ok))
    }

    @Test
    fun `it resumes at the first figure that is missing`() {
        assertEquals(SetupStep.INCOME, SetupWizard.resumeAt(FinancialSetup()))
        assertEquals(SetupStep.EXPENSES, SetupWizard.resumeAt(FinancialSetup(income = "4000.00")))
        assertEquals(
            SetupStep.PORTFOLIO,
            SetupWizard.resumeAt(FinancialSetup(income = "4000.00", monthlyExpense = "2500.00")),
        )
    }

    @Test
    fun `every save carries the whole wizard with amounts normalised`() {
        val draft = SetupDraft(
            income = "4,000",
            monthlyExpense = "2500.5",
            obligations = listOf(ItemDraft(name = "  Rent  ", amount = "900")),
            debts = listOf(ItemDraft(name = "Card", amount = "1,500", minimumPayment = "50", interestRatePercent = "19.99")),
            investments = listOf(ItemDraft(name = "TFSA", amount = "10000")),
        )

        val payload = SetupWizard.payload(draft, currency = "CAD")

        assertEquals("4000.00", payload.income)
        assertEquals("2500.50", payload.monthlyExpense)
        assertEquals(listOf(Obligation("Rent", "900.00")), payload.obligations)
        assertEquals(listOf(Debt("Card", "1500.00", "50.00", "19.99")), payload.debts)
        assertEquals(listOf(Investment("TFSA", "10000.00")), payload.investments)
        assertEquals("CAD", payload.currency)
    }

    @Test
    fun `a row left half finished is dropped rather than sent`() {
        val draft = SetupDraft(
            income = "4000",
            monthlyExpense = "2500",
            debts = listOf(ItemDraft(name = "Card", amount = ""), ItemDraft(name = "Loan", amount = "700")),
        )

        val payload = SetupWizard.payload(draft, currency = "CAD")

        assertEquals(listOf(Debt("Loan", "700.00", null, null)), payload.debts)
    }

    @Test
    fun `an unanswered figure is null rather than zero`() {
        val payload = SetupWizard.payload(SetupDraft(income = "4000"), currency = "CAD")
        assertEquals("4000.00", payload.income)
        assertNull(payload.monthlyExpense)
    }

    @Test
    fun `what was saved reopens as what was typed`() {
        val saved = FinancialSetup(
            currency = "CAD",
            income = "4000.00",
            monthlyExpense = "2500.00",
            debts = listOf(Debt("Card", "1500.00", "50.00", "19.99")),
            investments = listOf(Investment("TFSA", "10000.00")),
            obligations = listOf(Obligation("Rent", "900.00")),
        )

        val draft = SetupWizard.draftFrom(saved)

        assertEquals("4000.00", draft.income)
        assertEquals(ItemDraft("Card", "1500.00", "50.00", "19.99"), draft.debts.single())
        assertEquals(ItemDraft("Rent", "900.00"), draft.obligations.single())
        assertEquals(SetupWizard.payload(draft, "CAD"), saved)
    }
}
