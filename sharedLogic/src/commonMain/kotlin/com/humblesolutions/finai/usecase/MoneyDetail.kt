package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.Debt
import com.humblesolutions.finai.model.Investment
import com.humblesolutions.finai.model.MonthPoint
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The four figures on Home, each of which opens a screen of its own. */
enum class MoneyKind {
    INCOME,
    EXPENSES,
    INVESTMENTS,
    DEBTS,
}

/**
 * What the Income, Expenses, Investments and Debts screens decide, in one place
 * so both apps decide it the same way.
 *
 * Every amount comes from the server. What is decided here is which of those
 * amounts a screen shows, how two of them compare, which rows belong on it, and
 * when something falls due — never a figure the server is the authority on.
 */
object MoneyDetail {

    /** The category a screen lists by, for the two that are one category. */
    const val SAVINGS_SLUG = "savings"
    const val DEBT_PAYMENT_SLUG = "debt_payment"

    /** How many months the trend shows, ending at the month in view. */
    const val TREND_MONTHS = 6

    /** What a screen asks `GET /transactions` for. */
    data class Query(
        val month: String,
        val direction: TransactionDirection?,
        val categorySlug: String?,
    )

    fun query(kind: MoneyKind, month: LocalDate): Query {
        val wire = DashboardMonths.wire(month)
        return when (kind) {
            MoneyKind.INCOME -> Query(wire, TransactionDirection.CREDIT, null)

            MoneyKind.EXPENSES -> Query(wire, TransactionDirection.DEBIT, null)

            // Both ways: money put in and money taken out are both investing.
            MoneyKind.INVESTMENTS -> Query(wire, null, SAVINGS_SLUG)

            MoneyKind.DEBTS -> Query(wire, null, DEBT_PAYMENT_SLUG)
        }
    }

    /**
     * Only the rows that belong on [kind]'s screen for [month], whatever the
     * server sent. A server without the filters sends the whole month — or
     * every month — and drawing all of it under "Income" would be a lie.
     */
    fun rowsFor(kind: MoneyKind, rows: List<Transaction>, month: LocalDate, categories: List<Category>): List<Transaction> {
        val inMonth = TransactionBrowsing.inMonth(rows, month)
        val query = query(kind, month)
        return inMonth.filter { row ->
            val direction = query.direction
            val slug = query.categorySlug
            (direction == null || row.direction == direction) &&
                (slug == null || categories.firstOrNull { it.id == row.categoryId }?.slug == slug)
        }
    }

    /** The screen's headline figure for the month, from the dashboard. */
    fun headline(kind: MoneyKind, data: Dashboard): String = when (kind) {
        MoneyKind.INCOME -> data.income.actual

        MoneyKind.EXPENSES -> data.expenses.actual

        MoneyKind.INVESTMENTS -> data.investments.moved

        // What is owed, as the wizard was told: we never observe a loan's principal.
        MoneyKind.DEBTS -> data.debts.balance
    }

    /** Last month's comparable figure, or null when there is nothing to compare with. */
    fun previous(kind: MoneyKind, data: Dashboard): String? = when (kind) {
        MoneyKind.INCOME -> data.income.previous

        MoneyKind.EXPENSES -> data.expenses.previous

        MoneyKind.INVESTMENTS -> data.investments.previousMoved

        // An outstanding balance has no observed history to compare with; the
        // screen shows what was paid this month instead.
        MoneyKind.DEBTS -> null
    }

    /** A rise or fall against last month, in whole per cent. */
    data class Change(val percent: Int, val rose: Boolean)

    /**
     * [current] against [previous], or null when the comparison would not mean
     * anything: no previous month, or a previous month of nothing, where any
     * rise is "infinitely" more.
     */
    fun change(current: String, previous: String?): Change? {
        val before = minor(previous ?: return null) ?: return null
        val now = minor(current) ?: return null
        if (before == 0L) return null
        val delta = now - before
        // Half away from zero, in whole numbers: 4.5 % reads as 5 %.
        val scaled = delta * 1000 / kotlin.math.abs(before)
        val percent = ((kotlin.math.abs(scaled) + 5) / 10).toInt()
        return Change(percent = percent, rose = delta > 0)
    }

    /** One bar of a screen's trend. */
    data class Bar(
        /** The first of the month, `YYYY-MM-01`. */
        val month: String,
        /** The month's figure, or null for a month with no rows — drawn as a gap, never a zero. */
        val value: String?,
        /** Height in 0..1 against the tallest bar shown. */
        val height: Double,
        val isCurrent: Boolean,
    )

    /** The last [TREND_MONTHS] of [kind]'s figure, oldest first, ending at the month in view. */
    fun bars(kind: MoneyKind, trend: List<MonthPoint>, month: LocalDate): List<Bar> {
        val shown = trend.takeLast(TREND_MONTHS)
        val values = shown.map { valueOf(kind, it) }
        val tallest = values.mapNotNull { it?.let(::minor) }.maxOrNull()?.takeIf { it > 0 }
        val current = DashboardMonths.wire(month)
        return shown.mapIndexed { index, point ->
            val value = values[index]
            val units = value?.let(::minor) ?: 0L
            Bar(
                month = point.month,
                value = value,
                height = if (tallest == null) 0.0 else units.toDouble() / tallest,
                isCurrent = point.month.startsWith(current),
            )
        }
    }

    private fun valueOf(kind: MoneyKind, point: MonthPoint): String? = when (kind) {
        MoneyKind.INCOME -> point.income
        MoneyKind.EXPENSES -> point.expenses
        MoneyKind.INVESTMENTS -> point.invested
        MoneyKind.DEBTS -> point.debtPaid
    }

    /**
     * The date [dueDay] falls on in [month], or null when no day was given.
     * A 31 in a 30-day month is the month's last day, as banks treat it.
     */
    fun dueDate(dueDay: Int?, month: LocalDate): LocalDate? {
        val day = dueDay?.takeIf { it in 1..31 } ?: return null
        val first = DashboardMonths.first(month)
        val last = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).day
        return LocalDate(first.year, first.month, minOf(day, last))
    }

    /** How many of the month's commitments were seen paid, and of how many. */
    data class Met(val seen: Int, val total: Int)

    fun met(commitments: List<Commitment>): Met = Met(commitments.count { it.wasSeen }, commitments.size)

    /** A payment coming up in the month: what, how much, and when. */
    data class Upcoming(val name: String, val amount: String, val due: LocalDate)

    /**
     * The month's payments the person told us the day of, soonest first: the
     * commitments with a due day not yet seen paid, and the debts with both a
     * minimum payment and a due day. Anything without a day is not "due" — it
     * is simply not described, and listing it would invent a date.
     */
    fun upcoming(commitments: List<Commitment>, debts: List<Debt>, month: LocalDate): List<Upcoming> {
        val fromCommitments = commitments.filter { !it.wasSeen }.mapNotNull { item ->
            dueDate(item.dueDay, month)?.let { Upcoming(item.name, item.expected, it) }
        }
        val fromDebts = debts.mapNotNull { debt ->
            val amount = debt.minimumPayment?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            dueDate(debt.dueDay, month)?.let { Upcoming(debt.name, amount, it) }
        }
        return (fromCommitments + fromDebts).sortedBy { it.due }
    }

    /** One holding and its share of everything held, for "Investments by type". */
    data class Share(val name: String, val amount: String, val percent: Int)

    /**
     * Each holding with its share of the total, in whole per cent, largest
     * first. The shares are a display fold of the setup's own amounts; the
     * amounts themselves are the person's, never recomputed.
     */
    fun shares(investments: List<Investment>): List<Share> {
        val units = investments.map { minor(it.amount) ?: 0L }
        val total = units.sum()
        return investments.mapIndexed { index, item ->
            Share(
                name = item.name,
                amount = item.amount,
                percent = if (total <= 0L) 0 else ((units[index] * 1000 / total + 5) / 10).toInt(),
            )
        }.sortedByDescending { minor(it.amount) ?: 0L }
    }

    /**
     * The picture for an obligation, from its name — obligations carry no
     * category. A guess about a drawing, never about money: an unknown name
     * gets the plain document icon.
     */
    fun iconFor(name: String): CategoryIcon {
        // Whole words, not substrings: "card" holds "car", and a credit card
        // drawn as a car is the kind of slip that makes the list look careless.
        // A plural, or a longer word on a long enough stem ("utilities"), still counts.
        val words = name.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        fun any(vararg keys: String) = keys.any { key ->
            words.any { word -> word == key || word == key + "s" || (key.length >= 5 && word.startsWith(key)) }
        }
        return when {
            any("rent", "mortgage", "home", "house", "condo") -> CategoryIcon.HOME
            any("electric", "hydro", "power", "water", "gas", "utilit", "heat") -> CategoryIcon.BOLT
            any("internet", "wifi", "wi-fi", "phone", "mobile", "cell") -> CategoryIcon.PHONE
            any("insurance") -> CategoryIcon.SHIELD
            any("car", "auto", "vehicle", "transit", "parking") -> CategoryIcon.CAR
            any("card", "loan", "emi", "lease") -> CategoryIcon.CARD
            any("school", "tuition", "daycare", "course") -> CategoryIcon.SCHOOL
            any("gym", "netflix", "spotify", "subscription", "membership") -> CategoryIcon.REPEAT
            any("saving", "tfsa", "rrsp", "invest", "sip") -> CategoryIcon.PIGGY
            else -> CategoryIcon.DOCUMENT
        }
    }

    /** "Due 5 Oct", or null when no day was given. */
    fun dueLabelDate(dueDay: Int?, month: LocalDate, locale: String): String? = dueDate(dueDay, month)?.let { date ->
        "${date.day} ${Dates.monthShort(date, locale)}"
    }

    /**
     * A decimal string as whole minor units, for comparing and dividing — never
     * for display. Null for anything that is not money.
     */
    private fun minor(raw: String): Long? {
        val normal = Money.normalize(raw.trim(), 2) ?: return null
        return normal.replace(".", "").toLongOrNull()
    }
}
