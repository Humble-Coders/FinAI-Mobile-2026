package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Plan
import com.humblesolutions.finai.model.PlanCategory
import com.humblesolutions.finai.model.PlanDebt
import com.humblesolutions.finai.model.PlanPreferences
import com.humblesolutions.finai.model.PlanPreferencesToSave
import com.humblesolutions.finai.model.PlanRow
import com.humblesolutions.finai.model.PlanSplitEdit
import com.humblesolutions.finai.model.PlanStatus
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.model.PlanWeight
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.MoneyInput
import com.humblesolutions.finai.util.Percent

/**
 * The choices as the person is editing them (redesign, 2026-10-31): the
 * categories in their order, each with a percentage. It starts at what the
 * plan runs on — their saved choices, or the suggestion until they save — so
 * nothing here is a default the person did not see.
 */
data class PlanWeightsDraft(
    val categories: List<PlanCategory> = emptyList(),
    /** Each category's percentage as typed. */
    val percents: Map<PlanCategory, String> = emptyMap(),
)

/** Why the choices cannot be saved yet. */
enum class PlanChoiceBlock(val messageKey: String) {
    PERCENT_INVALID(Strings.plan_block_percent_invalid),
    NOT_100(Strings.plan_block_not_100),
    NOTHING_CHANGED(Strings.plan_block_unchanged),
}

/** One part of the split, already in words. */
data class SplitRow(
    val kind: SplitKind,
    /** Which row this is, for editing it. */
    val row: PlanRow,
    val goalId: String?,
    val label: String,
    val amount: String,
    /** The amount as the server sent it: what the editor starts from. */
    val rawAmount: String,
    /** A second line — what is still needed, or where the money goes — or null. */
    val detail: String?,
    /** Nothing goes here this month: drawn quieter, never hidden. */
    val isZero: Boolean,
    val icon: CategoryIcon,
    /** The row as one sentence for a screen reader. */
    val accessibility: String,
)

enum class SplitKind { EMERGENCY, GOAL, EXTRA_DEBT, SAVE_OR_INVEST }

/** The surplus card at the top of the plan. */
data class SurplusCard(
    /** "Oct 2026". */
    val month: String,
    val title: String,
    /** The money left over; null when there is none to split — [headline] says why. */
    val amount: String?,
    val headline: String,
    /** The bar under the amount: each part with money in it, in the split's order. */
    val segments: List<SurplusSegment>,
)

/** One part of the surplus bar. [fraction] is a bar's length, 0 to 1 — a ratio, not money. */
data class SurplusSegment(
    val kind: SplitKind,
    val label: String,
    val amount: String,
    val fraction: Float,
)

/** Under "Where it goes": how it was worked out, and the AI's words about it. */
data class SplitNote(val text: String, val why: String?)

/** One category on the plan's choices, in the person's order. */
data class WeightRow(
    val category: PlanCategory,
    val priority: Int,
    val label: String,
    val percent: String,
    val explanation: String,
)

/** One row of the split as the person is changing it. */
data class RowEditDraft(
    val row: PlanRow,
    val goalId: String?,
    val label: String,
    val amount: String,
    /** What the row held before: an unchanged amount is not a change. */
    val original: String,
)

/** Why a row's new amount cannot be saved. */
enum class RowEditBlock(val messageKey: String) {
    BLANK(Strings.plan_block_amount_blank),
    NOT_MONEY(Strings.plan_block_amount_invalid),
    NEGATIVE(Strings.plan_block_amount_negative),
    TOO_PRECISE(Strings.plan_block_too_precise),
    OVER_LIMIT(Strings.plan_block_over_limit),
    UNCHANGED(Strings.plan_block_amount_unchanged),
}

/** The alert after a row is changed: whether to put the amount where it belongs as well. */
data class ApplyQuestion(
    val title: String,
    val body: String,
    val confirm: String,
    val decline: String,
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
 * The plan, folded into what the screens say (backend #76, redesign
 * 2026-10-31) — and the rules for changing its choices and its rows.
 *
 * **Every figure is the server's.** The money left over, the split (the AI's
 * proposal, checked by the server, or the rules'), payoff months, interest
 * and the comparisons all arrive computed; this decides only which of them a
 * screen shows and in which words, once for both apps (CLAUDE.md → Who
 * computes what). Guidance, not advice: "Save or invest" never names a
 * product (PRD §1).
 */
object YourPlan {

    /** The feature the capabilities payload enables the plan with. */
    const val FEATURE = "debt_plan"

    /** How many parts of the split Home's card shows. */
    const val HOME_ROWS = 3

    /** How far the − and + beside a share move it. */
    const val PERCENT_STEP = 5

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
     * The sentence for the money left over, or why there is none — still
     * learning, or a budget whose lines exceed its income.
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

    /** The card at the top: the month, the surplus, and a bar of where it goes. */
    fun surplusCard(plan: Plan, words: Words): SurplusCard {
        val split = hasSplit(plan, words.currency)
        val whole = plan.leftover.toDoubleOrNull() ?: 0.0
        val segments = if (!split || whole <= 0.0) {
            emptyList()
        } else {
            splitParts(plan).mapNotNull { (kind, amount) ->
                val part = amount.toDoubleOrNull() ?: 0.0
                if (part <= 0.0) {
                    null
                } else {
                    SurplusSegment(kind, kindLabel(kind, words), words.money(amount), (part / whole).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        return SurplusCard(
            month = words.month(plan.month),
            title = words.text(Strings.plan_surplus_title),
            amount = if (split) words.money(plan.leftover) else null,
            headline = headline(plan, words),
            segments = segments,
        )
    }

    /** The split's parts by kind, goals together, in the order it is drawn. */
    private fun splitParts(plan: Plan): List<Pair<SplitKind, String>> {
        val split = plan.split
        val goals = split.goals.fold("0") { sum, share -> Money.add(sum, share.amount) ?: sum }
        return listOfNotNull(
            split.emergencyFund?.let { SplitKind.EMERGENCY to it.amount },
            SplitKind.GOAL to goals,
            SplitKind.EXTRA_DEBT to split.extraDebt,
            SplitKind.SAVE_OR_INVEST to split.saveOrInvest,
        )
    }

    private fun kindLabel(kind: SplitKind, words: Words): String = words.text(
        when (kind) {
            SplitKind.EMERGENCY -> Strings.plan_category_emergency
            SplitKind.GOAL -> Strings.plan_category_goals
            SplitKind.EXTRA_DEBT -> Strings.plan_split_extra_debt
            SplitKind.SAVE_OR_INVEST -> Strings.plan_split_save_or_invest
        },
    )

    /**
     * The split, a row each: the Emergency fund (when there is one), each goal
     * given something, extra debt, save or invest. Extra debt and save or
     * invest always show, so a zero there still says where money would go.
     */
    fun splitRows(plan: Plan, words: Words): List<SplitRow> {
        val split = plan.split
        val rows = mutableListOf<SplitRow>()
        split.emergencyFund?.let { fund ->
            val detail = if (isZero(fund.stillNeeded, words.currency)) null else words.text(Strings.plan_split_emergency_detail, words.money(fund.stillNeeded))
            rows += row(SplitKind.EMERGENCY, PlanRow.EMERGENCY_FUND, fund.goalId, fund.name, fund.amount, detail, words)
        }
        split.goals.forEach { share ->
            rows += row(SplitKind.GOAL, PlanRow.GOAL, share.goalId, share.name, share.amount, null, words)
        }
        rows += row(
            SplitKind.EXTRA_DEBT,
            PlanRow.DEBT,
            null,
            words.text(Strings.plan_split_extra_debt),
            split.extraDebt,
            focus(plan)?.let { words.text(Strings.plan_split_extra_debt_to, it.name) },
            words,
        )
        rows += row(SplitKind.SAVE_OR_INVEST, PlanRow.SAVE_OR_INVEST, null, words.text(Strings.plan_split_save_or_invest), split.saveOrInvest, null, words)
        return rows
    }

    private fun row(kind: SplitKind, planRow: PlanRow, goalId: String?, label: String, amount: String, detail: String?, words: Words): SplitRow {
        val money = words.money(amount)
        return SplitRow(
            kind = kind,
            row = planRow,
            goalId = goalId,
            label = label,
            amount = money,
            rawAmount = amount,
            detail = detail,
            isZero = isZero(amount, words.currency),
            icon = iconOf(kind),
            accessibility = listOfNotNull(label, money, detail).joinToString(", "),
        )
    }

    /** How the split was worked out — by the AI from the choices, or by the rules — and the AI's words. */
    fun splitNote(plan: Plan, words: Words): SplitNote? {
        if (!hasSplit(plan, words.currency)) return null
        val text = when {
            plan.split.edited -> words.text(Strings.plan_split_edited)
            plan.split.source == "ai" -> words.text(Strings.plan_split_by_ai)
            else -> words.text(Strings.plan_split_by_rules)
        }
        return SplitNote(text, plan.split.why?.takeIf { it.isNotBlank() && plan.split.source == "ai" })
    }

    /** The picture beside a part of the split, from the category art both apps draw. */
    fun iconOf(kind: SplitKind): CategoryIcon = when (kind) {
        SplitKind.EMERGENCY -> CategoryIcon.SHIELD
        SplitKind.GOAL -> CategoryIcon.TAG
        SplitKind.EXTRA_DEBT -> CategoryIcon.CARD
        SplitKind.SAVE_OR_INVEST -> CategoryIcon.PIGGY
    }

    /** What a goal's share of this month's split is, for the Goals tab; null when the plan gives it nothing. */
    fun suggestedFor(goalId: String, plan: Plan?, words: Words): String? {
        val plan = plan ?: return null
        if (!hasSplit(plan, words.currency)) return null
        val fund = plan.split.emergencyFund
        val amount = if (fund != null && fund.goalId == goalId) {
            fund.amount
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

    // ── A row set by hand ───────────────────────────────────────────────

    /** The editor opens on what the row holds now. */
    fun editDraftOf(row: SplitRow): RowEditDraft = RowEditDraft(row.row, row.goalId, row.label, row.rawAmount, row.rawAmount)

    /** The most [draft]'s row can be set to this month, as the server said; null for no limit but the whole. */
    fun limitFor(plan: Plan, draft: RowEditDraft): String? {
        val limit = plan.split.limits.firstOrNull { it.row == draft.row.wire && (draft.row != PlanRow.GOAL || it.goalId == draft.goalId) }
        return limit?.most ?: plan.leftover
    }

    /** Why [draft] cannot be saved, or null when it can. */
    fun editBlockingReason(plan: Plan, draft: RowEditDraft, currency: String): RowEditBlock? {
        when (MoneyInput.problem(draft.amount, currency)) {
            MoneyInput.Problem.BLANK -> return RowEditBlock.BLANK
            MoneyInput.Problem.NEGATIVE -> return RowEditBlock.NEGATIVE
            MoneyInput.Problem.NOT_MONEY -> return RowEditBlock.NOT_MONEY
            MoneyInput.Problem.TOO_PRECISE -> return RowEditBlock.TOO_PRECISE
            null -> Unit
        }
        val digits = Money.fractionDigits(currency)
        val amount = Money.normalize(draft.amount, digits) ?: return RowEditBlock.NOT_MONEY
        limitFor(plan, draft)?.let { most -> if (Money.compare(amount, most, digits) > 0) return RowEditBlock.OVER_LIMIT }
        if (Money.compare(amount, draft.original, digits) == 0) return RowEditBlock.UNCHANGED
        return null
    }

    fun editBlockText(block: RowEditBlock, plan: Plan, draft: RowEditDraft, words: Words): String = when (block) {
        RowEditBlock.TOO_PRECISE -> words.text(block.messageKey, words.currency)
        RowEditBlock.OVER_LIMIT -> words.text(block.messageKey, words.money(limitFor(plan, draft) ?: plan.leftover))
        else -> words.text(block.messageKey)
    }

    /** "At most $1,035.00 this month", under the field. */
    fun editLimitLine(plan: Plan, draft: RowEditDraft, words: Words): String? = limitFor(plan, draft)?.let { words.text(Strings.plan_edit_most, words.money(it)) }

    /** "The other amounts adjust so the total stays $1,565.00." */
    fun editRebalanceLine(plan: Plan, words: Words): String = words.text(Strings.plan_edit_rebalance, words.money(plan.leftover))

    /** The alert after Save: whether the amount should also go where it belongs. */
    fun applyQuestion(draft: RowEditDraft, words: Words): ApplyQuestion {
        val digits = Money.fractionDigits(words.currency)
        val amount = words.money(Money.normalize(draft.amount, digits) ?: draft.amount)
        val body = when (draft.row) {
            PlanRow.EMERGENCY_FUND, PlanRow.GOAL -> words.text(Strings.plan_apply_goal, draft.label, amount)
            PlanRow.DEBT -> words.text(Strings.plan_apply_debt, amount)
            PlanRow.SAVE_OR_INVEST -> words.text(Strings.plan_apply_save, amount)
        }
        return ApplyQuestion(
            title = words.text(Strings.plan_apply_title, draft.label),
            body = body,
            confirm = words.text(Strings.plan_apply_yes),
            decline = words.text(Strings.plan_apply_no),
        )
    }

    /** The row as it is sent. Call after [editBlockingReason] said yes. */
    fun toEdit(draft: RowEditDraft, apply: Boolean, currency: String): PlanSplitEdit = PlanSplitEdit(
        row = draft.row,
        goalId = draft.goalId.takeIf { draft.row == PlanRow.GOAL },
        amount = Money.normalize(draft.amount, Money.fractionDigits(currency)) ?: draft.amount,
        apply = apply,
    )

    // ── The choices ─────────────────────────────────────────────────────

    fun strategyLabel(strategy: PlanStrategy, locale: String = "en"): String = LocalizationRegistry.get(
        when (strategy) {
            PlanStrategy.AVALANCHE -> Strings.plan_strategy_avalanche
            PlanStrategy.SNOWBALL -> Strings.plan_strategy_snowball
        },
        locale,
    )

    fun categoryLabel(category: PlanCategory, locale: String = "en"): String = LocalizationRegistry.get(
        when (category) {
            PlanCategory.EMERGENCY_FUND -> Strings.plan_category_emergency
            PlanCategory.DEBT -> Strings.plan_category_debt
            PlanCategory.GOALS -> Strings.plan_category_goals
            PlanCategory.SAVE_OR_INVEST, PlanCategory.UNKNOWN -> Strings.plan_category_save
        },
        locale,
    )

    private fun categoryWhy(category: PlanCategory, locale: String): String = LocalizationRegistry.get(
        when (category) {
            PlanCategory.EMERGENCY_FUND -> Strings.plan_category_emergency_why
            PlanCategory.DEBT -> Strings.plan_category_debt_why
            PlanCategory.GOALS -> Strings.plan_category_goals_why
            PlanCategory.SAVE_OR_INVEST, PlanCategory.UNKNOWN -> Strings.plan_category_save_why
        },
        locale,
    )

    fun categoryIcon(category: PlanCategory): CategoryIcon = when (category) {
        PlanCategory.EMERGENCY_FUND -> CategoryIcon.SHIELD
        PlanCategory.DEBT -> CategoryIcon.CARD
        PlanCategory.GOALS -> CategoryIcon.TAG
        PlanCategory.SAVE_OR_INVEST, PlanCategory.UNKNOWN -> CategoryIcon.PIGGY
    }

    /** The choices as the plan screen lists them: in the person's order, each with its share. */
    fun weightRows(preferences: PlanPreferences, words: Words): List<WeightRow> = preferences.weights
        .filter { it.category != PlanCategory.UNKNOWN }
        .mapIndexed { index, weight ->
            WeightRow(
                category = weight.category,
                priority = index + 1,
                label = categoryLabel(weight.category, words.locale),
                percent = Percent.display(weight.percent),
                explanation = categoryWhy(weight.category, words.locale),
            )
        }

    /**
     * The suggestion to add an Emergency fund, while the household has none:
     * it becomes a choice only once it is a goal (product decision,
     * 2026-10-31). Null when it has one.
     */
    fun emergencySuggestion(preferences: PlanPreferences, words: Words): Pair<String, String>? = if (preferences.hasEmergencyFund) {
        null
    } else {
        words.text(Strings.plan_emergency_suggest_title) to words.text(Strings.plan_emergency_suggest_body)
    }

    /** The editor opens on what the plan runs on now. */
    fun weightsDraftOf(preferences: PlanPreferences): PlanWeightsDraft {
        val weights = preferences.weights.filter { it.category != PlanCategory.UNKNOWN }
        return PlanWeightsDraft(
            categories = weights.map { it.category },
            percents = weights.associate { it.category to plainPercent(it.percent) },
        )
    }

    /** `"40.00"` as a person types it: `"40"`. */
    private fun plainPercent(percent: String): String = Percent.display(percent).removeSuffix("%")

    /** [category] one place earlier ([up]) or later in the order. */
    fun moved(draft: PlanWeightsDraft, category: PlanCategory, up: Boolean): PlanWeightsDraft {
        val order = draft.categories.toMutableList()
        val at = order.indexOf(category)
        val to = if (up) at - 1 else at + 1
        if (at < 0 || to !in order.indices) return draft
        order[at] = order[to]
        order[to] = category
        return draft.copy(categories = order)
    }

    fun withPercent(draft: PlanWeightsDraft, category: PlanCategory, text: String): PlanWeightsDraft = draft.copy(percents = draft.percents + (category to text))

    /** [category]'s share moved by [PERCENT_STEP] whole points, kept between 0 and 100. */
    fun stepped(draft: PlanWeightsDraft, category: PlanCategory, up: Boolean): PlanWeightsDraft {
        val now = Percent.normalize(draft.percents[category].orEmpty())?.substringBefore('.')?.toIntOrNull() ?: 0
        val next = (now + if (up) PERCENT_STEP else -PERCENT_STEP).coerceIn(0, 100)
        return withPercent(draft, category, next.toString())
    }

    /** The shares added up, `"95.00"`; null while one is not a percentage. */
    fun total(draft: PlanWeightsDraft): String? = draft.categories.fold<PlanCategory, String?>("0.00") { sum, category ->
        val percent = Percent.normalize(draft.percents[category].orEmpty())
        if (sum == null || percent == null) null else Money.add(sum, percent)
    }

    /** "Total: 95%". */
    fun totalLine(draft: PlanWeightsDraft, words: Words): String? = total(draft)?.let { words.text(Strings.plan_choices_total, Percent.display(it)) }

    /** Why [draft] cannot be saved, or null when it can. Until the person has saved once, the suggestions unchanged are a real answer. */
    fun blockingReason(preferences: PlanPreferences, draft: PlanWeightsDraft): PlanChoiceBlock? {
        val sum = total(draft) ?: return PlanChoiceBlock.PERCENT_INVALID
        if (Money.compare(sum, "100") != 0) return PlanChoiceBlock.NOT_100
        if (preferences.saved && unchanged(preferences, draft)) return PlanChoiceBlock.NOTHING_CHANGED
        return null
    }

    private fun unchanged(preferences: PlanPreferences, draft: PlanWeightsDraft): Boolean {
        val saved = preferences.weights.filter { it.category != PlanCategory.UNKNOWN }
        return saved.map { it.category } == draft.categories &&
            saved.all { Percent.same(it.percent, draft.percents[it.category]) }
    }

    fun blockText(block: PlanChoiceBlock, draft: PlanWeightsDraft, locale: String = "en"): String = when (block) {
        PlanChoiceBlock.NOT_100 -> LocalizationRegistry.format(block.messageKey, listOf(Percent.display(total(draft) ?: "0")), locale)
        else -> LocalizationRegistry.get(block.messageKey, locale)
    }

    /** The choices as they are sent, with the strategy unchanged. Call after [blockingReason] said yes. */
    fun toSave(draft: PlanWeightsDraft, strategy: PlanStrategy): PlanPreferencesToSave = PlanPreferencesToSave(
        strategy = strategy,
        weights = draft.categories.map { PlanWeight(it, Percent.normalize(draft.percents[it].orEmpty()) ?: "0.00") },
    )

    /** Only the strategy changed — the Debts screen's switch. The saved choices are kept. */
    fun strategyOnly(strategy: PlanStrategy): PlanPreferencesToSave = PlanPreferencesToSave(strategy = strategy, weights = null)

    /**
     * "Pay debt before goals", from a diagnostic: debt to the top with the
     * goals' share added to it, the goals given none. Null without a debt choice.
     */
    fun debtFirstChoices(preferences: PlanPreferences): PlanPreferencesToSave? {
        val weights = preferences.weights.filter { it.category != PlanCategory.UNKNOWN }
        val debt = weights.firstOrNull { it.category == PlanCategory.DEBT } ?: return null
        val goals = weights.firstOrNull { it.category == PlanCategory.GOALS }?.percent ?: "0"
        val together = Money.add(Percent.normalize(debt.percent) ?: "0", Percent.normalize(goals) ?: "0") ?: return null
        val rest = weights.filter { it.category != PlanCategory.DEBT && it.category != PlanCategory.GOALS }
        return PlanPreferencesToSave(
            strategy = preferences.strategy,
            weights = listOf(PlanWeight(PlanCategory.DEBT, together)) + rest + PlanWeight(PlanCategory.GOALS, "0.00"),
        )
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
