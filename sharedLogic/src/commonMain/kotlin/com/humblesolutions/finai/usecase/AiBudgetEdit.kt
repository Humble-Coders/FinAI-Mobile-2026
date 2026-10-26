package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiAccept
import com.humblesolutions.finai.model.AiAcceptLine
import com.humblesolutions.finai.model.AiProposal
import com.humblesolutions.finai.model.AiProposalLine
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.util.Money

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

    /** Whether the Budget screen should ask for this month's review on its own. */
    fun shouldReview(budget: Budget): Boolean = budget.aiReviewDue && !budget.monthEnded
}
