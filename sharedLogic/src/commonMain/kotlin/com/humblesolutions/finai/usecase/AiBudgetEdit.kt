package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiAccept
import com.humblesolutions.finai.model.AiAcceptLine
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.AiProposalLine
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.util.Money
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of the suggested budget as the person is editing it. */
data class AiDraftLine(
    val line: AiProposalLine = AiProposalLine(),
    /** As typed; starts at the AI's amount. */
    val amount: String = "",
)

/** The whole suggested budget being edited: its lines and the savings target. */
data class AiBudgetDraft(
    val lines: List<AiDraftLine> = emptyList(),
    val savings: String = "",
)

/** Why the suggested budget cannot be started as it is. */
enum class AiBudgetBlock(val messageKey: String) {
    AMOUNT_INVALID(Strings.ai_budget_block_amount),
    BELOW_COMMITMENT(Strings.ai_budget_block_commitment),
    OVER_INCOME(Strings.ai_budget_block_over),
}

/**
 * The AI's suggested budget, editable, and the words around it.
 *
 * Every figure the AI suggested was checked by the server. What happens here
 * is the person's own arithmetic on what they typed: what their lines add up
 * to, and how far that is from their income — the same kind of sum setup's
 * totals are. Shared so both apps refuse the same drafts and say the same thing.
 */
object AiBudgetEdit {

    fun draftOf(proposal: AiProposal): AiBudgetDraft = AiBudgetDraft(
        lines = proposal.lines.map { AiDraftLine(it, it.amount) },
        savings = proposal.savings,
    )

    fun withAmount(draft: AiBudgetDraft, index: Int, amount: String): AiBudgetDraft = draft.copy(lines = draft.lines.mapIndexed { i, line -> if (i == index) line.copy(amount = amount) else line })

    /** The `kind` of a line the person added themselves; the AI never sends it. */
    const val ADDED = "added"

    /**
     * Categories the person may add a line for: ones a budget can hold, not
     * already on the draft, and not Debt or Savings, which have rows of their
     * own. The person's own categories first, then by name, as the Budget
     * screen's picker.
     */
    fun addable(categories: List<Category>, draft: AiBudgetDraft): List<Category> {
        val taken = draft.lines.map { it.line.categoryId }.toSet()
        return categories
            .filter { BudgetEdit.isBudgetable(it.slug) && it.slug !in OWN_ROWS && it.id !in taken }
            .sortedWith(compareBy<Category> { it.isSystem }.thenBy { it.name.lowercase() })
    }

    /** [category] as a new line at the end, empty until the person types an amount. */
    fun withLineAdded(draft: AiBudgetDraft, category: Category): AiBudgetDraft {
        if (draft.lines.any { it.line.categoryId == category.id }) return draft
        val line = AiProposalLine(categoryId = category.id, slug = category.slug, name = category.name, kind = ADDED)
        return draft.copy(lines = draft.lines + AiDraftLine(line, ""))
    }

    /**
     * Whether a line may be taken off. Not the Debt line, which holds the
     * minimum payments, and not one holding commitments: those are owed
     * whatever the budget says, the same reason a line can't go below them.
     */
    fun canRemove(line: AiDraftLine, currency: String): Boolean = !line.line.isDebt && !Money.isPositive(line.line.committed, Money.fractionDigits(currency))

    fun withoutLine(draft: AiBudgetDraft, index: Int, currency: String): AiBudgetDraft {
        val line = draft.lines.getOrNull(index) ?: return draft
        if (!canRemove(line, currency)) return draft
        return draft.copy(lines = draft.lines.filterIndexed { i, _ -> i != index })
    }

    // ── Kept for later ──────────────────────────────────────────────────

    /**
     * The draft as text, to keep on the phone until the budget is started, so
     * coming back shows the person's edits without asking the AI again. Tied
     * to the suggestion it was made from: [restore] drops it for any other.
     */
    fun encode(draft: AiBudgetDraft, proposal: AiProposal): String = SavedJson.encodeToString(
        SavedDraft.serializer(),
        SavedDraft(
            suggestion = fingerprint(proposal),
            lines = draft.lines.map { SavedLine(it.line, it.amount) },
            savings = draft.savings,
        ),
    )

    /** The kept draft, when it was made from [proposal]; otherwise null. */
    fun restore(saved: String?, proposal: AiProposal): AiBudgetDraft? {
        if (saved.isNullOrBlank()) return null
        val kept = runCatching { SavedJson.decodeFromString(SavedDraft.serializer(), saved) }.getOrNull() ?: return null
        if (kept.suggestion != fingerprint(proposal)) return null
        return AiBudgetDraft(lines = kept.lines.map { AiDraftLine(it.line, it.amount) }, savings = kept.savings)
    }

    /** One suggestion from another: its month, currency and every figure the AI gave. */
    private fun fingerprint(proposal: AiProposal): String = buildString {
        append(proposal.month).append('|').append(proposal.currency).append('|').append(proposal.savings)
        proposal.lines.forEach { append('|').append(it.categoryId).append(':').append(it.amount) }
    }

    private val OWN_ROWS = setOf("debt_payment", "savings")

    private val SavedJson = Json { ignoreUnknownKeys = true }

    /** Why one line cannot be kept, or null. Blank is zero: the person doesn't plan for it. */
    fun lineBlock(line: AiDraftLine, currency: String): AiBudgetBlock? {
        val digits = Money.fractionDigits(currency)
        val amount = line.amount.ifBlank { "0" }
        if (!Money.isMoney(amount, digits)) return AiBudgetBlock.AMOUNT_INVALID
        if (Money.compare(amount, line.line.committed, digits) < 0) return AiBudgetBlock.BELOW_COMMITMENT
        return null
    }

    /** Income less every line and savings, signed; null when something typed is not money. */
    fun left(draft: AiBudgetDraft, proposal: AiProposal): String? {
        val digits = Money.fractionDigits(proposal.currency)
        val total = (draft.lines.map { it.amount } + draft.savings).fold("0") { sum, amount ->
            Money.add(sum, amount.ifBlank { "0" }, digits) ?: return null
        }
        return Money.difference(proposal.income, total, digits)
    }

    /** The one rule the Start button, the notice and the request all read. */
    fun blockingReason(draft: AiBudgetDraft, proposal: AiProposal): AiBudgetBlock? {
        draft.lines.firstNotNullOfOrNull { lineBlock(it, proposal.currency) }?.let { return it }
        if (!Money.isMoney(draft.savings.ifBlank { "0" }, Money.fractionDigits(proposal.currency))) return AiBudgetBlock.AMOUNT_INVALID
        val left = left(draft, proposal) ?: return AiBudgetBlock.AMOUNT_INVALID
        if (Money.signOf(left, Money.fractionDigits(proposal.currency)) < 0) return AiBudgetBlock.OVER_INCOME
        return null
    }

    /** What is sent to start the budget: every line but the Debt line, which follows its own rule. */
    fun accept(draft: AiBudgetDraft, currency: String): AiAccept {
        val digits = Money.fractionDigits(currency)
        return AiAccept(
            lines = draft.lines.filterNot { it.line.isDebt }.map {
                AiAcceptLine(categoryId = it.line.categoryId, amount = Money.normalize(it.amount.ifBlank { "0" }, digits) ?: "0")
            },
            savings = Money.normalize(draft.savings.ifBlank { "0" }, digits)?.takeIf { Money.isPositive(it, digits) },
        )
    }

    // ── Words ───────────────────────────────────────────────────────────

    /** "Needs 52% · Wants 22% · Savings 26%", the AI's split before any edit. */
    fun shareLabel(proposal: AiProposal, locale: String = "en"): String = LocalizationRegistry.format(
        Strings.ai_budget_shares,
        listOf(proposal.needsPercent.toString(), proposal.wantsPercent.toString(), proposal.savingsPercent.toString()),
        locale,
    )

    /** "$120.00 not planned yet", "Over your income by $80.00", or null when it adds up exactly. */
    fun leftLabel(draft: AiBudgetDraft, proposal: AiProposal, locale: String = "en"): String? {
        val left = left(draft, proposal) ?: return null
        val digits = Money.fractionDigits(proposal.currency)
        val sign = Money.signOf(left, digits)
        if (sign == 0) return null
        val amount = Money.format(Money.magnitudeOf(left, digits) ?: return null, proposal.currency, locale)
        return LocalizationRegistry.format(if (sign > 0) Strings.ai_budget_unplanned else Strings.ai_budget_over, listOf(amount), locale)
    }

    fun kindLabel(line: AiProposalLine, locale: String = "en"): String = LocalizationRegistry.get(
        when (line.kind) {
            ADDED -> Strings.ai_budget_kind_added
            "want" -> Strings.ai_budget_kind_want
            "debt" -> Strings.ai_budget_kind_debt
            else -> Strings.ai_budget_kind_need
        },
        locale,
    )

    /** "Includes your $1,800.00 in commitments", for a line that holds some. */
    fun committedLabel(line: AiProposalLine, currency: String, locale: String = "en"): String? {
        if (line.isDebt || !Money.isPositive(line.committed, Money.fractionDigits(currency))) return null
        return LocalizationRegistry.format(Strings.ai_budget_committed, listOf(Money.format(line.committed, currency, locale)), locale)
    }

    /** "You usually spend $450.00 a month", when there is history to go on. */
    fun usualLabel(line: AiProposalLine, currency: String, locale: String = "en"): String? {
        val usual = line.usual ?: return null
        if (line.isDebt) return null
        return LocalizationRegistry.format(Strings.ai_budget_usual, listOf(Money.format(usual, currency, locale)), locale)
    }

    fun attemptsLabel(proposal: AiProposal, locale: String = "en"): String? = if (proposal.attemptsLeft <= 0) null else LocalizationRegistry.format(Strings.ai_budget_try_again, listOf(proposal.attemptsLeft.toString()), locale)

    // ── On the Budget screen ────────────────────────────────────────────

    /**
     * "AI suggests $430.00", for a line whose suggestion differs from what is
     * allocated — or null, when there is none or it is already what the line holds.
     */
    fun suggestionLabel(line: BudgetLine, currency: String, locale: String = "en"): String? {
        val suggested = line.aiSuggested ?: return null
        val digits = Money.fractionDigits(currency)
        if (Money.compare(suggested, line.allocated, digits) == 0) return null
        return LocalizationRegistry.format(Strings.ai_budget_suggests, listOf(Money.format(suggested, currency, locale)), locale)
    }

    /**
     * [suggestionLabel] as the Budget screen shows it, with the rest of the
     * budget in view. The review's numbers fit income as a set — its cuts
     * beside its raises — but the screen offers them one line at a time, so a
     * raise is offered only while the budget has room for it. Over income,
     * the AI suggested more for Dining while the budget was already short.
     */
    fun suggestionLabel(line: BudgetLine, budget: Budget, locale: String = "en"): String? {
        val label = suggestionLabel(line, budget.currency, locale) ?: return null
        val digits = Money.fractionDigits(budget.currency)
        val raise = Money.difference(line.aiSuggested ?: return null, line.allocated, digits) ?: return null
        if (Money.signOf(raise, digits) <= 0) return label
        val room = roomForRaises(budget) ?: return null
        return label.takeIf { Money.compare(raise, room, digits) <= 0 }
    }

    /**
     * What the budget can still give a line without going over income: the
     * room for savings, less a savings target the person fixed. Savings that
     * is only what is left gives way to a raise; a fixed target does not.
     * Null when there is no room.
     */
    fun roomForRaises(budget: Budget): String? {
        val digits = Money.fractionDigits(budget.currency)
        if (budget.shortfall != null) return null
        val target = budget.savings?.takeIf { it.isUserSet }?.allocated ?: "0"
        val room = Money.difference(budget.savingsRoom, target, digits) ?: return null
        return room.takeIf { Money.signOf(it, digits) > 0 }
    }

    /** Whether the Budget screen should ask for this month's review on its own. */
    fun shouldReview(budget: Budget): Boolean = budget.aiReviewDue && !budget.monthEnded
}

@Serializable
private data class SavedDraft(
    val suggestion: String = "",
    val lines: List<SavedLine> = emptyList(),
    val savings: String = "",
)

@Serializable
private data class SavedLine(
    val line: AiProposalLine = AiProposalLine(),
    val amount: String = "",
)
