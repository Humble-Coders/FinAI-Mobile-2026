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
    /**
     * The furthest step reached. Swiping back over answered steps is free;
     * swiping forward past one that is not answered is not, so the gate the
     * Continue button enforces cannot be slid around.
     */
    val reached: SetupStep = SetupStep.INCOME,
    val draft: SetupDraft = SetupDraft(),
    /** What the amounts are denominated in; capabilities says, the server decides. */
    val currency: String = "",
    /**
     * The language figures are formatted in, from capabilities — never the
     * device's, so two people in one household read the same figures the same
     * way. Blank until it has loaded, which formats as English.
     */
    val locale: String = "",
    /** The first load, before which there is nothing to show. */
    val loading: Boolean = true,
    val busy: Boolean = false,
    val errorKey: String? = null,
    /** The list the user opened, shown instead of the step. */
    val editing: ItemList? = null,
    /** The rows being edited, kept apart until they are kept or dropped. */
    val rows: List<ItemDraft> = emptyList(),
    /**
     * Whether this step's figure has been edited since the step was shown.
     *
     * The notice waits for it. A step that opens with "Enter your monthly
     * income to continue." in red, before the user has typed anything, reads as
     * a mistake they have already made; the disabled button says the same thing
     * without the accusation.
     */
    val touched: Boolean = false,
) {
    val fractionDigits: Int get() = Money.fractionDigits(currency)

    val symbol: String get() = Money.symbol(currency)

    /** Why this step cannot be left, or null — the one rule the button and the notice share. */
    val block: SetupBlock? get() = SetupWizard.blockingReason(step, draft, fractionDigits)

    val canContinue: Boolean get() = !busy && block == null

    /** What the notice renders: the block, once there is something for it to be about. */
    val notice: SetupBlock? get() = if (touched) block else null

    /**
     * Only a step that is optional in full offers a Skip (ticket #17).
     *
     * The mandatory figures cannot be skipped, so no control claims they can —
     * and because the step this leaves has nothing that can refuse, Skip can
     * never be a button that does nothing when pressed.
     */
    val canSkip: Boolean get() = !busy && step.isOptional

    fun itemsOf(list: ItemList): List<ItemDraft> = when (list) {
        ItemList.OBLIGATIONS -> draft.obligations
        ItemList.DEBTS -> draft.debts
        ItemList.INVESTMENTS -> draft.investments
    }

    /**
     * What a list adds up to, written for reading, or null while it is empty.
     *
     * The row shows the figure rather than how many rows are behind it: the
     * total is what the user came to check.
     */
    fun totalOf(list: ItemList): String? =
        SetupWizard.total(itemsOf(list), fractionDigits, debt = list == ItemList.DEBTS)
            ?.let { Money.format(it, currency, locale) }
            ?.ifBlank { null }

    /** A row still being filled in blocks keeping the list. */
    fun rowBlock(index: Int): SetupBlock? {
        val row = rows.getOrNull(index) ?: return null
        return SetupWizard.itemBlock(row, fractionDigits, debt = editing == ItemList.DEBTS)
    }

    val canKeepRows: Boolean get() = !busy && rows.indices.all { rowBlock(it) == null }
}
