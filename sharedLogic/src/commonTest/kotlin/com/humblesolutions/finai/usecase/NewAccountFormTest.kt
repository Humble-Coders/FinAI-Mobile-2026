package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.NewAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The account added from manual entry: named, and of a kind the person chose. */
class NewAccountFormTest {

    private val complete = NewAccountDraft(name = "RBC Visa", kind = AccountKind.CREDIT_CARD)

    @Test
    fun aNamedAccountOfAChosenKindCanBeAdded() {
        assertNull(NewAccountForm.blockingReason(complete))
        assertEquals(NewAccount("RBC Visa", AccountKind.CREDIT_CARD), NewAccountForm.request(complete))
    }

    @Test
    fun theKindStartsUnanswered() {
        // No preselected Chequing: a card added in a hurry would be misfiled.
        val draft = NewAccountDraft(name = "RBC Visa")

        assertNull(draft.kind)
        assertEquals(NewAccountBlock.NO_KIND, NewAccountForm.blockingReason(draft))
        assertNull(NewAccountForm.request(draft))
    }

    @Test
    fun anUnknownKindIsNotAnAnswer() {
        assertEquals(
            NewAccountBlock.NO_KIND,
            NewAccountForm.blockingReason(complete.copy(kind = AccountKind.UNKNOWN)),
        )
    }

    @Test
    fun aBlankNameIsRefusedBeforeTheKind() {
        assertEquals(NewAccountBlock.NO_NAME, NewAccountForm.blockingReason(NewAccountDraft(name = "   ")))
    }

    @Test
    fun theNameIsSentTrimmed() {
        assertEquals("RBC Visa", NewAccountForm.request(complete.copy(name = "  RBC Visa  "))?.name)
    }

    @Test
    fun aNameAtTheServersLimitIsAcceptedAndOnePastItIsNot() {
        val atLimit = complete.copy(name = "a".repeat(NewAccountForm.MAX_NAME_LENGTH))

        assertNull(NewAccountForm.blockingReason(atLimit))
        assertEquals(
            NewAccountBlock.NAME_TOO_LONG,
            NewAccountForm.blockingReason(atLimit.copy(name = atLimit.name + "a")),
        )
    }

    @Test
    fun anUnansweredFieldWaitsForTheFormToBeTouched() {
        assertNull(NewAccountForm.notice(NewAccountDraft(), touched = false))
        assertEquals(NewAccountBlock.NO_NAME, NewAccountForm.notice(NewAccountDraft(), touched = true))
    }

    @Test
    fun aNameTooLongIsSaidStraightAway() {
        val tooLong = NewAccountDraft(name = "a".repeat(NewAccountForm.MAX_NAME_LENGTH + 1))

        assertEquals(NewAccountBlock.NAME_TOO_LONG, NewAccountForm.notice(tooLong, touched = false))
    }

    @Test
    fun everyChoosableKindHasALabelAndUnknownHasNone() {
        assertEquals(emptyList(), AccountKind.choosable.filter { it.labelKey == null })
        assertNull(AccountKind.UNKNOWN.labelKey)
    }
}
