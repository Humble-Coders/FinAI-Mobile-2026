package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetCommitment
import com.humblesolutions.finai.model.CategorySpend
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

/** One figure on a card: a label, an amount, and whether it is the card's bottom line. */
data class OverviewRow(
    val label: String,
    val amount: String,
    val isTotal: Boolean = false,
)

/** A commitment on the Budget screen: its name, its amount, and what was seen of it. */
data class OverviewCommitment(
    val name: String,
    val amount: String,
    val status: String,
    val seen: Boolean,
    /** The setup row, for ticking it off; null from a server that predates it. */
    val id: String? = null,
    /** Ticked off by hand, so it can be unticked. */
    val byHand: Boolean = false,
) {
    /** Whether the row opens the tick-off sheet. */
    val canTick: Boolean get() = id != null

    /** One sentence, so a screen reader hears the row once. */
    val description: String get() = "$name, $amount, $status"
}

/** One category of a month that has ended: what was actually spent in it. */
data class OverviewSpend(
    val name: String,
    val slug: String?,
    val amount: String,
)

/**
 * The Budget screen above its lines: the month as the statements tell it,
 * the commitments, what is still to come, and what to do with what is left.
 *
 * **Two kinds of month.** One still running is a plan: the lines warn before
 * money is spent, and "Left at month end" says where the plan lands. One that
 * has ended is a record: every category at what was actually spent, and what
 * was left — so a one-off like a laptop is simply a row in its month, never a
 * line that takes from savings every month after.
 *
 * Every amount is the server's; this only words and formats them.
 */
object BudgetOverview {

    /** Whether the month is shown as its plan (lines, warnings) rather than as it happened. */
    fun showsPlan(budget: Budget): Boolean = !budget.monthEnded

    /** "This month so far", or "How the month went" once it has ended. */
    fun soFarTitle(budget: Budget, locale: String = "en"): String = LocalizationRegistry.get(if (budget.monthEnded) Strings.budget_month_went else Strings.budget_so_far, locale)

    /** Income, spending and what is left; null from a server that predates them. */
    fun soFar(budget: Budget, locale: String = "en"): List<OverviewRow>? {
        val income = budget.monthIncome ?: return null
        val spending = budget.monthSpending ?: return null
        val left = budget.monthLeft ?: return null
        fun money(amount: String) = Money.format(amount, budget.currency, locale)
        fun text(key: String) = LocalizationRegistry.get(key, locale)
        val ended = budget.monthEnded
        return listOf(
            OverviewRow(text(if (ended) Strings.budget_income else Strings.budget_income_received), money(income)),
            OverviewRow(text(Strings.budget_spent), money(spending)),
            OverviewRow(text(Strings.budget_saved), money(budget.monthSaved ?: "0")),
            OverviewRow(text(if (ended) Strings.budget_left_over else Strings.budget_left_now), money(left), isTotal = true),
        )
    }

    /** The month's commitments, each with what was seen of it. */
    fun commitments(budget: Budget, locale: String = "en"): List<OverviewCommitment> = budget.commitments.map { commitment(it, budget, locale) }

    private fun commitment(item: BudgetCommitment, budget: Budget, locale: String): OverviewCommitment {
        val month = Dates.parse(budget.month)
        val paid = Dates.parse(item.paidOn)
        val status = when {
            paid != null -> LocalizationRegistry.format(Strings.budget_commitment_paid, listOf(dayMonth(paid.day, paid.month.ordinal, locale)), locale)

            budget.monthEnded -> LocalizationRegistry.get(Strings.dashboard_commitment_not_seen, locale)

            item.dueDay != null && month != null -> LocalizationRegistry.format(
                Strings.budget_commitment_due,
                listOf(dayMonth(minOf(item.dueDay, daysIn(month.year, month.month.ordinal)), month.month.ordinal, locale)),
                locale,
            )

            else -> LocalizationRegistry.get(Strings.budget_commitment_not_seen_yet, locale)
        }
        return OverviewCommitment(
            name = item.name,
            amount = Money.format(item.paidAmount ?: item.expected, budget.currency, locale),
            status = status,
            seen = item.wasSeen,
            id = item.id,
            byHand = item.paidByHand,
        )
    }

    /**
     * What is still to come and where the month lands; null once the month
     * has ended, or from a server that predates it.
     */
    fun stillExpected(budget: Budget, locale: String = "en"): List<OverviewRow>? {
        if (budget.monthEnded) return null
        val income = budget.incomeExpected ?: return null
        val due = budget.commitmentsDue ?: return null
        val rest = budget.budgetRest ?: return null
        val left = budget.projectedLeft ?: return null
        fun money(amount: String) = Money.format(amount, budget.currency, locale)
        fun text(key: String, amount: String) = LocalizationRegistry.format(key, listOf(money(amount)), locale)
        fun label(key: String) = LocalizationRegistry.get(key, locale)
        val savingsDue = budget.savingsDue?.takeIf { budget.savings != null }
        return listOfNotNull(
            OverviewRow(label(Strings.budget_income_still_expected), text(Strings.budget_plus, income)),
            OverviewRow(label(Strings.budget_commitments_not_seen), text(Strings.budget_minus, due)),
            OverviewRow(label(Strings.budget_rest_of_budget), text(Strings.budget_minus, rest)),
            savingsDue?.let { OverviewRow(label(Strings.budget_savings_still_due), text(Strings.budget_minus, it)) },
            OverviewRow(label(Strings.budget_left_at_month_end), money(left), isTotal = true),
        )
    }

    /**
     * What is free to put to use — income less spending less savings: what
     * was left once the month has ended, else where the plan lands.
     */
    fun leftover(budget: Budget): String? = if (budget.monthEnded) budget.monthLeft else budget.projectedLeft

    /**
     * "You had $283.21 left this month. Put it toward your goals or into
     * savings." — or, when the month spent more than came in, that instead.
     * Null when there is no figure, or it is exactly zero.
     */
    fun suggestion(budget: Budget, locale: String = "en"): String? {
        val left = leftover(budget) ?: return null
        val digits = Money.fractionDigits(budget.currency)
        val sign = Money.signOf(left, digits)
        if (sign == 0) return null
        val amount = Money.format(Money.magnitudeOf(left, digits) ?: return null, budget.currency, locale)
        val key = when {
            sign > 0 && budget.monthEnded -> Strings.budget_suggest_ended
            sign > 0 -> Strings.budget_suggest_running
            budget.monthEnded -> Strings.budget_short_ended
            else -> Strings.budget_short_running
        }
        return LocalizationRegistry.format(key, listOf(amount), locale)
    }

    /** True when [suggestion] is about money left over rather than a month that went short. */
    fun suggestsUsing(budget: Budget): Boolean {
        val left = leftover(budget) ?: return false
        return Money.signOf(left, Money.fractionDigits(budget.currency)) > 0
    }

    /** A month that has ended, category by category, largest first. */
    fun spent(budget: Budget, locale: String = "en"): List<OverviewSpend> = budget.spendingByCategory.map { spend(it, budget, locale) }

    // ── The savings target ──────────────────────────────────────────────

    /** "Up to $1,562.00 is left after your other lines.", when there is room for a target. */
    fun savingsRoom(budget: Budget, locale: String = "en"): String? {
        if (!Money.isPositive(budget.savingsRoom, Money.fractionDigits(budget.currency))) return null
        return LocalizationRegistry.format(Strings.budget_savings_room, listOf(Money.format(budget.savingsRoom, budget.currency, locale)), locale)
    }

    /**
     * The line "Set a savings target" opens the editor on: a new one, on the
     * savings category — or null from a server that does not name it.
     */
    fun savingsTargetToSet(budget: Budget, locale: String = "en"): com.humblesolutions.finai.model.BudgetLine? {
        val id = budget.savingsCategoryId ?: return null
        return com.humblesolutions.finai.model.BudgetLine(
            categoryId = id,
            slug = "savings",
            name = LocalizationRegistry.get(Strings.category_savings, locale),
            suggested = budget.savingsRoom,
        )
    }

    private fun spend(item: CategorySpend, budget: Budget, locale: String): OverviewSpend = OverviewSpend(
        name = item.name ?: LocalizationRegistry.get(Strings.budget_not_filed, locale),
        slug = item.slug,
        amount = Money.format(item.spent, budget.currency, locale),
    )

    private fun dayMonth(day: Int, monthIndex: Int, locale: String): String = LocalizationRegistry.format(Strings.budget_day_month, listOf(LocalizationRegistry.get(MONTHS[monthIndex], locale), day.toString()), locale)

    private fun daysIn(year: Int, monthIndex: Int): Int = when (monthIndex + 1) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private val MONTHS = listOf(
        Strings.month_short_1, Strings.month_short_2, Strings.month_short_3, Strings.month_short_4,
        Strings.month_short_5, Strings.month_short_6, Strings.month_short_7, Strings.month_short_8,
        Strings.month_short_9, Strings.month_short_10, Strings.month_short_11, Strings.month_short_12,
    )
}
