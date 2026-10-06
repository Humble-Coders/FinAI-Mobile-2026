package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.model.GoalChanges
import com.humblesolutions.finai.model.GoalHorizon
import com.humblesolutions.finai.model.GoalKind
import com.humblesolutions.finai.model.GoalStatus
import com.humblesolutions.finai.model.GoalsBudgetReason
import com.humblesolutions.finai.model.GoalsPage
import com.humblesolutions.finai.model.NewGoal
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.MoneyInput
import kotlinx.datetime.LocalDate

/**
 * A goal as the person is editing it.
 *
 * [horizon] starts **unanswered**: short or long term decides whether the
 * projection carries the disclaimer, and a silent default would decide it for
 * them (CLAUDE.md → No silent defaults). The optional fields are blank or null
 * for "not given", which the server reads as "none".
 */
data class GoalDraft(
    val name: String = "",
    val kind: GoalKind? = null,
    val horizon: GoalHorizon? = null,
    val target: String = "",
    /** Optional on a new goal — blank starts it at 0. Required when editing: the server cannot clear it. */
    val saved: String = "",
    /** `YYYY-MM-DD`, or null for none. */
    val targetDate: String? = null,
    /** Blank for none. */
    val monthlyContribution: String = "",
)

/** Why a goal cannot be saved yet. */
enum class GoalBlock(val messageKey: String) {
    TOO_MANY(Strings.goals_block_too_many),
    NO_NAME(Strings.goals_block_no_name),
    NAME_TOO_LONG(Strings.goals_block_name_too_long),
    NO_HORIZON(Strings.goals_block_no_horizon),
    NO_TARGET(Strings.goals_block_no_target),
    TARGET_NOT_MONEY(Strings.goals_block_target_invalid),
    TARGET_NEGATIVE(Strings.goals_block_target_negative),
    TARGET_TOO_PRECISE(Strings.goals_block_too_precise),
    TARGET_ZERO(Strings.goals_block_target_zero),
    NO_SAVED(Strings.goals_block_no_saved),
    SAVED_NOT_MONEY(Strings.goals_block_saved_invalid),
    SAVED_NEGATIVE(Strings.goals_block_saved_negative),
    SAVED_TOO_PRECISE(Strings.goals_block_too_precise),
    DATE_IN_PAST(Strings.goals_block_date_in_past),
    CONTRIBUTION_NOT_MONEY(Strings.goals_block_contribution_invalid),
    CONTRIBUTION_NEGATIVE(Strings.goals_block_contribution_negative),
    CONTRIBUTION_TOO_PRECISE(Strings.goals_block_too_precise),
    NOTHING_CHANGED(Strings.goals_block_unchanged),
}

/** Why an amount cannot be added to a goal yet. */
enum class AddMoneyBlock(val messageKey: String) {
    NO_AMOUNT(Strings.goals_add_block_no_amount),
    NOT_MONEY(Strings.goals_add_block_invalid),
    NEGATIVE(Strings.goals_add_block_negative),
    TOO_PRECISE(Strings.goals_block_too_precise),
    ZERO(Strings.goals_add_block_zero),
}

/**
 * Editing goals, and folding the server's goals into words (#52, PRD F5).
 *
 * **Nothing here computes money.** Every figure a goal shows — what it needs a
 * month, when it is done, progress, status — is the server's (backend #65).
 * What lives here is whether a draft may be sent, exactly what an edit
 * changes, and the wording: every sentence is built once, here, so Android and
 * iOS cannot word the same goal differently (CLAUDE.md → Who computes what).
 */
object GoalEdit {

    /** The server's limit on goals still being saved for (`GOAL_LIMIT`). */
    const val OPEN_LIMIT = 20

    /** The server's limit on a name (`NAME_MAX`), after trimming. */
    const val NAME_LIMIT = 255

    /** How many goals Home's card shows. */
    const val HOME_COUNT = 3

    // ── Drafts ──────────────────────────────────────────────────────────

    fun draftOf(goal: Goal): GoalDraft = GoalDraft(
        name = goal.name,
        kind = goal.kind?.takeIf { it != GoalKind.UNKNOWN },
        horizon = goal.horizon.takeIf { it != GoalHorizon.UNKNOWN },
        target = goal.target,
        saved = goal.saved,
        targetDate = goal.targetDate,
        monthlyContribution = goal.monthlyContribution.orEmpty(),
    )

    /**
     * Why [draft] cannot become a new goal, or null when it can.
     *
     * The limit comes first: being told after filling in five fields that a
     * goal cannot be added is the worse time to learn it. [openGoals] is the
     * count the server limits (`GoalsPage.openCount`). The one function behind
     * the disabled Save, the inline notice and the use case's refusal.
     */
    fun blockingReasonForNew(draft: GoalDraft, currency: String, today: LocalDate, openGoals: Int): GoalBlock? {
        if (openGoals >= OPEN_LIMIT) return GoalBlock.TOO_MANY
        common(draft, currency)?.let { return it }
        if (draft.saved.isNotBlank()) amount(draft.saved, currency, ::savedBlock)?.let { return it }
        if (isBefore(draft.targetDate, today)) return GoalBlock.DATE_IN_PAST
        return null
    }

    /**
     * Why [draft] cannot replace [original], or null when it can.
     *
     * The date is only judged when it changed: an overdue goal whose name is
     * being fixed is not refused for a date the person did not touch — the
     * server checks the same way.
     */
    fun blockingReason(original: Goal, draft: GoalDraft, currency: String, today: LocalDate): GoalBlock? {
        common(draft, currency)?.let { return it }
        if (draft.saved.isBlank()) return GoalBlock.NO_SAVED
        amount(draft.saved, currency, ::savedBlock)?.let { return it }
        if (draft.targetDate != original.targetDate && isBefore(draft.targetDate, today)) return GoalBlock.DATE_IN_PAST
        if (changes(original, draft, currency).isEmpty) return GoalBlock.NOTHING_CHANGED
        return null
    }

    private fun common(draft: GoalDraft, currency: String): GoalBlock? {
        val name = draft.name.trim()
        if (name.isEmpty()) return GoalBlock.NO_NAME
        if (name.length > NAME_LIMIT) return GoalBlock.NAME_TOO_LONG
        if (draft.horizon == null || draft.horizon == GoalHorizon.UNKNOWN) return GoalBlock.NO_HORIZON
        if (draft.target.isBlank()) return GoalBlock.NO_TARGET
        amount(draft.target, currency, ::targetBlock)?.let { return it }
        if (!Money.isPositive(normalized(draft.target, currency), Money.fractionDigits(currency))) return GoalBlock.TARGET_ZERO
        if (draft.monthlyContribution.isNotBlank()) {
            amount(draft.monthlyContribution, currency, ::contributionBlock)?.let { return it }
        }
        return null
    }

    /** [raw]'s [MoneyInput] problem, worded by [word]; null when it is an amount. Blank is the caller's call. */
    private fun amount(raw: String, currency: String, word: (MoneyInput.Problem) -> GoalBlock?): GoalBlock? = MoneyInput.problem(raw, currency)?.let(word)

    private fun targetBlock(problem: MoneyInput.Problem): GoalBlock? = when (problem) {
        MoneyInput.Problem.BLANK -> GoalBlock.NO_TARGET
        MoneyInput.Problem.NEGATIVE -> GoalBlock.TARGET_NEGATIVE
        MoneyInput.Problem.NOT_MONEY -> GoalBlock.TARGET_NOT_MONEY
        MoneyInput.Problem.TOO_PRECISE -> GoalBlock.TARGET_TOO_PRECISE
    }

    private fun savedBlock(problem: MoneyInput.Problem): GoalBlock? = when (problem) {
        MoneyInput.Problem.BLANK -> GoalBlock.NO_SAVED
        MoneyInput.Problem.NEGATIVE -> GoalBlock.SAVED_NEGATIVE
        MoneyInput.Problem.NOT_MONEY -> GoalBlock.SAVED_NOT_MONEY
        MoneyInput.Problem.TOO_PRECISE -> GoalBlock.SAVED_TOO_PRECISE
    }

    private fun contributionBlock(problem: MoneyInput.Problem): GoalBlock? = when (problem) {
        MoneyInput.Problem.BLANK -> null
        MoneyInput.Problem.NEGATIVE -> GoalBlock.CONTRIBUTION_NEGATIVE
        MoneyInput.Problem.NOT_MONEY -> GoalBlock.CONTRIBUTION_NOT_MONEY
        MoneyInput.Problem.TOO_PRECISE -> GoalBlock.CONTRIBUTION_TOO_PRECISE
    }

    private fun isBefore(date: String?, today: LocalDate): Boolean = Dates.parse(date)?.let { it < today } ?: false

    /** [raw] at the currency's scale. Only called on drafts that passed their blocking reason. */
    private fun normalized(raw: String, currency: String): String = Money.normalize(raw, Money.fractionDigits(currency)) ?: raw

    private fun optional(raw: String, currency: String): String? = raw.takeIf { it.isNotBlank() }?.let { normalized(it, currency) }

    /**
     * The goal [draft] describes, ready to send; null if it would be refused.
     * Not `newGoal`: Kotlin/Native renames anything starting `new` for
     * Objective-C, and Swift would have to call it `doNewGoal`.
     */
    fun goalToCreate(draft: GoalDraft, currency: String): NewGoal? {
        val horizon = draft.horizon?.takeIf { it != GoalHorizon.UNKNOWN } ?: return null
        if (draft.name.isBlank() || MoneyInput.problem(draft.target, currency) != null) return null
        return NewGoal(
            name = draft.name.trim(),
            kind = draft.kind?.takeIf { it != GoalKind.UNKNOWN },
            horizon = horizon,
            target = normalized(draft.target, currency),
            saved = optional(draft.saved, currency),
            targetDate = draft.targetDate,
            monthlyContribution = optional(draft.monthlyContribution, currency),
        )
    }

    /**
     * Exactly what [draft] changes about [original] — and only that.
     *
     * Amounts compare at the currency's scale, so `"6000"` is not a change from
     * `"6000.00"`. A cleared date, monthly amount or kind becomes a `clear…`
     * flag, because the server only removes a field it is sent an explicit
     * null for (see `GoalChanges`).
     */
    fun changes(original: Goal, draft: GoalDraft, currency: String): GoalChanges {
        val digits = Money.fractionDigits(currency)
        fun same(a: String?, b: String?): Boolean = Money.normalize(a.orEmpty(), digits) == Money.normalize(b.orEmpty(), digits)

        val name = draft.name.trim().takeIf { it != original.name }
        val horizon = draft.horizon?.takeIf { it != GoalHorizon.UNKNOWN && it != original.horizon }
        val target = draft.target.takeIf { !same(it, original.target) }?.let { normalized(it, currency) }
        val saved = draft.saved.takeIf { it.isNotBlank() && !same(it, original.saved) }?.let { normalized(it, currency) }

        val kindWas = original.kind?.takeIf { it != GoalKind.UNKNOWN }
        val kindNow = draft.kind?.takeIf { it != GoalKind.UNKNOWN }

        val contributionNow = draft.monthlyContribution.takeIf { it.isNotBlank() }
        val contributionChanged = (contributionNow == null) != (original.monthlyContribution == null) ||
            (contributionNow != null && !same(contributionNow, original.monthlyContribution))

        return GoalChanges(
            name = name,
            horizon = horizon,
            target = target,
            saved = saved,
            kind = kindNow.takeIf { it != kindWas },
            clearKind = kindWas != null && kindNow == null,
            targetDate = draft.targetDate.takeIf { it != null && it != original.targetDate },
            clearTargetDate = original.targetDate != null && draft.targetDate == null,
            monthlyContribution = contributionNow?.takeIf { contributionChanged }?.let { normalized(it, currency) },
            clearMonthlyContribution = original.monthlyContribution != null && contributionNow == null,
        )
    }

    /** Why [amount] cannot be added yet. Zero is refused: adding nothing is not an add. */
    fun blockingReasonForAdd(amount: String, currency: String): AddMoneyBlock? = when (MoneyInput.problem(amount, currency)) {
        MoneyInput.Problem.BLANK -> AddMoneyBlock.NO_AMOUNT
        MoneyInput.Problem.NEGATIVE -> AddMoneyBlock.NEGATIVE
        MoneyInput.Problem.NOT_MONEY -> AddMoneyBlock.NOT_MONEY
        MoneyInput.Problem.TOO_PRECISE -> AddMoneyBlock.TOO_PRECISE
        null -> if (Money.isPositive(normalized(amount, currency), Money.fractionDigits(currency))) null else AddMoneyBlock.ZERO
    }

    /** [amount] as it will be sent. Call after [blockingReasonForAdd] said yes. */
    fun addAmount(amount: String, currency: String): String = normalized(amount, currency)

    // ── Order ───────────────────────────────────────────────────────────

    /** [ids] with the one at [from] moved to [to] — the order a drag or a "Move up" produces. */
    fun moved(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val list = ids.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    // ── Words ───────────────────────────────────────────────────────────

    /**
     * The picture for a goal, from the category art both apps already draw.
     * Unknown and `other` share the plain tag.
     */
    fun iconOf(kind: GoalKind?): CategoryIcon = when (kind) {
        GoalKind.EMERGENCY_FUND -> CategoryIcon.SHIELD
        GoalKind.VACATION -> CategoryIcon.PLANE
        GoalKind.CAR -> CategoryIcon.CAR
        GoalKind.ELECTRONICS -> CategoryIcon.PHONE
        GoalKind.HOME -> CategoryIcon.HOME
        GoalKind.RETIREMENT -> CategoryIcon.SPA
        GoalKind.WEALTH -> CategoryIcon.PIGGY
        GoalKind.OTHER, GoalKind.UNKNOWN, null -> CategoryIcon.TAG
    }

    fun kindLabel(kind: GoalKind, locale: String = "en"): String = LocalizationRegistry.get(
        when (kind) {
            GoalKind.EMERGENCY_FUND -> Strings.goals_kind_emergency_fund
            GoalKind.VACATION -> Strings.goals_kind_vacation
            GoalKind.CAR -> Strings.goals_kind_car
            GoalKind.ELECTRONICS -> Strings.goals_kind_electronics
            GoalKind.HOME -> Strings.goals_kind_home
            GoalKind.RETIREMENT -> Strings.goals_kind_retirement
            GoalKind.WEALTH -> Strings.goals_kind_wealth
            GoalKind.OTHER, GoalKind.UNKNOWN -> Strings.goals_kind_other
        },
        locale,
    )

    /** How a screen words a goal. [amountsHidden] masks every figure, as Home's eye toggle does. */
    class Words(val currency: String, val locale: String = "en", val amountsHidden: Boolean = false) {
        internal fun text(key: String): String = LocalizationRegistry.get(key, locale)

        internal fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

        internal fun money(amount: String): String = if (amountsHidden) text(Strings.dashboard_hidden_amount) else Money.format(amount, currency, locale)

        internal fun month(yyyyMm: String?): String = DashboardMonths.parse(yyyyMm)
            ?.let { text(Strings.dashboard_month_display, Dates.monthShort(it, locale), it.year.toString()) }
            ?: yyyyMm.orEmpty()
    }

    /** "$1,200 of $6,000". */
    fun amountsLine(goal: Goal, words: Words): String = words.text(Strings.goals_amounts, words.money(goal.saved), words.money(goal.target))

    /**
     * The one line under a goal that says where it stands — always in words,
     * never left to a colour.
     *
     * On track and behind show **both** server figures, the monthly need and
     * what the person plans; the gap between them is never worked out here.
     */
    fun statusLine(goal: Goal, words: Words): String = when (goal.status) {
        GoalStatus.ACHIEVED -> words.text(Strings.goals_status_achieved)

        GoalStatus.OVERDUE -> words.text(
            Strings.goals_status_overdue,
            Dates.parse(goal.targetDate)?.let { Dates.display(it) } ?: goal.targetDate.orEmpty(),
            words.money(goal.remaining),
        )

        GoalStatus.ON_TRACK -> words.text(
            Strings.goals_status_on_track,
            words.money(goal.requiredMonthly.orEmpty()),
            words.money(goal.monthlyContribution.orEmpty()),
        )

        GoalStatus.BEHIND -> words.text(
            Strings.goals_status_behind,
            words.money(goal.requiredMonthly.orEmpty()),
            words.money(goal.monthlyContribution.orEmpty()),
        )

        GoalStatus.OPEN -> when {
            goal.requiredMonthly != null -> words.text(
                Strings.goals_status_needs_until,
                words.money(goal.requiredMonthly),
                words.month(goal.targetDate?.take(7)),
            )

            goal.projectedCompletion != null -> words.text(
                Strings.goals_status_done_by,
                words.month(goal.projectedCompletion),
                words.money(goal.monthlyContribution.orEmpty()),
            )

            else -> words.text(Strings.goals_status_no_plan)
        }

        GoalStatus.UNKNOWN -> ""
    }

    /** True when [goal]'s projection must say it counts no investment growth. */
    fun saysNoGrowth(goal: Goal, page: GoalsPage): Boolean = goal.isLongTerm && !goal.isAchieved && !page.assumesGrowth

    /** "Doesn't include investment growth", for [saysNoGrowth]. */
    fun growthLine(words: Words): String = words.text(Strings.goals_no_growth)

    /** One goal as a single sentence, so a screen reader hears it once. */
    fun description(goal: Goal, words: Words): String {
        val status = statusLine(goal, words)
        val amounts = words.text(Strings.goals_a11y_amounts, goal.name, words.money(goal.saved), words.money(goal.target))
        return if (status.isEmpty()) amounts else words.text(Strings.goals_a11y_with_status, amounts, status)
    }

    /**
     * The goals against the budget, or null when there is nothing worth
     * saying: no open goals, budgets off, or a reason this build does not know.
     * Learning says so plainly — goals still work, only this line waits.
     */
    fun comparisonLine(page: GoalsPage, words: Words): String? {
        if (page.openCount == 0) return null
        val budget = page.budget
        if (budget != null) {
            return budget.shortfall?.let { words.text(Strings.goals_compare_short, words.money(budget.need), words.money(it)) }
                ?: words.text(Strings.goals_compare_covered, words.money(budget.need), words.money(budget.setAside))
        }
        return when (page.budgetReason) {
            GoalsBudgetReason.LEARNING -> words.text(Strings.goals_compare_learning)
            GoalsBudgetReason.NO_SAVINGS_LINE -> words.text(Strings.goals_compare_no_savings)
            GoalsBudgetReason.UNAVAILABLE, GoalsBudgetReason.UNKNOWN, null -> null
        }
    }

    /**
     * The goals Home's card shows: the open ones in priority order, the first
     * [HOME_COUNT] of them. A household whose every goal is reached sees those
     * instead — a card of nothing would read as no goals at all.
     */
    fun forHome(page: GoalsPage): List<Goal> {
        val open = page.goals.filterNot { it.isAchieved }
        return (open.ifEmpty { page.goals }).take(HOME_COUNT)
    }

    /**
     * Home's goals card, worded — the same card on both apps. No rows means
     * the household has no goals yet, and the card invites them to set one.
     * Figures follow Home's eye toggle through [words].
     */
    fun homeCard(page: GoalsPage, words: Words): HomeGoals = HomeGoals(
        forHome(page).map { goal ->
            HomeGoalRow(
                id = goal.id,
                name = goal.name,
                amounts = amountsLine(goal, words),
                status = statusLine(goal, words),
                fraction = goal.fraction,
                icon = iconOf(goal.kind),
                accessibility = description(goal, words),
            )
        },
    )
}

/** Home's goals card: up to three goals, or none and an invitation. */
data class HomeGoals(val rows: List<HomeGoalRow>) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** One goal on Home's card, already in words. */
data class HomeGoalRow(
    val id: String,
    val name: String,
    val amounts: String,
    val status: String,
    /** The bar's length, from the server's percentage. */
    val fraction: Float,
    val icon: CategoryIcon,
    /** The row as one sentence for a screen reader. */
    val accessibility: String,
)
