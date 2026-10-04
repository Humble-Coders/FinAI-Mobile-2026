package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Obligation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommitmentEditTest {

    private val rent = Commitment(name = "Rent", expected = "1800.00")

    private fun setup(vararg obligations: Obligation) = FinancialSetup(currency = "CAD", obligations = obligations.toList())

    @Test
    fun a_draft_starts_as_the_commitment_is() {
        assertEquals(CommitmentDraft("Rent", "1800.00"), CommitmentEdit.draftOf(rent))
    }

    @Test
    fun an_unchanged_draft_cannot_be_saved() {
        assertEquals(CommitmentBlock.NOTHING_CHANGED, CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent", "1800.00"), "CAD"))
        // The same amount written differently is not a change.
        assertEquals(CommitmentBlock.NOTHING_CHANGED, CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent ", "1800"), "CAD"))
    }

    @Test
    fun a_name_or_an_amount_change_can_be_saved() {
        assertNull(CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent + parking", "1800.00"), "CAD"))
        assertNull(CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent", "1850"), "CAD"))
    }

    @Test
    fun a_draft_that_is_not_a_commitment_is_refused_with_its_reason() {
        assertEquals(CommitmentBlock.NO_NAME, CommitmentEdit.blockingReason(rent, CommitmentDraft("  ", "10"), "CAD"))
        assertEquals(CommitmentBlock.NAME_TOO_LONG, CommitmentEdit.blockingReason(rent, CommitmentDraft("x".repeat(256), "10"), "CAD"))
        assertEquals(CommitmentBlock.NO_AMOUNT, CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent", ""), "CAD"))
        assertEquals(CommitmentBlock.AMOUNT_NOT_MONEY, CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent", "ten"), "CAD"))
        assertEquals(CommitmentBlock.AMOUNT_ZERO, CommitmentEdit.blockingReason(rent, CommitmentDraft("Rent", "0"), "CAD"))
    }

    @Test
    fun applying_changes_the_matching_obligation_and_nothing_else() {
        val before = setup(Obligation("Phone", "65.00"), Obligation("Rent", "1800.00"), Obligation("Gym", "40.00"))

        val after = assertNotNull(CommitmentEdit.applied(before, rent, CommitmentDraft(" Rent + parking ", "1850")))

        assertEquals(
            listOf(Obligation("Phone", "65.00"), Obligation("Rent + parking", "1850.00"), Obligation("Gym", "40.00")),
            after.obligations,
        )
    }

    @Test
    fun the_match_reads_amounts_the_way_money_reads_them() {
        val after = CommitmentEdit.applied(setup(Obligation("Rent", "1800")), rent, CommitmentDraft("Rent", "1900"))
        assertEquals("1900.00", assertNotNull(after).obligations.single().monthlyAmount)
    }

    @Test
    fun a_commitment_changed_elsewhere_is_not_written_over() {
        // The wizard's answers moved on since the dashboard was read. Writing
        // the list back would replace that change with a stale one.
        assertNull(CommitmentEdit.applied(setup(Obligation("Rent", "1950.00")), rent, CommitmentDraft("Rent", "2000")))
        assertNull(CommitmentEdit.applied(setup(), rent, CommitmentDraft("Rent", "2000")))
    }

    @Test
    fun of_two_identical_rows_only_the_first_changes() {
        val after = CommitmentEdit.applied(
            setup(Obligation("Rent", "1800.00"), Obligation("Rent", "1800.00")),
            rent,
            CommitmentDraft("Rent", "1900"),
        )
        assertEquals(listOf("1900.00", "1800.00"), assertNotNull(after).obligations.map { it.monthlyAmount })
    }

    // ── Adding ──────────────────────────────────────────────────────────

    @Test
    fun a_new_commitment_goes_at_the_end_with_its_amount_written_as_money() {
        val after = CommitmentEdit.added(setup(Obligation("Rent", "1800.00")), CommitmentDraft(" Gym ", "40"))
        assertEquals(listOf(Obligation("Rent", "1800.00"), Obligation("Gym", "40.00")), assertNotNull(after).obligations)
    }

    @Test
    fun a_new_commitment_is_checked_like_an_edited_one() {
        assertEquals(CommitmentBlock.NO_NAME, CommitmentEdit.blockingReasonForNew(CommitmentDraft("", "10"), "CAD", 0))
        assertEquals(CommitmentBlock.AMOUNT_ZERO, CommitmentEdit.blockingReasonForNew(CommitmentDraft("Gym", "0"), "CAD", 0))
        assertNull(CommitmentEdit.blockingReasonForNew(CommitmentDraft("Gym", "40"), "CAD", 3))
    }

    @Test
    fun the_twenty_first_commitment_is_refused_before_it_is_typed() {
        // The server holds a wizard list to twenty; learning it from a 422
        // after both fields are filled in is the worse time.
        assertEquals(
            CommitmentBlock.TOO_MANY,
            CommitmentEdit.blockingReasonForNew(CommitmentDraft("Gym", "40"), "CAD", CommitmentEdit.COUNT_LIMIT),
        )
    }

    @Test
    fun a_list_that_filled_up_elsewhere_is_not_added_to() {
        val full = setup(*Array(CommitmentEdit.COUNT_LIMIT) { Obligation("Bill $it", "10.00") })
        assertNull(CommitmentEdit.added(full, CommitmentDraft("Gym", "40")))
    }

    // ── Deleting ────────────────────────────────────────────────────────

    @Test
    fun deleting_removes_the_matching_obligation_and_nothing_else() {
        val after = CommitmentEdit.removed(
            setup(Obligation("Phone", "65.00"), Obligation("Rent", "1800.00"), Obligation("Gym", "40.00")),
            rent,
        )
        assertEquals(listOf(Obligation("Phone", "65.00"), Obligation("Gym", "40.00")), assertNotNull(after).obligations)
    }

    @Test
    fun a_commitment_changed_elsewhere_is_not_deleted_on_a_stale_read() {
        assertNull(CommitmentEdit.removed(setup(Obligation("Rent", "1950.00")), rent))
    }

    @Test
    fun of_two_identical_rows_only_one_is_deleted() {
        val after = CommitmentEdit.removed(setup(Obligation("Rent", "1800.00"), Obligation("Rent", "1800.00")), rent)
        assertEquals(1, assertNotNull(after).obligations.size)
    }
}
