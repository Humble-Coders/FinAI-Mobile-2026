package com.humblesolutions.finai.ui.setup

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
    /** The step on screen. Any step can be swiped to; Continue is what is gated. */
    val step: SetupStep = SetupStep.INCOME,
    val draft: SetupDraft = SetupDraft(),
    /** What the amounts are denominated in; capabilities says, the server decides. */
    val currency: String = "",
    /**
     * The language figures are formatted in, from capabilities — never the
     * device's, so two people in one household read the same figures the same
     * way. Blank until it has loaded, which formats as English.
     */
    val locale: String = "",
    /** The first load, while the app's coin loader covers the screen. */
    val loading: Boolean = true,
    /**
     * Finishing: the save the gate clears on is under way, so nothing else may
     * start. Continue on earlier steps never sets this — it does not wait.
     */
    val busy: Boolean = false,
    /** A save running behind the steps. Only the small loader says so. */
    val syncing: Boolean = false,
    val errorKey: String? = null,
    /** The list the user opened, shown instead of the step. */
    val editing: ItemList? = null,
    /** The rows being edited, kept apart until they are kept or dropped. */
    val rows: List<ItemDraft> = emptyList(),
    /** Whether this step's figure has been edited since the step was shown. */
    val touched: Boolean = false,
    /** The user cancelled setup: the wizard says it is required instead of showing a step. */
    val cancelled: Boolean = false,
) {
    val fractionDigits: Int get() = Money.fractionDigits(currency)

    val symbol: String get() = Money.symbol(currency)

    /** Zero, written at the currency's scale — the faint figure in an empty amount box. */
    val amountPlaceholder: String get() = Money.normalize("0", fractionDigits).orEmpty()

    /** Why Continue on this step cannot go ahead, or null — shared with the notice. */
    val block: SetupBlock? get() = SetupWizard.blockingReason(step, draft, fractionDigits)

    /** Continue waits for every mandatory figure up to this step, and for a finish in flight. */
    val canContinue: Boolean get() = !busy && block == null

    /** What the notice under Continue says — the same reason, with the shared timing. */
    val notice: SetupBlock? get() = SetupWizard.notice(step, draft, fractionDigits, touched)

    /** Skip is drawn only on the step that is optional in full. */
    val showsSkip: Boolean get() = step.isOptional

    /** ...and goes through only once both mandatory figures are in. */
    val canSkip: Boolean get() = !busy && SetupWizard.canSkip(step, draft, fractionDigits)

    /** The small loader: a save under way. Never the coin. */
    val showsSaving: Boolean get() = syncing || busy

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
