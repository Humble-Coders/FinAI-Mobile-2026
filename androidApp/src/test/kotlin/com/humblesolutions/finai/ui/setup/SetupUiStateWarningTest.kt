package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupDraft
import com.humblesolutions.finai.usecase.SetupStep
import com.humblesolutions.finai.usecase.SetupWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The warning as the screen sees it. Plain constructors, no view model: the
 * derived rules are computed vals on the state, which is what makes this
 * testable without a dispatcher (kmp-arch-v2).
 */
class SetupUiStateWarningTest {

    private fun state(expense: String, vararg commitments: String) = SetupUiState(
        step = SetupStep.EXPENSES,
        currency = "CAD",
        loading = false,
        draft = SetupDraft(
            income = "5000",
            monthlyExpense = expense,
            obligations = commitments.mapIndexed { index, amount ->
                ItemDraft(name = "item $index", amount = amount)
            },
        ),
    )

    @Test
    fun commitments_over_the_total_reach_the_screen() {
        assertEquals(SetupWarning.OBLIGATIONS_OVER_EXPENSES, state("1000", "800", "400").warning)
    }

    @Test
    fun commitments_inside_the_total_say_nothing() {
        assertNull(state("2000", "800", "400").warning)
    }

    @Test
    fun the_warning_never_disables_continue() {
        val over = state("1000", "800", "400")
        assertNotNull(over.warning)
        assertTrue(over.canContinue, "a warning is advice, not a gate")
        assertNull(over.block)
    }

    @Test
    fun the_message_is_a_sentence_and_not_a_raw_key() {
        // LocalizationRegistry returns an unknown key unchanged, so a key that
        // was never given a value would come back as itself.
        val key = state("1000", "800", "400").warning!!.messageKey
        assertNotEquals(key, LocalizationRegistry.get(key), "no English value for $key")
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}
