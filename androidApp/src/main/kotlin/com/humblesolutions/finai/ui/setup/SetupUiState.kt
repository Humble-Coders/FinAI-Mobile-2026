package com.humblesolutions.finai.ui.setup

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupBlock
import com.humblesolutions.finai.usecase.SetupDraft
import com.humblesolutions.finai.usecase.SetupStep
import com.humblesolutions.finai.usecase.SetupWizard
import com.humblesolutions.finai.util.Money

/** Which itemised list the user has open, if any. */
enum class ItemList {
    OBLIGATIONS,
    DEBTS,
    INVESTMENTS,
}

/**
 * Everything the wizard shows.
 *
 * The gates are computed here from the shared rules rather than decided in a
 * composable, so they can be asserted with a plain constructor (kmp-arch-v2).
 */
data class SetupUiState(
    val step: SetupStep = SetupStep.INCOME,
    val draft: SetupDraft = SetupDraft(),
    /** What the amounts are denominated in; the server decides it. */
    val currency: String = "",
    /** The first load, before which there is nothing to show. */
    val loading: Boolean = true,
    val busy: Boolean = false,
    val errorKey: String? = null,
    /** The list the user opened, shown instead of the step. */
    val editing: ItemList? = null,
    /** The rows being edited, kept apart until they are kept or dropped. */
    val rows: List<ItemDraft> = emptyList(),
) {
    val fractionDigits: Int get() = Money.fractionDigits(currency)

    val symbol: String get() = Money.symbol(currency)

    /** Why this step cannot be left, or null — the one rule the button and the notice share. */
    val block: SetupBlock? get() = SetupWizard.blockingReason(step, draft, fractionDigits)

    val canContinue: Boolean get() = !busy && block == null

    /** The optional lists can be left empty, so they always offer a way past. */
    val canSkip: Boolean get() = !busy && step != SetupStep.INCOME

    fun itemsOf(list: ItemList): List<ItemDraft> = when (list) {
        ItemList.OBLIGATIONS -> draft.obligations
        ItemList.DEBTS -> draft.debts
        ItemList.INVESTMENTS -> draft.investments
    }

    /** A row still being filled in blocks keeping the list. */
    fun rowBlock(index: Int): SetupBlock? {
        val row = rows.getOrNull(index) ?: return null
        return SetupWizard.itemBlock(row, fractionDigits, debt = editing == ItemList.DEBTS)
    }

    val canKeepRows: Boolean get() = !busy && rows.indices.all { rowBlock(it) == null }
}
