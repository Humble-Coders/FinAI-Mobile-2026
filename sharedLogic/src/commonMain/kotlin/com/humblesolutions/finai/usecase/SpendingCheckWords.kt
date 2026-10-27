package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.SpendingCheck
import com.humblesolutions.finai.util.Money

/** How one share of income sits against what the rule aims for. */
enum class SpendingStatus {
    ON_TRACK,

    /** Needs or wants past their share. */
    OVER,

    /** An ended month saved less than the aim. */
    SHORT,

    /** A running month has not saved the aim yet: not a failure, still to come. */
    TO_GO,
}

/** Needs, wants or saved: the amount, its share of income, and the aim. */
data class SpendingShareRow(
    val label: String,
    val amount: String,
    val share: String,
    val aim: String,
    val status: SpendingStatus,
    val statusLabel: String,
    /** The share of income, 0 to 1 — the length of its bar. */
    val fraction: Float,
    /** Where the aim sits on that bar, 0 to 1. */
    val aimFraction: Float,
)

/**
 * The budget screen's spending check in words. Every amount and percentage
 * is the server's; this only formats them. The AI's words arrive already free
 * of figures.
 */
object SpendingCheckWords {

    fun title(locale: String = "en"): String = text(Strings.spending_title, locale)

    fun incomeLine(c: SpendingCheck, locale: String = "en"): String = format(Strings.spending_against_income, locale, Money.format(c.income, c.currency, locale))

    fun rows(c: SpendingCheck, locale: String = "en"): List<SpendingShareRow> {
        fun money(amount: String) = Money.format(amount, c.currency, locale)
        fun row(label: String, amount: String, percent: Int, aimPercent: Int, atMost: Boolean, status: SpendingStatus) = SpendingShareRow(
            label = text(label, locale),
            amount = money(amount),
            share = format(Strings.spending_share, locale, percent.toString()),
            aim = format(if (atMost) Strings.spending_aim_at_most else Strings.spending_aim_at_least, locale, aimPercent.toString()),
            status = status,
            statusLabel = text(statusKey(status), locale),
            fraction = (percent / 100f).coerceIn(0f, 1f),
            aimFraction = (aimPercent / 100f).coerceIn(0f, 1f),
        )
        val savedStatus = when {
            !c.savingsShort -> SpendingStatus.ON_TRACK
            c.monthEnded -> SpendingStatus.SHORT
            else -> SpendingStatus.TO_GO
        }
        return listOf(
            row(Strings.spending_needs, c.needs, c.needsPercent, c.needsAimPercent, true, if (c.needsOver) SpendingStatus.OVER else SpendingStatus.ON_TRACK),
            row(Strings.spending_wants, c.wants, c.wantsPercent, c.wantsAimPercent, true, if (c.wantsOver) SpendingStatus.OVER else SpendingStatus.ON_TRACK),
            row(Strings.spending_saved, c.saved, c.savedPercent, c.savingsAimPercent, false, savedStatus),
        )
    }

    /** The AI's tip for [categoryId], as shown under its line; null without one. */
    fun tipFor(c: SpendingCheck?, categoryId: String, locale: String = "en"): String? = c?.categories?.firstOrNull { it.categoryId == categoryId }?.let { format(Strings.spending_tip, locale, it.tip) }

    /** "Ask AI how to spend" before the first check, "Check again" after. */
    fun askLabel(c: SpendingCheck, locale: String = "en"): String = text(if (c.hasAdvice) Strings.spending_ask_again else Strings.spending_ask, locale)

    /** Whether asking can do anything: a first check, or figures that moved, with runs left. */
    fun canAsk(c: SpendingCheck): Boolean = (!c.hasAdvice || c.stale) && c.runsLeft > 0

    fun runsLeft(c: SpendingCheck, locale: String = "en"): String = if (c.runsLeft > 0) format(Strings.spending_runs_left, locale, c.runsLeft.toString()) else text(Strings.spending_runs_none, locale)

    /** "Includes $1,200.00 one-off" under a line, or null when none of it was. */
    fun oneOff(amount: String?, currency: String, locale: String = "en"): String? {
        if (amount == null || !Money.isPositive(amount, Money.fractionDigits(currency))) return null
        return format(Strings.budget_one_off, locale, Money.format(amount, currency, locale))
    }

    /** "This month only" beside a line set by hand for its month alone; null otherwise. */
    fun thisMonthOnly(line: BudgetLine, locale: String = "en"): String? = if (line.thisMonthOnly) text(Strings.budget_this_month_only, locale) else null

    /**
     * Whether saving a line asks "this month only, or every month". Savings is
     * a target that always carries, so it is not asked there.
     */
    fun asksHowLong(line: BudgetLine?): Boolean = line != null && line.slug != SAVINGS_SLUG

    private const val SAVINGS_SLUG = "savings"

    private fun statusKey(status: SpendingStatus): String = when (status) {
        SpendingStatus.ON_TRACK -> Strings.spending_on_track
        SpendingStatus.OVER -> Strings.spending_over
        SpendingStatus.SHORT -> Strings.spending_short
        SpendingStatus.TO_GO -> Strings.spending_to_go
    }

    private fun text(key: String, locale: String) = LocalizationRegistry.get(key, locale)

    private fun format(key: String, locale: String, vararg args: String) = LocalizationRegistry.format(key, args.toList(), locale)
}
