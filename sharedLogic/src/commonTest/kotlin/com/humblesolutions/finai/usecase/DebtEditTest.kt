package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.BalanceSource
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.DebtRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebtEditTest {

    private val visa = DebtRecord(
        id = "d-visa",
        name = "Visa",
        kind = DebtKind.CREDIT_CARD,
        balance = "4000.00",
        minimumPayment = "120.00",
        interestRatePercent = "20.99",
        accountId = null,
        balanceAsOf = "2026-08-31",
        balanceSource = BalanceSource.STATEMENT,
    )

    @Test
    fun `an untouched draft has nothing to save`() {
        val draft = DebtEdit.draftOf(visa)

        assertEquals(DebtBlock.NOTHING_CHANGED, DebtEdit.blockingReason(visa, draft, "CAD"))
        assertTrue(DebtEdit.changes(visa, draft, "CAD").isEmpty)
    }

    @Test
    fun `only what changed is sent and the same figure is not a change`() {
        val draft = DebtEdit.draftOf(visa).copy(interestRatePercent = "19.99", minimumPayment = "120")

        val changes = DebtEdit.changes(visa, draft, "CAD")

        assertEquals("19.99", changes.interestRatePercent)
        assertNull(changes.minimumPayment, "120 and 120.00 are the same minimum")
        assertNull(changes.name)
    }

    @Test
    fun `emptying a field clears it`() {
        val draft = DebtEdit.draftOf(visa).copy(minimumPayment = "", interestRatePercent = "")

        val changes = DebtEdit.changes(visa, draft, "CAD")

        assertTrue(changes.clearMinimumPayment)
        assertTrue(changes.clearInterestRate)
    }

    @Test
    fun `a promotion needs both its rate and its end`() {
        val half = DebtEdit.draftOf(visa).copy(promoRatePercent = "0")
        val whole = half.copy(promoEndsOn = "2027-05-01")

        assertEquals(DebtBlock.PROMO_INCOMPLETE, DebtEdit.blockingReason(visa, half, "CAD"))
        assertNull(DebtEdit.blockingReason(visa, whole, "CAD"))
        val changes = DebtEdit.changes(visa, whole, "CAD")
        assertEquals("0.00" to "2027-05-01", changes.promoRatePercent to changes.promoEndsOn)
    }

    @Test
    fun `removing a promotion clears the pair`() {
        val promoted = visa.copy(promoRatePercent = "0.00", promoEndsOn = "2027-05-01")
        val draft = DebtEdit.draftOf(promoted).copy(promoRatePercent = "", promoEndsOn = null)

        assertTrue(DebtEdit.changes(promoted, draft, "CAD").clearPromotion)
    }

    @Test
    fun `a rate the server would refuse is refused here`() {
        val draft = DebtEdit.draftOf(visa).copy(interestRatePercent = "120")

        assertEquals(DebtBlock.RATE_INVALID, DebtEdit.blockingReason(visa, draft, "CAD"))
    }

    @Test
    fun `linking and unlinking an account`() {
        val linked = DebtEdit.changes(visa, DebtEdit.draftOf(visa).copy(accountId = "a-card"), "CAD")
        val withLink = visa.copy(accountId = "a-card")
        val unlinked = DebtEdit.changes(withLink, DebtEdit.draftOf(withLink).copy(accountId = null), "CAD")

        assertEquals("a-card", linked.accountId)
        assertTrue(unlinked.clearAccount)
    }

    @Test
    fun `only cards and loans can be linked`() {
        val accounts = listOf(
            Account(id = "a", kind = AccountKind.CHEQUING),
            Account(id = "b", kind = AccountKind.CREDIT_CARD),
            Account(id = "c", kind = AccountKind.LOAN),
            Account(id = "d", kind = AccountKind.SAVINGS),
        )

        assertEquals(listOf("b", "c"), DebtEdit.linkable(accounts).map { it.id })
    }

    @Test
    fun `a new balance may be zero but not the same or negative`() {
        assertNull(DebtEdit.blockingReasonForBalance(visa, "0", "CAD"))
        assertEquals(BalanceBlock.UNCHANGED, DebtEdit.blockingReasonForBalance(visa, "4000", "CAD"))
        assertEquals(BalanceBlock.NEGATIVE, DebtEdit.blockingReasonForBalance(visa, "-5", "CAD"))
        assertEquals(BalanceBlock.NO_BALANCE, DebtEdit.blockingReasonForBalance(visa, "", "CAD"))
        assertEquals("3500.00", DebtEdit.balanceChange("3,500", "CAD").balance)
    }

    @Test
    fun `the source line says where the balance came from and when`() {
        assertEquals("Balance from a statement, Aug 31, 2026", DebtEdit.sourceLine(visa))
        assertEquals("Balance not dated", DebtEdit.sourceLine(visa.copy(balanceAsOf = null)))
    }

    @Test
    fun `only a stale balance gets the reminder`() {
        assertNull(DebtEdit.staleLine(visa))
        assertTrue(DebtEdit.staleLine(visa.copy(balanceStale = true, balanceSource = BalanceSource.SETUP))!!.isNotBlank())
    }

    @Test
    fun `a promotion is worded with what follows it`() {
        val promoted = visa.copy(promoRatePercent = "0.00", promoEndsOn = "2027-05-01", interestRatePercent = "22.99")

        assertEquals("0% until May 1, 2027, then 22.99%", DebtEdit.promotionLine(promoted))
        assertNull(DebtEdit.promotionLine(visa))
    }
}
