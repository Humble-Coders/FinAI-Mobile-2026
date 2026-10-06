package com.humblesolutions.finai.ui.dashboard

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.DayPoint
import com.humblesolutions.finai.model.HealthScore
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.usecase.CategoryIcon
import com.humblesolutions.finai.usecase.CategoryIcons
import com.humblesolutions.finai.usecase.CommitmentBlock
import com.humblesolutions.finai.usecase.CommitmentDraft
import com.humblesolutions.finai.usecase.CommitmentEdit
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.DashboardTrend
import com.humblesolutions.finai.usecase.HomeInsights
import com.humblesolutions.finai.usecase.HomeSections
import com.humblesolutions.finai.usecase.ImportedRows
import com.humblesolutions.finai.usecase.ScoreBreakdown
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * The dashboard as the screen reads it (PRD F3).
 *
 * Derived rules are computed vals here rather than work done inside the
 * composable, so the wiring is testable with a plain constructor and no
 * dispatcher (kmp-arch-v2). Anything that is a *decision* lives further in, on
 * [Dashboard] or [DashboardMonths]; what is left here is wording and layout.
 *
 * **No property below adds an expectation to an actual.** Doing so is the
 * double count the server refuses to make, and it would arrive on screen just
 * as wrong for being computed here.
 */
data class DashboardUiState(
    val month: LocalDate = DashboardMonths.current(),
    val data: Dashboard = Dashboard(),
    val locale: String = "en",

    /** First paint. A month change uses [refreshing], so the figures stay up. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Only when there is nothing to show: a failed refresh keeps the month it had. */
    val loadFailed: Boolean = false,
    val errorKey: String? = null,

    /**
     * Whether figures are masked. Session-only and deliberately not saved: it
     * is for the moment somebody is on a train, not a setting, and a dashboard
     * that opens blank because of a tap days ago is a bug report.
     */
    val amountsHidden: Boolean = false,

    /** The newest few rows, whatever month is in view; see `loadRecent`. */
    val recent: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),

    // ── Editing a commitment ────────────────────────────────────────────
    val editingCommitment: Commitment? = null,
    /** A new commitment being typed in; [editingCommitment] is null meanwhile. */
    val addingCommitment: Boolean = false,
    /** Asking "Delete Rent?" before anything is sent. */
    val confirmingCommitmentDelete: Boolean = false,
    val commitmentDraft: CommitmentDraft = CommitmentDraft(),
    val commitmentSaving: Boolean = false,
    val commitmentErrorKey: String? = null,

    // ── The score, the budget, spending and freshness (#46) ─────────────
    /**
     * The capabilities payload, read with the month. Null until it has been
     * read, which draws neither the budget nor the score: a section arriving
     * a moment late is better than one wrongly shown.
     */
    val capabilities: Capabilities? = null,
    /** "Where it went" shows every category rather than the top five. */
    val spendingExpanded: Boolean = false,
    /** The score's breakdown sheet is up. */
    val breakdownOpen: Boolean = false,
    /** `GET /health-score`, read when the sheet opens; null until it answers. */
    val breakdown: HealthScore? = null,
    val breakdownLoading: Boolean = false,
    val breakdownFailed: Boolean = false,
) {
    private val digits: Int get() = data.fractionDigits

    private fun money(amount: String): String = if (amountsHidden) text(Strings.dashboard_hidden_amount) else Money.format(amount, data.currency, locale)

    private fun text(key: String): String = LocalizationRegistry.get(key, locale)

    private fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

    /** `Aug 2026`, in the language the server sent. */
    val monthLabel: String get() = text(
        Strings.dashboard_month_display,
        Dates.monthShort(month, locale),
        month.year.toString(),
    )

    /** The label for the eye, which says what tapping it will do. */
    val hideToggleLabel: String get() =
        text(if (amountsHidden) Strings.dashboard_show_amounts else Strings.dashboard_hide_amounts)

    /** Whether anything is waiting, which is what the bell's dot means. */
    val hasPending: Boolean get() = data.pendingReview > 0

    val notificationsLabel: String get() =
        text(if (hasPending) Strings.dashboard_notifications else Strings.dashboard_notifications_none)

    val canGoBack: Boolean get() = !loading
    val canGoForward: Boolean get() = !loading && DashboardMonths.canGoForward(month)

    val net: String get() = money(data.net)
    val netIsPositive: Boolean get() = data.netIsPositive

    /**
     * "$400.00 more than last month", or null when there is nothing to compare.
     *
     * An absolute difference rather than a percentage: a rise from nothing has
     * no percentage, and "+12%" of a figure nobody remembers is harder to read
     * than the amount itself.
     */
    val changeLabel: String? get() {
        val previous = data.previousNet ?: return null
        val gap = Money.difference(data.net, previous, digits) ?: return null
        return when (Money.signOf(gap, digits)) {
            0 -> text(Strings.dashboard_change_same)
            1 -> text(Strings.dashboard_change_up, money(Money.magnitudeOf(gap, digits).orEmpty()))
            else -> text(Strings.dashboard_change_down, money(Money.magnitudeOf(gap, digits).orEmpty()))
        }
    }

    val incomeAmount: String get() = money(data.income.actual)
    val expensesAmount: String get() = money(data.expenses.actual)
    val investmentsAmount: String get() = money(data.investments.balance)
    val debtsAmount: String get() = money(data.debts.balance)

    /** "of $5,200.00 expected", or null before the wizard has been filled in. */
    val incomeExpectation: String? get() = expectation(data.income.expected)
    val expensesExpectation: String? get() = expectation(data.expenses.expected)

    private fun expectation(expected: String?): String? = expected?.let { text(Strings.dashboard_of_expected, money(it)) }

    /** Only expenses: over on income is good news, and a screen should not scold. */
    val expensesAreOver: Boolean get() = data.expenses.isOverExpected

    val investmentsMovement: String get() = movement(data.investments, Strings.dashboard_set_aside)
    val debtsMovement: String get() = movement(data.debts, Strings.dashboard_paid_down)

    private fun movement(stock: com.humblesolutions.finai.model.Stock, key: String): String = if (stock.movedThisMonth) text(key, money(stock.moved)) else text(Strings.dashboard_nothing_moved)

    val commitments: List<CommitmentRow> get() = data.commitments.map { commitment ->
        CommitmentRow(
            name = commitment.name,
            expected = money(commitment.expected),
            wasSeen = commitment.wasSeen,
            detail = detailFor(commitment),
            editLabel = text(Strings.commitment_edit_hint, commitment.name),
        )
    }

    /** The commitments section is shown once the month is read, empty or not: it is where one is added. */
    val showsCommitments: Boolean get() = !loading && !loadFailed

    val showsCommitmentEditor: Boolean get() = editingCommitment != null || addingCommitment

    val commitmentTitleKey: String
        get() = if (addingCommitment) Strings.commitment_add_title else Strings.commitment_edit_title

    private val commitmentBlock: CommitmentBlock?
        get() = when {
            addingCommitment -> CommitmentEdit.blockingReasonForNew(commitmentDraft, data.currency, data.commitments.size)
            else -> editingCommitment?.let { CommitmentEdit.blockingReason(it, commitmentDraft, data.currency) }
        }

    val canSaveCommitment: Boolean
        get() = showsCommitmentEditor && !commitmentSaving && commitmentBlock == null

    /**
     * The notice under Save. Nothing before a touch: not "nothing changed" on
     * an edit just opened, and not "give it a name" on an add with both
     * fields still empty — the limit is the exception, said at once.
     */
    val commitmentNotice: String?
        get() {
            commitmentErrorKey?.let { return it }
            val block = commitmentBlock ?: return null
            val untouched = addingCommitment && commitmentDraft.name.isBlank() && commitmentDraft.amount.isBlank()
            return when {
                block == CommitmentBlock.NOTHING_CHANGED -> null
                untouched && block != CommitmentBlock.TOO_MANY -> null
                else -> block.messageKey
            }
        }

    /** "Delete Rent?" — naming it, so the wrong one is not deleted by a quick tap. */
    val deleteCommitmentTitle: String
        get() = text(Strings.commitment_delete_confirm_title, editingCommitment?.name.orEmpty())

    val commitmentCurrencySymbol: String get() = Money.symbol(data.currency)

    val commitmentAmountPlaceholder: String
        get() = Money.normalize("0", Money.fractionDigits(data.currency)).orEmpty()

    /**
     * "Seen Aug 2, 2026" — never "Paid".
     *
     * A commitment settled in cash, from another account, or under a name the
     * bank writes differently is indistinguishable from one never paid, so the
     * screen says only what it knows.
     */
    private fun detailFor(commitment: com.humblesolutions.finai.model.Commitment): String {
        val match = commitment.match ?: return text(Strings.dashboard_commitment_not_seen)
        if (commitment.paidADifferentAmount) {
            return text(
                Strings.dashboard_commitment_differs,
                money(match.amount),
                money(commitment.expected),
            )
        }
        val on = Dates.parse(match.occurredOn)?.let { Dates.display(it) } ?: match.occurredOn
        return text(Strings.dashboard_commitment_seen, on)
    }

    val commitmentsSummary: String? get() = data.commitments.takeIf { it.isNotEmpty() }?.let {
        text(
            Strings.dashboard_commitments_summary,
            data.commitmentsSeenCount.toString(),
            it.size.toString(),
        )
    }

    val pendingReviewLabel: String? get() = when {
        data.pendingReview <= 0 -> null
        data.pendingReview == 1 -> text(Strings.dashboard_pending_review_one)
        else -> text(Strings.dashboard_pending_review, data.pendingReview.toString())
    }

    /**
     * The month day by day — shared geometry, so the two apps draw the same
     * line. Null for a month with nothing in it, and from a server too old to
     * send the days: no chart rather than a flat line that looks measured.
     */
    val dailyChart: DashboardTrend.DailyChart? get() = DashboardTrend.daily(data.daily, data.month.ifEmpty { month.toString() })

    /** Under the chart: "Aug 1", the weeks as plain days, and "Aug 31". */
    val dailyTicks: List<String> get() {
        val ticks = dailyChart?.ticks ?: return emptyList()
        val name = Dates.monthShort(month, locale)
        return ticks.mapIndexed { index, tick ->
            if (index == 0 || index == ticks.lastIndex) {
                text(Strings.dashboard_daily_tick, name, tick.day.toString())
            } else {
                tick.day.toString()
            }
        }
    }

    private val latestDay: DayPoint? get() = dailyChart?.let { chart ->
        data.daily.firstOrNull { it.day == chart.days[chart.markerIndex].day }
    }

    /** The latest day's figure, rounded ("$1.5k"), on the marker. Null while amounts are hidden. */
    val dailyMarkerValue: String? get() = if (amountsHidden) null else latestDay?.let { Money.compact(it.net, data.currency, locale) }

    /** A month behind so far, whose figure is written below its point rather than across the line. */
    val dailyMarkerIsLoss: Boolean get() = latestDay?.let { Money.signOf(it.net, digits) < 0 } ?: false

    /** The line in words, for somebody who cannot see it. */
    val dailyDescription: String get() {
        val days = data.daily.filter { it.net.toDoubleOrNull() != null }
        if (days.isEmpty()) return ""
        val lowest = days.minBy { it.net.toDouble() }
        val highest = days.maxBy { it.net.toDouble() }
        return text(
            Strings.dashboard_daily_description,
            monthLabel,
            money(days.first().net),
            money(days.last().net),
            money(lowest.net),
            money(highest.net),
        )
    }

    /** The recent list, worded. Empty when there is nothing to show. */
    val recentRows: List<RecentRow> get() = recent.map { row ->
        val isCredit = row.direction == TransactionDirection.CREDIT
        val amount = text(
            if (isCredit) Strings.dashboard_recent_credit else Strings.dashboard_recent_debit,
            money(row.amount),
        )
        val category = ImportedRows.categoryOf(row, categories)
        val categoryName = ImportedRows.categoryLabel(row, categories, text(Strings.import_extracted_uncategorised)).orEmpty()
        val date = Dates.parse(row.occurredOn)?.let { Dates.display(it) } ?: row.occurredOn
        val title = ImportedRows.titleOf(row)
        RecentRow(
            id = row.id,
            icon = CategoryIcons.forSlug(category?.slug),
            title = title,
            date = date,
            amount = amount,
            isCredit = isCredit,
            category = categoryName,
            isFiled = ImportedRows.isFiled(row),
            description = text(Strings.dashboard_recent_row, title, date, amount, categoryName),
        )
    }

    /**
     * The score, the budget, spending and freshness, worded once in shared
     * code ([HomeInsights]) so the two apps cannot word them apart. Each is
     * null when it is not to be drawn.
     */
    val sections: HomeSections get() = HomeInsights.sectionsNow(data, capabilities, locale, amountsHidden)

    /** The breakdown sheet's rows, or null until `/health-score` answers with a score. */
    val breakdownView: ScoreBreakdown? get() = breakdown?.let { HomeInsights.breakdown(it, locale) }

    /** Nothing recorded at all, so the screen offers a first step instead of zeroes. */
    val showsEmptyState: Boolean get() = !loading && !loadFailed && data.isEmpty
}

/** One commitment as a row: its name, what was expected, and what we saw. */
data class CommitmentRow(
    val name: String,
    val expected: String,
    val wasSeen: Boolean,
    val detail: String,
    /** Read aloud for the row, which opens the editor when tapped. */
    val editLabel: String = "",
)

/** One row of the recent list, already worded. */
data class RecentRow(
    val id: String,
    /** What the tile beside it shows; [CategoryIcon.UNFILED] for a row nothing filed. */
    val icon: CategoryIcon = CategoryIcon.UNFILED,
    val title: String,
    val date: String,
    /** Signed in words — "+ $5.00" or "− $5.00" — so colour is never the only cue. */
    val amount: String,
    val isCredit: Boolean,
    val category: String,
    /** False for a row nothing filed, which is drawn as needing attention. */
    val isFiled: Boolean,
    /** The whole row as one sentence, read once by a screen reader. */
    val description: String,
)
