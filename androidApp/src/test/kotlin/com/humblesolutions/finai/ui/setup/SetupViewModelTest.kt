package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.usecase.SetupStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who the wizard belongs to.
 *
 * The model is scoped to the activity so a rotation cannot lose a half-typed
 * figure — which means it also outlives a sign-out. This is the other half of
 * that bargain: the next account to sign in must never inherit what the last
 * one typed, because the first Continue would save it under their name.
 */
class SetupViewModelTest {

    @Test
    fun `a different user gets a clean wizard`() {
        val model = SetupViewModel()
        model.releaseForNewUser("user-a")
        model.onIncomeChange("4000")
        model.onExpenseChange("2500")
        assertEquals("4000", model.uiState.value.draft.income)

        assertTrue(model.rebindNeeded("user-b"))
        model.releaseForNewUser("user-b")

        assertEquals(SetupUiState(), model.uiState.value)
        assertEquals("", model.uiState.value.draft.income)
        assertEquals("", model.uiState.value.draft.monthlyExpense)
        assertEquals(SetupStep.INCOME, model.uiState.value.step)
    }

    @Test
    fun `the same user does not rebind so a rotation keeps what was typed`() {
        val model = SetupViewModel()
        model.releaseForNewUser("user-a")
        model.onIncomeChange("4000")

        assertFalse(model.rebindNeeded("user-a"))
        assertEquals("4000", model.uiState.value.draft.income)
    }
}
