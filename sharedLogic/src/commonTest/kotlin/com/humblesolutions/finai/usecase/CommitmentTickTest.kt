package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetCommitment
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitmentTickTest {

    private val rent = BudgetCommitment(id = "o-rent", name = "Rent", expected = "1800.00", dueDay = 1)
    private val gym = BudgetCommitment(id = "o-gym", name = "Gym", expected = "40.00", dueDay = 31, transactionId = "t-gym", paidOn = "2026-09-03", paidAmount = "40.00")
    private val september = Budget(month = "2026-09-01", currency = "CAD", commitments = listOf(rent, gym))

    private fun row(id: String, amount: String, day: Int, debit: Boolean = true, month: Int = 9) = Transaction(
        id = id,
        occurredOn = "2026-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}",
        amount = amount,
        currency = "CAD",
        direction = if (debit) TransactionDirection.DEBIT else TransactionDirection.CREDIT,
        description = "ROW $id",
    )

    @Test
    fun the_closest_payments_out_of_the_month_come_first() {
        val rows = listOf(
            row("t-coffee", "5.25", 4),
            row("t-transfer", "1800.00", 2),
            row("t-gym", "40.00", 3),
            row("t-refund", "1800.00", 5, debit = false),
            row("t-august", "1800.00", 30, month = 8),
            row("t-big", "1750.00", 10),
        )

        val found = CommitmentTick.candidates(rows, rent, september)

        assertEquals(listOf("t-transfer", "t-big", "t-coffee"), found.map { it.transactionId }, "no money in, no other month, nothing paying the gym")
        assertTrue(found[0].likely)
        assertTrue(found[1].likely, "within a tenth")
        assertFalse(found[2].likely)
        assertEquals("$1,800.00", found[0].amount)
    }

    @Test
    fun a_typed_in_payment_starts_on_its_due_day_but_never_in_the_future() {
        assertEquals(LocalDate(2026, 9, 1), CommitmentTick.defaultDate(rent, september, LocalDate(2026, 9, 15)))
        assertEquals(LocalDate(2026, 9, 15), CommitmentTick.defaultDate(gym, september, LocalDate(2026, 9, 15)), "the 30th has not come")
        assertEquals(LocalDate(2026, 9, 30), CommitmentTick.defaultDate(gym, september, LocalDate(2026, 11, 2)), "a 31 in a 30-day month")
    }

    @Test
    fun a_typed_in_payment_needs_an_amount_a_date_in_the_month_and_an_account() {
        val day = LocalDate(2026, 9, 2)
        assertEquals(TickBlock.AMOUNT, CommitmentTick.blockingReason("", day, "a1", september))
        assertEquals(TickBlock.DATE, CommitmentTick.blockingReason("1800", LocalDate(2026, 8, 31), "a1", september))
        assertEquals(TickBlock.ACCOUNT, CommitmentTick.blockingReason("1800", day, null, september))
        assertNull(CommitmentTick.blockingReason("1800", day, "a1", september))
        val payment = CommitmentTick.payment("1800", day, "a1", september)!!
        assertEquals("1800.00", payment.amount)
        assertEquals("2026-09-02", payment.occurredOn)
        assertNull(CommitmentTick.payment("", day, "a1", september))
    }

    @Test
    fun the_sheet_names_what_is_expected_and_how_it_was_paid() {
        assertEquals("Mark Rent as paid", CommitmentTick.title(rent))
        assertEquals("Expected $1,800.00", CommitmentTick.subtitle(rent, september))
        assertTrue(CommitmentTick.subtitle(gym, september).contains("seen paid"))
        assertTrue(CommitmentTick.subtitle(gym.copy(paidByHand = true), september).contains("marked paid"))
    }
}
