package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupBlock
import com.humblesolutions.finai.usecase.SetupDraft
import com.humblesolutions.finai.usecase.SetupStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wizard's derived rules (ticket #17).
 *
 * They are computed on the state class rather than decided in a composable
 * exactly so they can be asserted with a plain constructor — no coroutines, no
 * repository, no Android (kmp-arch-v2 → ViewModels). What is tested here is
 * what the user meets: which button is enabled, which control is drawn at all,
 * and what the notice says.
 */
class SetupUiStateTest {

    private val answered = SetupDraft(income = "4000", monthlyExpense = "2500")

    @Test
    fun `continue waits for the step's own figure`() {
        val blank = SetupUiState(loading = false)
        assertFalse(blank.canContinue)
        assertEquals(SetupBlock.INCOME_MISSING, blank.block)

        val typed = blank.copy(draft = SetupDraft(income = "1,200"))
        assertTrue(typed.canContinue)
        assertNull(typed.block)
    }

    @Test
    fun `a save in flight disables both controls`() {
        val busy = SetupUiState(loading = false, step = SetupStep.PORTFOLIO, draft = answered, busy = true)
        assertFalse(busy.canContinue)
        assertFalse(busy.canSkip)
    }

    @Test
    fun `only the optional step offers a skip`() {
        val state = SetupUiState(loading = false, draft = answered)
        assertFalse(state.copy(step = SetupStep.INCOME).showsSkip)
        assertFalse(state.copy(step = SetupStep.EXPENSES).showsSkip)
        assertTrue(state.copy(step = SetupStep.PORTFOLIO).showsSkip)
        assertTrue(state.copy(step = SetupStep.PORTFOLIO).canSkip)
    }

    @Test
    fun `swiping ahead does not get past a missing figure`() {
        // Any step can be looked at, but Continue on step 2 or 3 still waits
        // for the income, and says so without waiting to be touched.
        val ahead = SetupUiState(loading = false, step = SetupStep.EXPENSES, draft = SetupDraft(monthlyExpense = "2500"))
        assertFalse(ahead.canContinue)
        assertEquals(SetupBlock.INCOME_MISSING, ahead.notice)

        val last = SetupUiState(loading = false, step = SetupStep.PORTFOLIO, draft = SetupDraft(income = "4000"))
        assertFalse(last.canContinue)
        assertTrue(last.showsSkip)
        assertFalse(last.canSkip)
    }

    @Test
    fun `the small loader shows for a save and for a finish`() {
        assertFalse(SetupUiState(loading = false).showsSaving)
        assertTrue(SetupUiState(loading = false, syncing = true).showsSaving)
        assertTrue(SetupUiState(loading = false, busy = true).showsSaving)
    }

    @Test
    fun `the skip that is offered can always be taken`() {
        // A Skip that is drawn must go through. Dropping the lists drops the
        // only thing on the last step that can block, so it always does.
        val started = SetupUiState(
            loading = false,
            step = SetupStep.PORTFOLIO,
            draft = answered.copy(debts = listOf(ItemDraft(name = "Card", amount = ""))),
        )
        assertTrue(started.canSkip)
        assertEquals(SetupBlock.ITEM_AMOUNT_INVALID, started.block)

        val skipped = started.copy(
            draft = started.draft.copy(debts = emptyList(), investments = emptyList()),
        )
        assertNull(skipped.block)
    }

    @Test
    fun `the notice waits until the figure has been touched`() {
        val untouched = SetupUiState(loading = false)
        assertEquals(SetupBlock.INCOME_MISSING, untouched.block)
        assertNull(untouched.notice)

        val wrong = untouched.copy(draft = SetupDraft(income = "abc"), touched = true)
        assertEquals(SetupBlock.INCOME_NOT_MONEY, wrong.notice)

        val emptied = untouched.copy(touched = true)
        assertEquals(SetupBlock.INCOME_MISSING, emptied.notice)
    }

    @Test
    fun `a figure that is already wrong is said without waiting to be touched`() {
        // Coming back to a step whose stored figure will not do: Continue is
        // disabled, so something has to explain why it is disabled.
        val revisited = SetupUiState(loading = false, draft = SetupDraft(income = "abc"))
        assertEquals(SetupBlock.INCOME_NOT_MONEY, revisited.notice)
    }

    @Test
    fun `a list row shows what it adds up to`() {
        val state = SetupUiState(
            loading = false,
            currency = "CAD",
            step = SetupStep.PORTFOLIO,
            draft = answered.copy(
                debts = listOf(
                    ItemDraft(name = "Card", amount = "1,500"),
                    ItemDraft(name = "Loan", amount = "700.50"),
                ),
            ),
        )
        assertEquals("$2,200.50", state.totalOf(ItemList.DEBTS))
        // Nothing in it yet, so the row says what to do instead of "0".
        assertNull(state.totalOf(ItemList.INVESTMENTS))
    }

    @Test
    fun `the currency decides the symbol and the decimals`() {
        val yen = SetupUiState(loading = false, currency = "JPY")
        assertEquals(0, yen.fractionDigits)
        assertEquals("¥", yen.symbol)
        // A currency the table does not know shows its code rather than a
        // symbol that would be wrong.
        assertEquals("ZZZ", SetupUiState(loading = false, currency = "ZZZ").symbol)
    }

    @Test
    fun `a half finished row cannot be kept`() {
        val editing = SetupUiState(
            loading = false,
            currency = "CAD",
            editing = ItemList.DEBTS,
            rows = listOf(ItemDraft(name = "Card", amount = "")),
        )
        assertFalse(editing.canKeepRows)
        assertEquals(SetupBlock.ITEM_AMOUNT_INVALID, editing.rowBlock(0))

        val filled = editing.copy(rows = listOf(ItemDraft(name = "Card", amount = "500")))
        assertTrue(filled.canKeepRows)
        assertNull(filled.rowBlock(0))
    }
}
