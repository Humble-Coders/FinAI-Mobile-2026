package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.CategorySpend
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.HealthScore
import com.humblesolutions.finai.model.LearningProgress
import com.humblesolutions.finai.model.ScoreComponent
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What Home shows of M4 — the score, the budget, where the money went, and how
 * current it all is (PRD F6, F8, F12) — worded once for both apps.
 *
 * **Nothing here computes a figure.** Every amount, the score and the
 * previous score arrive from the server; this only chooses which to show, in
 * what order, and how to say them. The two pieces of arithmetic are both
 * about presentation: a bar's length (a ratio that is drawn and discarded)
 * and the score's change, the difference of two integers the server sent.
 */
object HomeInsights {

    /** The capability that draws the budget section (backend #56). */
    const val BUDGET_FEATURE = "auto_budget"

    /** The capability that draws the score card (backend #57). */
    const val SCORE_FEATURE = "health_score"

    /** Categories shown before "Show all". */
    const val TOP_CATEGORIES = 5

    /** Budget lines Home has room for; the Budget tab shows the rest. */
    const val HOME_BUDGET_LINES = 4

    /** Past this, the freshness line stops reporting and starts asking for an import. */
    const val STALE_AFTER_DAYS = 30

    /** For a screen: the clock and time zone read here, so Swift passes four arguments. */
    fun sectionsNow(
        data: Dashboard,
        capabilities: Capabilities?,
        locale: String,
        amountsHidden: Boolean,
    ): HomeSections {
        val zone = TimeZone.currentSystemDefault()
        return sections(data, capabilities, locale, amountsHidden, Clock.System.todayIn(zone), zone)
    }

    /**
     * Every M4 section Home can draw for [data]; null for each it should not.
     *
     * A section is drawn only when the capabilities payload enables it **and**
     * the server sent it — so an older server, a feature turned off, or a
     * payload not yet read all mean the section is simply absent. Unknown
     * capabilities read as off, as the tab bar reads them (#47): a section
     * wrongly shown is worse than one that arrives a moment late.
     */
    fun sections(
        data: Dashboard,
        capabilities: Capabilities?,
        locale: String,
        amountsHidden: Boolean,
        today: LocalDate,
        timeZone: TimeZone,
    ): HomeSections {
        val words = Words(locale, data.currency, amountsHidden)
        val showsScore = capabilities?.isEnabled(SCORE_FEATURE) == true && data.healthScore != null
        val showsBudget = capabilities?.isEnabled(BUDGET_FEATURE) == true && data.budget != null
        // One learning card stands in for both, never two.
        val learning = data.learning?.takeIf { !it.ready && (showsScore || showsBudget) }
        return HomeSections(
            freshness = freshness(data, words, today, timeZone),
            learning = learning,
            score = if (showsScore && learning == null) scoreCard(data, words) else null,
            budget = if (showsBudget && learning == null) budgetCard(data, words) else null,
            spending = spending(data.spendByCategory, words),
        )
    }

    // ── Freshness ───────────────────────────────────────────────────────

    private fun freshness(data: Dashboard, words: Words, today: LocalDate, timeZone: TimeZone): FreshnessLine? {
        val latest = Dates.parse(data.asOf?.latestTransactionOn) ?: return null
        val age = latest.daysUntil(today)
        if (age > STALE_AFTER_DAYS) {
            return FreshnessLine(words.text(Strings.home_fresh_stale, Dates.display(latest)), isStale = true)
        }
        val asOf = words.text(Strings.home_fresh_as_of, Dates.display(latest))
        val imported = importedOn(data.asOf?.lastImportAt, timeZone) ?: return FreshnessLine(asOf, isStale = false)
        val ago = when (val days = imported.daysUntil(today)) {
            in Int.MIN_VALUE..0 -> words.text(Strings.home_fresh_import_today)
            1 -> words.text(Strings.home_fresh_import_yesterday)
            else -> words.text(Strings.home_fresh_import_days, days.toString())
        }
        return FreshnessLine(words.text(Strings.home_fresh_line, asOf, ago), isStale = false)
    }

    /** The day an import was made, on this phone's calendar; null when the server sent no instant we can read. */
    private fun importedOn(iso: String?, timeZone: TimeZone): LocalDate? {
        val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return instant.toLocalDateTime(timeZone).date
    }

    // ── The score ───────────────────────────────────────────────────────

    private fun scoreCard(data: Dashboard, words: Words): ScoreCard? {
        val health = data.healthScore ?: return null
        if (!health.isReady) return null
        val score = health.score ?: return null
        val change = health.previousScore?.let { score - it }
        val held = health.notice?.takeIf { it.isLastMonthMissing }?.let { notice ->
            val missing = Dates.parse(notice.month)?.let { Dates.monthShort(it, words.locale) } ?: notice.month
            val from = Dates.parse(health.scoredOn)?.let { Dates.display(it) } ?: health.scoredOn.orEmpty()
            words.text(Strings.home_score_held, missing, from)
        }
        val changeLabel = when {
            change == null -> null
            change > 0 -> words.text(Strings.home_score_up, change.toString())
            change < 0 -> words.text(Strings.home_score_down, (-change).toString())
            else -> words.text(Strings.home_score_same)
        }
        val spoken = when {
            change == null -> words.text(Strings.home_score_a11y, score.toString())
            change > 0 -> words.text(Strings.home_score_a11y_up, score.toString(), change.toString())
            change < 0 -> words.text(Strings.home_score_a11y_down, score.toString(), (-change).toString())
            else -> words.text(Strings.home_score_a11y_same, score.toString())
        }
        return ScoreCard(
            score = score.toString(),
            fraction = (score.coerceIn(0, 100) / 100.0),
            change = changeLabel,
            changeIsDown = change != null && change < 0,
            held = held,
            accessibility = listOfNotNull(spoken, held).joinToString(". "),
        )
    }

    // ── The budget ──────────────────────────────────────────────────────

    private fun budgetCard(data: Dashboard, words: Words): BudgetCard? {
        val budget = data.budget ?: return null
        if (!budget.isReady) return null
        val digits = budget.fractionDigits
        return BudgetCard(
            totals = words.text(Strings.budget_spent_of, words.money(budget.totalSpent), words.money(budget.totalAllocated)),
            shortfall = budget.shortfall?.let { words.text(Strings.budget_shortfall, words.money(it)) },
            rows = BudgetEdit.forHome(budget, HOME_BUDGET_LINES).map { budgetRow(it, digits, words) },
        )
    }

    private fun budgetRow(line: BudgetLine, digits: Int, words: Words): BudgetRow {
        val spent = words.money(line.spent)
        val allocated = words.money(line.allocated)
        val isOver = line.isOver(digits)
        // The server's figure where it sent one (`/dashboard`); the Budget
        // tab's difference of its two figures otherwise.
        val overBy = if (isOver) (line.over?.takeIf { Money.isPositive(it, digits) } ?: line.overBy(digits)) else null
        val over = overBy?.let { words.money(it) }
        return BudgetRow(
            name = line.name,
            icon = CategoryIcons.forSlug(line.slug),
            amounts = words.text(Strings.home_budget_line_amounts, spent, allocated),
            fraction = line.fraction(digits),
            isOver = isOver,
            overLabel = over?.let { words.text(Strings.budget_over_by, it) },
            accessibility = if (over != null) {
                words.text(Strings.budget_line_a11y_over, line.name, spent, allocated, over)
            } else {
                words.text(Strings.budget_line_a11y, line.name, spent, allocated)
            },
        )
    }

    // ── Where it went ───────────────────────────────────────────────────

    private fun spending(entries: List<CategorySpend>, words: Words): SpendingCard? {
        if (entries.isEmpty()) return null
        // The server's order — largest first — is kept as sent.
        val rows = entries.map { entry ->
            val name = entry.name ?: words.text(Strings.home_spend_uncategorised)
            val amount = words.money(entry.spent)
            SpendRow(
                name = name,
                icon = CategoryIcons.forSlug(entry.slug),
                amount = amount,
                accessibility = words.text(Strings.home_spend_row_a11y, name, amount),
            )
        }
        return SpendingCard(
            top = rows.take(TOP_CATEGORIES),
            all = rows,
            showAllLabel = (rows.size - TOP_CATEGORIES).takeIf { it > 0 }?.let { words.text(Strings.home_spend_show_all, it.toString()) },
        )
    }

    // ── The breakdown ───────────────────────────────────────────────────

    /**
     * The score's parts, worded, for the sheet behind the score card.
     *
     * Null when the server sent no score (still learning, or nothing could be
     * scored). Each part describes what it measures and never says what to do
     * — the score is educational guidance, not advice (PRD positioning).
     */
    fun breakdown(health: HealthScore, locale: String): ScoreBreakdown? {
        val score = health.score ?: return null
        val words = Words(locale, currency = "", amountsHidden = false)
        return ScoreBreakdown(
            score = score.toString(),
            rows = health.components.map { breakdownRow(it, words) },
            formula = health.formulaVersion?.let { words.text(Strings.home_breakdown_formula, it) },
            disclaimer = words.text(Strings.home_breakdown_disclaimer),
        )
    }

    private fun breakdownRow(component: ScoreComponent, words: Words): BreakdownRow {
        val part = PARTS[component.key]
        val title = words.text(part?.title ?: Strings.home_part_other)
        val score = component.score?.takeIf { component.available }
        return BreakdownRow(
            title = title,
            score = if (score != null) words.text(Strings.home_breakdown_score, score.toString()) else words.text(Strings.home_breakdown_not_counted),
            fraction = (score ?: 0).coerceIn(0, 100) / 100.0,
            available = score != null,
            sentence = part?.let { words.text(if (score != null) it.counted else it.notCounted) }.orEmpty(),
            accessibility = if (score != null) {
                words.text(Strings.home_breakdown_row_a11y, title, score.toString())
            } else {
                words.text(Strings.home_breakdown_row_a11y_not_counted, title)
            },
        )
    }

    /** The parts formula v1 has; a part from a newer formula is listed under a general title. */
    private val PARTS = mapOf(
        "savings_consistency" to Part(Strings.home_part_savings, Strings.home_part_savings_counted, Strings.home_part_savings_not_counted),
        "spending_vs_budget" to Part(Strings.home_part_budget, Strings.home_part_budget_counted, Strings.home_part_budget_not_counted),
        "debt_payments" to Part(Strings.home_part_debt, Strings.home_part_debt_counted, Strings.home_part_debt_not_counted),
    )

    private class Part(val title: String, val counted: String, val notCounted: String)

    private class Words(val locale: String, val currency: String, val amountsHidden: Boolean) {
        fun text(key: String): String = LocalizationRegistry.get(key, locale)

        fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

        /** Masked like every other figure on Home while the eye is closed. The score is not money and is never masked. */
        fun money(amount: String): String = if (amountsHidden) text(Strings.dashboard_hidden_amount) else Money.format(amount, currency, locale)
    }
}

/** Every M4 section of Home; a null one is not drawn. */
data class HomeSections(
    val freshness: FreshnessLine?,
    /** When set, one learning card stands where the score and the budget would be. */
    val learning: LearningProgress?,
    val score: ScoreCard?,
    val budget: BudgetCard?,
    val spending: SpendingCard?,
)

/** "As of Oct 2, 2026 · last import 3 days ago", or a nudge to import when [isStale]. */
data class FreshnessLine(val text: String, val isStale: Boolean)

data class ScoreCard(
    /** "72" — the server's score, out of 100. */
    val score: String,
    /** How much of the ring to fill, 0 to 1; drawn and discarded, never shown as a number. */
    val fraction: Double,
    /** "+4 since last month", or null with no score last month. */
    val change: String?,
    val changeIsDown: Boolean,
    /** Set when the score is held because last month is not imported yet. */
    val held: String?,
    /** "Money Health Score 72 out of 100, up 4 since last month". */
    val accessibility: String,
)

data class BudgetCard(
    /** "Spent $1,240.00 of $3,000.00". */
    val totals: String,
    val shortfall: String?,
    /** At most [HomeInsights.HOME_BUDGET_LINES], over budget first. */
    val rows: List<BudgetRow>,
)

data class BudgetRow(
    val name: String,
    val icon: CategoryIcon,
    /** "$150.01 of $450.00". */
    val amounts: String,
    /** The bar's length, 0 to 1. */
    val fraction: Double,
    /** Never the only cue: [overLabel] says it in words. */
    val isOver: Boolean,
    val overLabel: String?,
    val accessibility: String,
)

data class SpendingCard(
    /** The first [HomeInsights.TOP_CATEGORIES]. */
    val top: List<SpendRow>,
    /** Every category, for "Show all". */
    val all: List<SpendRow>,
    /** "Show all (3 more)", or null when [top] is everything. */
    val showAllLabel: String?,
)

data class SpendRow(
    val name: String,
    val icon: CategoryIcon,
    val amount: String,
    val accessibility: String,
)

data class ScoreBreakdown(
    val score: String,
    val rows: List<BreakdownRow>,
    /** "Formula v1". */
    val formula: String?,
    val disclaimer: String,
)

data class BreakdownRow(
    val title: String,
    /** "70 / 100", or "Not counted yet". */
    val score: String,
    val fraction: Double,
    /** False for a part the server could not score — never drawn as a zero. */
    val available: Boolean,
    /** What the part measures, in one sentence. */
    val sentence: String,
    val accessibility: String,
)
