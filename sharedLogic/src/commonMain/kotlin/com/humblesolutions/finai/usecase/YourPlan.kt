package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanDebt
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.MoneyInput
import com.humblesolutions.finai.util.Percent

/**
 * The four choices as the person is editing them. Each starts at what the
 * plan runs on — their saved choice, or the suggestion until they save one —
 * so nothing here is a default the person did not see.
 */
data class PlanChoicesDraft(
    /** False for "no buffer". */
    val hasBuffer: Boolean = true,
    val buffer: String = "",
    val goalSharePercent: String = "",
    val rateLinePercent: String = "",
    val strategy: PlanStrategy = PlanStrategy.AVALANCHE,
)

/** Why the choices cannot be saved yet. */
enum class PlanChoiceBlock(val messageKey: String) {
    NO_BUFFER_AMOUNT(Strings.plan_block_no_buffer),
    BUFFER_NOT_MONEY(Strings.plan_block_buffer_invalid),
    BUFFER_NEGATIVE(Strings.plan_block_buffer_negative),
    BUFFER_TOO_PRECISE(Strings.plan_block_too_precise),
    BUFFER_ZERO(Strings.plan_block_buffer_zero),
    GOAL_SHARE_INVALID(Strings.plan_block_goal_share_invalid),
    RATE_LINE_INVALID(Strings.plan_block_rate_line_invalid),
    NOTHING_CHANGED(Strings.plan_block_unchanged),
}

/** One part of the split, already in words. */
data class SplitRow(
    val kind: SplitKind,
    val label: String,
    val amount: String,
    /** A second line — progress, or where the money goes — or null. */
    val detail: String?,
    /** Nothing goes here this month: drawn quieter, never hidden. */
    val isZero: Boolean,
    val icon: CategoryIcon,
    /** The row as one sentence for a screen reader. */
    val accessibility: String,
)

enum class SplitKind { BUFFER, GOAL, EXTRA_DEBT, SAVE_OR_INVEST }

/** One choice on the plan screen: what it is set to, why it exists, and the suggestion. */
data class ChoiceRow(
    val label: String,
    val value: String,
    val explanation: String,
    /** "Suggested: 20%" when the choice differs from the suggestion; null when it matches. */
    val suggestion: String?,
)

/** One debt on the Debts screen, in words. */
data class DebtRow(
    val debtId: String,
    val name: String,
    /** "Credit card · 20.99%". */
    val subtitle: String,
    val balance: String,
    /** "Step 1"; null for a debt outside the order. */
    val step: String?,
    /** Payoff month and interest, or why there is none. */
    val payoff: String,
    /** True when [payoff] asks for something — a rate, a bigger payment. */
    val payoffNeedsAttention: Boolean,
    /** "Your extra goes here this month", or "Minimum only: …", or null. */
    val note: String?,
    val icon: CategoryIcon,
    val accessibility: String,
)

/** Home's "Where your extra money goes" card. */
data class HomePlan(
    val headline: String,
    /** Up to three parts of the split with money in them; empty when there is none to split. */
    val rows: List<SplitRow>,
)

/**
 * The plan, folded into what the screens say (backend #76) — and the rules
 * for changing its four choices.
 *
 * **Every figure is the server's.** The money left over, the split, payoff
 * months, interest and the comparisons all arrive computed; this decides only
 * which of them a screen shows and in which words, once for both apps
 * (CLAUDE.md → Who computes what). Guidance, not advice: "Save or invest"
 * never names a product (PRD §1).
 */
object YourPlan {

    /** The feature the capabilities payload enables the plan with. */
    const val FEATURE = "debt_plan"

    /** How many parts of the split Home's card shows. */
    const val HOME_ROWS = 3

    class Words(val currency: String, val locale: String = "en", val amountsHidden: Boolean = false) {
        internal fun text(key: String): String = LocalizationRegistry.get(key, locale)

        internal fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

        internal fun money(amount: String): String = if (amountsHidden) text(Strings.dashboard_hidden_amount) else Money.format(amount, currency, locale)

        internal fun month(yyyyMm: String?): String = DashboardMonths.parse(yyyyMm)
            ?.let { text(Strings.dashboard_month_display, Dates.monthShort(it, locale), it.year.toString()) }
            ?: yyyyMm.orEmpty()
    }

    private fun isZero(amount: String, currency: String): Boolean = !Money.isPositive(amount, Money.fractionDigits(currency))

    // ── The money left over ─────────────────────────────────────────────

    /**
     * The sentence at the top of the plan: what is left over, or why nothing
     * is — still learning, or a budget whose lines exceed its income.
     */
    fun headline(plan: Plan, words: Words): String = when {
        plan.status == PlanStatus.LEARNING -> words.text(Strings.plan_headline_learning)
        plan.shortfall != null -> words.text(Strings.plan_headline_shortfall, words.money(plan.shortfall))
        isZero(plan.leftover, words.currency) -> words.text(Strings.plan_headline_nothing)
        else -> words.text(Strings.plan_headline_leftover, words.money(plan.leftover))
    }

    /** Whether there is money to split, so the split is worth drawing. */
    fun hasSplit(plan: Plan, currency: String): Boolean = plan.status == PlanStatus.READY && plan.shortfall == null && !isZero(plan.leftover, currency)

    /** The debt this month's extra goes to first, or null. */
    fun focus(plan: Plan): PlanDebt? = plan.debts.firstOrNull { it.getsExtraNow }

    /**
     * The split, in the order the money is divided: buffer, goals, extra debt,
     * save or invest. The buffer's row shows only with a buffer; the goals
     * only those given something. Extra debt and save-or-invest always show,
     * so a zero there still says where money would go.
     */
    fun splitRows(plan: Plan, words: Words): List<SplitRow> {
        val split = plan.split
        val rows = mutableListOf<SplitRow>()
        if (plan.preferences.buffer != null) {
            val detail = if (split.bufferSaved != null && split.bufferTarget != null) {
                words.text(Strings.plan_split_buffer_progress, words.money(split.bufferSaved), words.money(split.bufferTarget))
            } else {
                null
            }
            rows += row(SplitKind.BUFFER, words.text(Strings.plan_split_buffer), split.buffer, detail, words)
        }
        split.goals.forEach { share ->
            rows += row(SplitKind.GOAL, share.name, share.amount, words.text(Strings.plan_split_goal_detail), words)
        }
        val focus = focus(plan)
        rows += row(
            SplitKind.EXTRA_DEBT,
            words.text(Strings.plan_split_extra_debt),
            split.extraDebt,
            focus?.let { words.text(Strings.plan_split_extra_debt_to, it.name) },
            words,
        )
        rows += row(
            SplitKind.SAVE_OR_INVEST,
            words.text(Strings.plan_split_save_or_invest),
            split.saveOrInvest,
            words.text(Strings.plan_split_save_or_invest_detail),
            words,
        )
        return rows
    }

    private fun row(kind: SplitKind, label: String, amount: String, detail: String?, words: Words): SplitRow {
        val money = words.money(amount)
        return SplitRow(
            kind = kind,
            label = label,
            amount = money,
            detail = detail,
            isZero = isZero(amount, words.currency),
            icon = iconOf(kind),
            accessibility = listOfNotNull(label, money, detail).joinToString(", "),
        )
    }

    /** The picture beside a part of the split, from the category art both apps draw. */
    fun iconOf(kind: SplitKind): CategoryIcon = when (kind) {
        SplitKind.BUFFER -> CategoryIcon.SHIELD
        SplitKind.GOAL -> CategoryIcon.TAG
        SplitKind.EXTRA_DEBT -> CategoryIcon.CARD
        SplitKind.SAVE_OR_INVEST -> CategoryIcon.PIGGY
    }

    /** What a goal's share of this month's split is, for the Goals tab; null when the plan gives it nothing. */
    fun suggestedFor(goalId: String, plan: Plan?, words: Words): String? {
        val plan = plan ?: return null
        if (!hasSplit(plan, words.currency)) return null
        val amount = if (goalId == plan.preferences.bufferGoalId) {
            plan.split.buffer
        } else {
            plan.split.goals.firstOrNull { it.goalId == goalId }?.amount ?: return null
        }
        if (isZero(amount, words.currency)) return null
        return words.text(Strings.plan_goal_suggests, words.money(amount))
    }

    /** Home's card: the headline and the first few parts of the split that have money in them. */
    fun homeCard(plan: Plan, words: Words): HomePlan = HomePlan(
        headline = headline(plan, words),
        rows = if (hasSplit(plan, words.currency)) splitRows(plan, words).filterNot { it.isZero }.take(HOME_ROWS) else emptyList(),
    )

    // ── The choices ─────────────────────────────────────────────────────

    fun strategyLabel(strategy: PlanStrategy, locale: String = "en"): String = LocalizationRegistry.get(
        when (strategy) {
            PlanStrategy.AVALANCHE -> Strings.plan_strategy_avalanche
            PlanStrategy.SNOWBALL -> Strings.plan_strategy_snowball
        },
        locale,
    )

    /** The four choices as the plan screen lists them. */
    fun choiceRows(preferences: PlanPreferences, words: Words): List<ChoiceRow> {
        val suggested = preferences.suggested
        fun suggestion(differs: Boolean, value: String): String? = if (differs) words.text(Strings.plan_choice_suggested, value) else null

        val bufferValue = preferences.buffer?.let { words.money(it) } ?: words.text(Strings.plan_choice_no_buffer)
        val bufferSuggested = suggested.buffer?.let { words.money(it) } ?: words.text(Strings.plan_choice_no_buffer)
        val digits = Money.fractionDigits(words.currency)
        val bufferDiffers = Money.normalize(preferences.buffer.orEmpty(), digits) != Money.normalize(suggested.buffer.orEmpty(), digits)
        return listOf(
            ChoiceRow(
                label = words.text(Strings.plan_choice_buffer),
                value = bufferValue,
                explanation = words.text(Strings.plan_choice_buffer_why),
                suggestion = suggestion(bufferDiffers && suggested.buffer != null, bufferSuggested),
            ),
            ChoiceRow(
                label = words.text(Strings.plan_choice_goal_share),
                value = Percent.display(preferences.goalSharePercent),
                explanation = words.text(Strings.plan_choice_goal_share_why),
                suggestion = suggestion(!Percent.same(preferences.goalSharePercent, suggested.goalSharePercent), Percent.display(suggested.goalSharePercent)),
            ),
            ChoiceRow(
                label = words.text(Strings.plan_choice_rate_line),
                value = Percent.display(preferences.rateLinePercent),
                explanation = words.text(Strings.plan_choice_rate_line_why),
                suggestion = suggestion(!Percent.same(preferences.rateLinePercent, suggested.rateLinePercent), Percent.display(suggested.rateLinePercent)),
            ),
            ChoiceRow(
                label = words.text(Strings.plan_choice_strategy),
                value = strategyLabel(preferences.strategy, words.locale),
                explanation = words.text(Strings.plan_choice_strategy_why),
                suggestion = suggestion(preferences.strategy != suggested.strategy, strategyLabel(suggested.strategy, words.locale)),
            ),
        )
    }

    /** The editor opens on what the plan runs on now. */
    fun draftOf(preferences: PlanPreferences): PlanChoicesDraft = PlanChoicesDraft(
        hasBuffer = preferences.buffer != null,
        buffer = preferences.buffer.orEmpty(),
        goalSharePercent = Percent.normalize(preferences.goalSharePercent)?.removeSuffix(".00") ?: preferences.goalSharePercent,
        rateLinePercent = Percent.normalize(preferences.rateLinePercent)?.removeSuffix(".00") ?: preferences.rateLinePercent,
        strategy = preferences.strategy,
    )

    /**
     * Why [draft] cannot be saved, or null when it can. Until the person has
     * saved once, saving the suggestions unchanged is a real answer — it
     * keeps the buffer as a goal — so "nothing changed" only applies after.
     */
    fun blockingReason(preferences: PlanPreferences, draft: PlanChoicesDraft, currency: String): PlanChoiceBlock? {
        if (draft.hasBuffer) {
            when (MoneyInput.problem(draft.buffer, currency)) {
                MoneyInput.Problem.BLANK -> return PlanChoiceBlock.NO_BUFFER_AMOUNT
                MoneyInput.Problem.NEGATIVE -> return PlanChoiceBlock.BUFFER_NEGATIVE
                MoneyInput.Problem.NOT_MONEY -> return PlanChoiceBlock.BUFFER_NOT_MONEY
                MoneyInput.Problem.TOO_PRECISE -> return PlanChoiceBlock.BUFFER_TOO_PRECISE
                null -> Unit
            }
            if (!Money.isPositive(Money.normalize(draft.buffer, Money.fractionDigits(currency)).orEmpty(), Money.fractionDigits(currency))) return PlanChoiceBlock.BUFFER_ZERO
        }
        if (Percent.problem(draft.goalSharePercent) != null) return PlanChoiceBlock.GOAL_SHARE_INVALID
        if (Percent.problem(draft.rateLinePercent) != null) return PlanChoiceBlock.RATE_LINE_INVALID
        if (preferences.saved && unchanged(preferences, draft, currency)) return PlanChoiceBlock.NOTHING_CHANGED
        return null
    }

    private fun unchanged(preferences: PlanPreferences, draft: PlanChoicesDraft, currency: String): Boolean {
        val digits = Money.fractionDigits(currency)
        val buffer = if (draft.hasBuffer) Money.normalize(draft.buffer, digits) else null
        return buffer == preferences.buffer?.let { Money.normalize(it, digits) } &&
            Percent.same(draft.goalSharePercent, preferences.goalSharePercent) &&
            Percent.same(draft.rateLinePercent, preferences.rateLinePercent) &&
            draft.strategy == preferences.strategy
    }

    /** The choices as they are sent. Call after [blockingReason] said yes. */
    fun toSave(draft: PlanChoicesDraft, currency: String): PlanPreferencesToSave = PlanPreferencesToSave(
        buffer = if (draft.hasBuffer) Money.normalize(draft.buffer, Money.fractionDigits(currency)) else null,
        goalSharePercent = Percent.normalize(draft.goalSharePercent) ?: draft.goalSharePercent,
        rateLinePercent = Percent.normalize(draft.rateLinePercent) ?: draft.rateLinePercent,
        strategy = draft.strategy,
    )

    /**
     * Only the strategy changed, the rest as saved — the Debts screen's switch.
     * Null until the person has saved their choices once: the first save keeps
     * a buffer as a goal, and that is theirs to confirm in the editor, not a
     * side effect of a switch (the screen opens the editor instead).
     */
    fun strategyOnly(preferences: PlanPreferences, strategy: PlanStrategy): PlanPreferencesToSave? {
        if (!preferences.saved) return null
        return PlanPreferencesToSave(
            buffer = preferences.buffer,
            goalSharePercent = Percent.normalize(preferences.goalSharePercent) ?: preferences.goalSharePercent,
            rateLinePercent = Percent.normalize(preferences.rateLinePercent) ?: preferences.rateLinePercent,
            strategy = strategy,
        )
    }

    /** Said under the editor's Save when saving will keep the buffer as an Emergency fund goal. */
    fun bufferGoalNote(preferences: PlanPreferences, draft: PlanChoicesDraft, locale: String = "en"): String? = if (draft.hasBuffer && preferences.bufferGoalId == null) {
        LocalizationRegistry.get(Strings.plan_choices_goal_note, locale)
    } else {
        null
    }

    fun blockText(block: PlanChoiceBlock, currency: String, locale: String = "en"): String = when (block) {
        PlanChoiceBlock.BUFFER_TOO_PRECISE -> LocalizationRegistry.format(block.messageKey, listOf(currency), locale)
        else -> LocalizationRegistry.get(block.messageKey, locale)
    }

    // ── The debts ───────────────────────────────────────────────────────

    /**
     * The debts as the Debts screen lists them: in payoff order, then the
     * debts that only get their minimum, then those with no rate — "goes last
     * with 'Add the interest rate'" (ticket #76). Within each group the
     * household's own order is kept.
     */
    fun debtRows(plan: Plan, words: Words): List<DebtRow> {
        val ordered = plan.debts.filter { it.order != null }.sortedBy { it.order } +
            plan.debts.filter { it.order == null && !it.missingRate } +
            plan.debts.filter { it.order == null && it.missingRate }
        val line = plan.preferences.rateLinePercent
        return ordered.map { debt -> debtRow(debt, line, words) }
    }

    private fun debtRow(debt: PlanDebt, rateLine: String, words: Words): DebtRow {
        val kind = DebtEdit.kindLabel(debt.kind, words.locale)
        val subtitle = debt.ratePercent?.let { words.text(Strings.debts_subtitle, kind, Percent.display(it)) } ?: kind
        val balance = words.money(debt.balance)
        val (payoff, attention) = when {
            debt.missingRate -> words.text(Strings.debts_payoff_missing_rate) to true
            debt.neverPaidOff -> words.text(Strings.debts_payoff_never) to true
            debt.paidOff != null -> words.text(Strings.debts_payoff_by, words.month(debt.paidOff), words.money(debt.interest)) to false
            else -> words.text(Strings.debts_payoff_unknown) to false
        }
        val note = when {
            debt.getsExtraNow -> words.text(Strings.debts_note_extra_now)

            debt.minimumOnly && debt.ratePercent != null ->
                words.text(Strings.debts_note_minimum_only, Percent.display(debt.ratePercent), Percent.display(rateLine))

            else -> null
        }
        val step = debt.order?.let { words.text(Strings.debts_step, it.toString()) }
        return DebtRow(
            debtId = debt.debtId,
            name = debt.name,
            subtitle = subtitle,
            balance = balance,
            step = step,
            payoff = payoff,
            payoffNeedsAttention = attention,
            note = note,
            icon = DebtEdit.iconOf(debt.kind),
            accessibility = listOfNotNull(step, debt.name, subtitle, balance, payoff, note).joinToString(", "),
        )
    }

    /** "Debt-free by Jan 2028", or why there is no date; null with no debts. */
    fun debtFreeLine(plan: Plan, words: Words): String? {
        if (plan.debts.isEmpty()) return null
        val totals = plan.totals
        return when {
            totals.debtFree != null -> words.text(Strings.debts_free_by, words.month(totals.debtFree))
            plan.debts.any { it.missingRate } -> words.text(Strings.debts_free_missing_rate)
            plan.debts.any { it.neverPaidOff } -> words.text(Strings.debts_free_never)
            else -> null
        }
    }

    /** "$1,234 less interest than paying only the minimums"; null when there is no comparison or no saving. */
    fun interestSavedLine(plan: Plan, words: Words): String? {
        val saved = plan.totals.interestSaved ?: return null
        if (isZero(saved, words.currency)) return null
        return words.text(Strings.debts_interest_saved, words.money(saved))
    }

    /** "$2,147 interest in all"; null without a finite total. */
    fun totalInterestLine(plan: Plan, words: Words): String? = plan.totals.totalInterest?.let { words.text(Strings.debts_total_interest, words.money(it)) }

    /**
     * What the other strategy would cost — the snowball comparison the ticket
     * asks for, said from whichever side the person chose. The server sends
     * the difference signed; only its sign and size are read here.
     */
    fun comparisonLine(plan: Plan, words: Words): String? {
        val difference = plan.totals.otherStrategyDifference ?: return null
        val digits = Money.fractionDigits(words.currency)
        val other = strategyLabel(plan.totals.otherStrategy, words.locale)
        val size = Money.magnitudeOf(difference, digits) ?: return null
        return when (Money.signOf(difference, digits)) {
            0 -> words.text(Strings.debts_compare_same, other)
            1 -> words.text(Strings.debts_compare_costs_more, other, words.money(size))
            else -> words.text(Strings.debts_compare_costs_less, other, words.money(size))
        }
    }
}
