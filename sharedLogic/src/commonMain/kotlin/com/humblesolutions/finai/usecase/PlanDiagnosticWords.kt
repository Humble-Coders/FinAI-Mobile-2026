package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.PlanDiagnostic
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

/** One place money goes: what, how much, and a line under it. */
data class DiagnosticRow(
    val label: String,
    val amount: String,
    val detail: String? = null,
    /** Nothing goes here this month — drawn quieter, never hidden. */
    val isZero: Boolean = false,
)

/** One thing that has to come before investing, and whether it is done. */
data class DiagnosticCheck(
    val done: Boolean,
    val text: String,
)

/** Why the savings figures cannot be run as typed. */
enum class DiagnosticBlock(val messageKey: String) {
    AMOUNT_INVALID(Strings.diagnostic_block_amount),
}

/**
 * Your plan's diagnostics in words: the rows, checks and sentences both apps
 * show around the server's figures. Every amount is the server's; this only
 * formats and words it. The AI's own words arrive already free of figures.
 */
object PlanDiagnosticWords {

    fun title(kind: String, locale: String = "en"): String = text(if (kind == PlanDiagnostics.ALLOCATION) Strings.diagnostic_allocation_title else Strings.diagnostic_invest_title, locale)

    fun question(kind: String, locale: String = "en"): String = text(if (kind == PlanDiagnostics.ALLOCATION) Strings.diagnostic_allocation_question else Strings.diagnostic_invest_question, locale)

    /** Why the form cannot run, or null. Blank is "use what was last run". */
    fun blockingReason(savingsTarget: String, savingsNow: String, currency: String): DiagnosticBlock? {
        val digits = Money.fractionDigits(currency)
        listOf(savingsTarget, savingsNow).forEach { if (it.isNotBlank() && !Money.isMoney(it, digits)) return DiagnosticBlock.AMOUNT_INVALID }
        return null
    }

    /** "3 fresh runs left this month", or that none are. */
    fun runsLeft(diagnostics: PlanDiagnostics, kind: String, locale: String = "en"): String {
        val left = diagnostics.runsLeftFor(kind)
        return if (left > 0) format(Strings.diagnostic_runs_left, locale, left.toString()) else text(Strings.diagnostic_runs_none, locale)
    }

    // ── Pay debt, fund goals, or keep in savings? ───────────────────────

    /** Where this month's money left over goes, in the plan's order. */
    fun allocationRows(d: PlanDiagnostic, locale: String = "en"): List<DiagnosticRow> {
        fun money(amount: String) = Money.format(amount, d.currency, locale)
        val digits = Money.fractionDigits(d.currency)
        fun zero(amount: String) = !Money.isPositive(amount, digits)
        val savingsDetail = if (zero(d.savingsGap)) {
            format(Strings.diagnostic_savings_reached, locale, money(d.savingsTarget))
        } else {
            format(Strings.diagnostic_savings_gap, locale, money(d.savingsGap), money(d.savingsTarget))
        }
        val rows = mutableListOf(DiagnosticRow(text(Strings.diagnostic_to_savings, locale), money(d.toSavings), savingsDetail, zero(d.toSavings)))
        if (d.goals.isEmpty()) {
            rows += DiagnosticRow(text(Strings.diagnostic_to_goals, locale), money(d.toGoals), null, zero(d.toGoals))
        } else {
            d.goals.forEach { rows += DiagnosticRow(it.name, money(it.amount), text(Strings.diagnostic_goal_detail, locale), zero(it.amount)) }
        }
        val debtLabel = d.debtFirst?.let { format(Strings.diagnostic_to_debt_named, locale, it) } ?: text(Strings.diagnostic_to_debt, locale)
        val debtDetail = d.debtFirstRatePercent?.let { format(Strings.diagnostic_debt_detail, locale, it) }
        rows += DiagnosticRow(debtLabel, money(d.toDebt), debtDetail, zero(d.toDebt))
        rows += DiagnosticRow(text(Strings.diagnostic_to_invest, locale), money(d.toInvest), null, zero(d.toInvest))
        return rows
    }

    /**
     * "Every $100.00 a month on Visa (20.99%) saves about $136.00 of interest
     * in its first year. Invested at an assumed 5.00% a year, it might earn
     * about $32.00." — or null without a debt to compare.
     */
    fun comparison(d: PlanDiagnostic, locale: String = "en"): String? {
        val debt = d.debtFirst ?: return null
        val rate = d.debtFirstRatePercent ?: return null
        val per100 = d.per100 ?: return null
        val debtGain = d.debtPer100 ?: return null
        val investGain = d.investPer100 ?: return null
        fun money(amount: String) = Money.format(amount, d.currency, locale)
        return format(Strings.diagnostic_comparison, locale, money(per100), debt, rate, money(debtGain), d.assumedReturnPercent.orEmpty(), money(investGain))
    }

    /** "Debt-free by Jul 2030 instead of Dec 2031, with $1,004.37 less interest." — or null. */
    fun payoff(d: PlanDiagnostic, locale: String = "en"): String? {
        val free = Dates.parse(d.debtFree) ?: return null
        val minimums = Dates.parse(d.debtFreeMinimums) ?: return null
        val saved = d.interestSaved ?: return null
        if (!Money.isPositive(saved, Money.fractionDigits(d.currency))) return null
        fun month(date: kotlinx.datetime.LocalDate) = format(Strings.dashboard_month_display, locale, Dates.monthShort(date, locale), date.year.toString())
        return format(Strings.diagnostic_payoff, locale, month(free), month(minimums), Money.format(saved, d.currency, locale))
    }

    fun stepLabel(key: String, locale: String = "en"): String = text(
        when (key) {
            "savings" -> Strings.diagnostic_to_savings
            "goals" -> Strings.diagnostic_to_goals
            "debt" -> Strings.diagnostic_to_debt
            else -> Strings.diagnostic_to_invest
        },
        locale,
    )

    // ── Should I invest, and how much? ──────────────────────────────────

    /** What has to come before investing, each done or not. */
    fun investChecks(d: PlanDiagnostic, locale: String = "en"): List<DiagnosticCheck> {
        fun money(amount: String) = Money.format(amount, d.currency, locale)
        val line = d.rateLinePercent.orEmpty()
        return listOf(
            if (d.bufferFull == true) {
                DiagnosticCheck(true, format(Strings.diagnostic_check_savings_done, locale, money(d.savingsTarget)))
            } else {
                DiagnosticCheck(false, format(Strings.diagnostic_check_savings_short, locale, money(d.savingsGap), money(d.savingsTarget)))
            },
            if (d.expensiveDebts.isEmpty()) {
                DiagnosticCheck(true, format(Strings.diagnostic_check_debt_done, locale, line))
            } else {
                DiagnosticCheck(false, format(Strings.diagnostic_check_debt_open, locale, d.expensiveDebts.joinToString(", "), line))
            },
            DiagnosticCheck(d.goalsCovered == true, text(if (d.goalsCovered == true) Strings.diagnostic_check_goals_done else Strings.diagnostic_check_goals_short, locale)),
        )
    }

    fun investRows(d: PlanDiagnostic, locale: String = "en"): List<DiagnosticRow> {
        fun money(amount: String) = Money.format(amount, d.currency, locale)
        val digits = Money.fractionDigits(d.currency)
        return listOfNotNull(
            DiagnosticRow(text(Strings.diagnostic_free_to_invest, locale), money(d.toInvest), null, !Money.isPositive(d.toInvest, digits)),
            d.existingInvestments?.let { DiagnosticRow(text(Strings.diagnostic_already_invested, locale), money(it)) },
        )
    }

    private fun text(key: String, locale: String) = LocalizationRegistry.get(key, locale)

    private fun format(key: String, locale: String, vararg args: String) = LocalizationRegistry.format(key, args.toList(), locale)
}
