package com.humblesolutions.finai.ui.goals

import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.model.GoalsPage
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.usecase.AddMoneyBlock
import com.humblesolutions.finai.usecase.GoalBlock
import com.humblesolutions.finai.usecase.GoalDraft
import com.humblesolutions.finai.usecase.GoalEdit
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * The household's goals as the screen reads them (#52, PRD F5).
 *
 * Every figure is the server's; every sentence is [GoalEdit]'s, so this file
 * only decides what is shown and whether a button is on.
 */
data class GoalsUiState(
    val page: GoalsPage? = null,
    /** The household's currency, from capabilities — goals carry none of their own. */
    val currency: String = "",
    val locale: String = "en",
    val today: LocalDate = ManualEntry.today(),

    /** The region's disclaimer, shown beside long-term projections; null when it has none. */
    val disclaimer: Terms? = null,

    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadFailed: Boolean = false,
    /** Why the list could not be read. Cleared by the next read that works. */
    val errorKey: String? = null,
    /**
     * Why an action on the list was refused — a reorder, a delete. Its own
     * field because those refusals are often followed by a fresh read, which
     * would otherwise wipe the very message explaining why it happened.
     */
    val noticeKey: String? = null,

    // ── The editor ──────────────────────────────────────────────────────
    /** True while a new goal is being written. */
    val creating: Boolean = false,
    /** The goal being edited, by id; its current figures are read from [page]. */
    val editingId: String? = null,
    val draft: GoalDraft = GoalDraft(),
    val saving: Boolean = false,
    val editErrorKey: String? = null,

    // ── Add money ───────────────────────────────────────────────────────
    val addingToId: String? = null,
    val addAmount: String = "",
    val adding: Boolean = false,
    val addErrorKey: String? = null,

    // ── Delete and order ────────────────────────────────────────────────
    val confirmingDeleteId: String? = null,
    val deleting: Boolean = false,
    /** True while the list shows its move controls. */
    val reordering: Boolean = false,
    val reorderBusy: Boolean = false,
) {
    val words: GoalEdit.Words get() = GoalEdit.Words(currency = currency, locale = locale)

    val goals: List<Goal> get() = page?.goals.orEmpty()

    val showsEmpty: Boolean get() = !loading && !loadFailed && page != null && goals.isEmpty()

    val comparisonLine: String? get() = page?.let { GoalEdit.comparisonLine(it, words) }

    fun amountsOf(goal: Goal): String = GoalEdit.amountsLine(goal, words)

    fun statusOf(goal: Goal): String = GoalEdit.statusLine(goal, words)

    fun descriptionOf(goal: Goal): String = GoalEdit.description(goal, words)

    /** "Doesn't include investment growth", for a long-term goal while projections assume none. */
    fun growthLineOf(goal: Goal): String? = page?.takeIf { GoalEdit.saysNoGrowth(goal, it) }?.let { GoalEdit.growthLine(words) }

    /** The disclaimer, under a long-term goal still being saved for. */
    fun disclaimerOf(goal: Goal): String? = disclaimer?.body?.takeIf { goal.isLongTerm && !goal.isAchieved && it.isNotBlank() }

    // ── The editor ──────────────────────────────────────────────────────

    val editing: Goal? get() = editingId?.let { id -> goals.firstOrNull { it.id == id } }

    val editorOpen: Boolean get() = creating || editing != null

    /**
     * Why Save is off, or null. The one shared function, read here for the
     * button and the notice and by the view model for the refusal.
     */
    val editBlock: GoalBlock?
        get() {
            if (creating) return GoalEdit.blockingReasonForNew(draft, currency, today, page?.openCount ?: 0)
            val goal = editing ?: return null
            return GoalEdit.blockingReason(goal, draft, currency, today)
        }

    /** "Nothing has changed" is not worth saying before the person has touched anything. */
    val editNotice: GoalBlock? get() = editBlock?.takeIf { it != GoalBlock.NOTHING_CHANGED }

    val canSave: Boolean get() = !saving && editBlock == null

    /** Opening a new goal at the limit says so up front rather than after five fields. */
    val atLimit: Boolean get() = (page?.openCount ?: 0) >= GoalEdit.OPEN_LIMIT

    val currencySymbol: String get() = Money.symbol(currency)

    // ── Add money ───────────────────────────────────────────────────────

    val addingTo: Goal? get() = addingToId?.let { id -> goals.firstOrNull { it.id == id } }

    val addBlock: AddMoneyBlock? get() = if (addingTo == null) null else GoalEdit.blockingReasonForAdd(addAmount, currency)

    /** Blank is not worth calling out before anything is typed. */
    val addNotice: AddMoneyBlock? get() = addBlock?.takeIf { it != AddMoneyBlock.NO_AMOUNT }

    val canAdd: Boolean get() = !adding && addBlock == null

    // ── Delete and order ────────────────────────────────────────────────

    val confirmingDelete: Goal? get() = confirmingDeleteId?.let { id -> goals.firstOrNull { it.id == id } }

    fun canMoveUp(index: Int): Boolean = !reorderBusy && index > 0

    fun canMoveDown(index: Int): Boolean = !reorderBusy && index < goals.lastIndex
}
