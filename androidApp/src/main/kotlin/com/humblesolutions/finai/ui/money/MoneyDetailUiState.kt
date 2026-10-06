package com.humblesolutions.finai.ui.money

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.FinancialSetup
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.usecase.CategoryIcon
import com.humblesolutions.finai.usecase.CategoryIcons
import com.humblesolutions.finai.usecase.CommitmentBlock
import com.humblesolutions.finai.usecase.CommitmentDraft
import com.humblesolutions.finai.usecase.CommitmentEdit
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.ImportedRows
import com.humblesolutions.finai.usecase.MoneyDetail
import com.humblesolutions.finai.usecase.MoneyKind
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** The two halves of the Expenses screen. */
enum class ExpensesTab { TRANSACTIONS, OBLIGATIONS }

/**
 * One of the four money screens — Income, Expenses, Investments or Debts — as
 * the screen reads it. Every amount is the server's; what is worked out here is
 * wording, and which of the server's figures go where (via [MoneyDetail]).
 */
data class MoneyDetailUiState(
    val kind: MoneyKind = MoneyKind.EXPENSES,
    val month: LocalDate = DashboardMonths.current(),
    val locale: String = "en",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadFailed: Boolean = false,
    val errorKey: String? = null,
    val data: Dashboard = Dashboard(),
    val rows: List<Transaction> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val categories: List<Category> = emptyList(),
    val setup: FinancialSetup? = null,
    val tab: ExpensesTab = ExpensesTab.TRANSACTIONS,
    val searching: Boolean = false,
    val query: String = "",
    // ── Adding an obligation ────────────────────────────────────────────
    val obligationDraft: CommitmentDraft? = null,
    val obligationTouched: Boolean = false,
    val savingObligation: Boolean = false,
    val obligationErrorKey: String? = null,
    /** A one-off line after a save — "Added to your monthly obligations". */
    val noticeKey: String? = null,
) {
    private val currency: String get() = data.currency

    private fun money(amount: String): String = Money.format(amount, currency, locale)

    private fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

    val titleKey: String
        get() = when (kind) {
            MoneyKind.INCOME -> Strings.money_income_title
            MoneyKind.EXPENSES -> Strings.money_expenses_title
            MoneyKind.INVESTMENTS -> Strings.money_investments_title
            MoneyKind.DEBTS -> Strings.money_debts_title
        }

    val headlineLabelKey: String
        get() = when (kind) {
            MoneyKind.INCOME -> Strings.money_total_income
            MoneyKind.EXPENSES -> Strings.money_total_expenses
            MoneyKind.INVESTMENTS -> Strings.money_invested_this_month
            MoneyKind.DEBTS -> Strings.money_total_outstanding
        }

    val headline: String get() = money(MoneyDetail.headline(kind, data))

    val monthLabel: String get() = text(Strings.dashboard_month_display, Dates.monthShort(month, locale), month.year.toString())

    /** The months on offer: this one and the eleven before it, newest first. */
    val months: List<LocalDate>
        get() = generateSequence(DashboardMonths.current()) { DashboardMonths.previous(it) }.take(12).toList()

    fun monthName(month: LocalDate): String = text(Strings.dashboard_month_display, Dates.monthShort(month, locale), month.year.toString())

    val change: MoneyDetail.Change? get() = MoneyDetail.change(MoneyDetail.headline(kind, data), MoneyDetail.previous(kind, data))

    /** "+5%", or null. */
    val changeLabel: String?
        get() = change?.let { text(if (it.rose) Strings.money_change_up else Strings.money_change_down, it.percent.toString()) }

    /** Read aloud instead of the arrow and the figure. */
    val changeDescription: String?
        get() = change?.let {
            when {
                it.percent == 0 -> text(Strings.money_change_same)
                it.rose -> text(Strings.money_change_rose, it.percent.toString())
                else -> text(Strings.money_change_fell, it.percent.toString())
            }
        }

    /**
     * Whether the change is good news. More income or more invested is; more
     * spending is not. Colour only — the words say which way it went.
     */
    val changeIsGood: Boolean
        get() = change?.let { if (kind == MoneyKind.EXPENSES) !it.rose else it.rose } ?: true

    /** Debts' line under the balance: what was paid toward them this month. */
    val paidThisMonth: String? get() = if (kind == MoneyKind.DEBTS) text(Strings.money_paid_this_month, money(data.debts.moved)) else null

    // ── Trend ───────────────────────────────────────────────────────────

    val showsTrend: Boolean get() = kind == MoneyKind.INCOME || kind == MoneyKind.INVESTMENTS

    val bars: List<MoneyDetail.Bar> get() = MoneyDetail.bars(kind, data.trend, month)

    fun barMonth(bar: MoneyDetail.Bar): String = Dates.parse(bar.month)?.let { Dates.monthShort(it, locale) }.orEmpty()

    fun barValue(bar: MoneyDetail.Bar): String? = bar.value?.let { Money.compact(it, currency, locale) }

    val trendDescription: String
        get() = bars.joinToString("; ") { bar ->
            bar.value?.let { text(Strings.money_trend_month, barMonth(bar), money(it)) } ?: text(Strings.money_trend_gap, barMonth(bar))
        }

    // ── Transactions ────────────────────────────────────────────────────

    /** The screen's own rows, whatever the server sent, then narrowed by the search. */
    val visibleRows: List<Transaction>
        get() {
            val mine = MoneyDetail.rowsFor(kind, rows, month, categories)
            val words = query.trim().lowercase()
            if (words.isEmpty()) return mine
            return mine.filter { row ->
                ImportedRows.titleOf(row).lowercase().contains(words) ||
                    (ImportedRows.categoryOf(row, categories)?.name?.lowercase()?.contains(words) == true)
            }
        }

    val days: List<ImportedRows.Day> get() = ImportedRows.byDate(visibleRows)

    fun dayLabel(day: ImportedRows.Day): String = Dates.parse(day.date)?.let(Dates::display) ?: day.date

    fun titleOf(row: Transaction): String = ImportedRows.titleOf(row)

    fun categoryLabel(row: Transaction): String = ImportedRows.categoryOf(row, categories)?.name ?: text(Strings.import_extracted_uncategorised)

    fun iconFor(row: Transaction): CategoryIcon = CategoryIcons.forSlug(ImportedRows.categoryOf(row, categories)?.slug)

    fun isCredit(row: Transaction): Boolean = row.direction == TransactionDirection.CREDIT

    fun amountLabel(row: Transaction): String = text(if (isCredit(row)) Strings.dashboard_recent_credit else Strings.dashboard_recent_debit, Money.format(row.amount, row.currency, locale))

    val listTitleKey: String
        get() = when (kind) {
            MoneyKind.INCOME -> Strings.money_income_transactions
            MoneyKind.EXPENSES -> Strings.money_expense_transactions
            MoneyKind.INVESTMENTS -> Strings.money_investment_transactions
            MoneyKind.DEBTS -> Strings.money_debt_transactions
        }

    val emptyMessage: String
        get() = if (query.isNotBlank()) text(Strings.money_no_matches, query.trim()) else text(Strings.money_no_transactions, monthLabel)

    // ── Obligations (Expenses) ──────────────────────────────────────────

    val commitments: List<Commitment> get() = data.commitments

    val metLabel: String
        get() = MoneyDetail.met(commitments).let { text(Strings.money_obligations_met, it.seen.toString(), it.total.toString()) }

    fun dueLabel(dueDay: Int?): String = MoneyDetail.dueLabelDate(dueDay, month, locale)?.let { text(Strings.money_due, it) } ?: text(Strings.money_no_due_day)

    fun amount(raw: String): String = money(raw)

    val obligationBlock: CommitmentBlock?
        get() = obligationDraft?.let { CommitmentEdit.blockingReasonForNew(it, currency, commitments.size) }

    val obligationNotice: CommitmentBlock? get() = obligationBlock.takeIf { obligationTouched }

    val canSaveObligation: Boolean get() = obligationDraft != null && !savingObligation && obligationBlock == null

    val currencySymbol: String get() = Money.symbol(currency)

    val amountPlaceholder: String get() = Money.normalize("0", Money.fractionDigits(currency)).orEmpty()

    // ── Debts ───────────────────────────────────────────────────────────

    val debts get() = setup?.debts.orEmpty()

    fun debtDetail(minimum: String?, rate: String?): String? = listOfNotNull(
        minimum?.takeIf { it.isNotBlank() }?.let { text(Strings.money_minimum_payment, money(it)) },
        rate?.takeIf { it.isNotBlank() }?.let { text(Strings.money_interest_rate, it) },
    ).joinToString(" · ").ifBlank { null }

    val upcoming: List<MoneyDetail.Upcoming> get() = MoneyDetail.upcoming(commitments, debts, month)

    fun upcomingDue(item: MoneyDetail.Upcoming): String = text(Strings.money_due, "${item.due.day} ${Dates.monthShort(item.due, locale)}")

    // ── Investments ─────────────────────────────────────────────────────

    val invested: String get() = money(data.investments.moved)

    val withdrawn: String get() = money(data.investments.withdrawn)

    val shares: List<MoneyDetail.Share> get() = MoneyDetail.shares(setup?.investments.orEmpty())

    fun shareLabel(share: MoneyDetail.Share): String = text(Strings.money_share, share.percent.toString())
}
