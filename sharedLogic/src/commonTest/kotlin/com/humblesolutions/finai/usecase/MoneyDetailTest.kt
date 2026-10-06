package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.CommitmentMatch
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.Debt
import com.humblesolutions.finai.model.Flow
import com.humblesolutions.finai.model.Investment
import com.humblesolutions.finai.model.MonthPoint
import com.humblesolutions.finai.model.Stock
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyDetailTest {

    private val october = LocalDate(2026, 10, 1)

    @Test
    fun each_screen_asks_for_its_own_rows() {
        assertEquals(TransactionDirection.CREDIT, MoneyDetail.query(MoneyKind.INCOME, october).direction)
        assertEquals(TransactionDirection.DEBIT, MoneyDetail.query(MoneyKind.EXPENSES, october).direction)
        assertEquals("savings", MoneyDetail.query(MoneyKind.INVESTMENTS, october).categorySlug)
        assertNull(MoneyDetail.query(MoneyKind.INVESTMENTS, october).direction, "money in and out of savings both count")
        assertEquals("debt_payment", MoneyDetail.query(MoneyKind.DEBTS, october).categorySlug)
        assertEquals("2026-10", MoneyDetail.query(MoneyKind.DEBTS, october).month)
    }

    @Test
    fun a_server_that_ignores_the_filters_still_shows_only_the_screens_rows() {
        val savings = Category(id = "s", slug = "savings")
        val rows = listOf(
            Transaction(id = "pay", occurredOn = "2026-10-02", direction = TransactionDirection.CREDIT),
            Transaction(id = "coffee", occurredOn = "2026-10-03", direction = TransactionDirection.DEBIT),
            Transaction(id = "tfsa", occurredOn = "2026-10-04", direction = TransactionDirection.DEBIT, categoryId = "s"),
            Transaction(id = "september", occurredOn = "2026-09-30", direction = TransactionDirection.CREDIT),
        )

        assertEquals(listOf("pay"), MoneyDetail.rowsFor(MoneyKind.INCOME, rows, october, listOf(savings)).map { it.id })
        assertEquals(listOf("coffee", "tfsa"), MoneyDetail.rowsFor(MoneyKind.EXPENSES, rows, october, listOf(savings)).map { it.id })
        assertEquals(listOf("tfsa"), MoneyDetail.rowsFor(MoneyKind.INVESTMENTS, rows, october, listOf(savings)).map { it.id })
    }

    @Test
    fun a_change_is_whole_per_cent_rounded_half_away_from_zero() {
        assertEquals(MoneyDetail.Change(5, rose = true), MoneyDetail.change("105.00", "100.00"))
        assertEquals(MoneyDetail.Change(5, rose = false), MoneyDetail.change("95.50", "100.00"), "4.5% reads as 5%")
        assertEquals(MoneyDetail.Change(0, rose = false), MoneyDetail.change("100.00", "100.00"))
        assertEquals(MoneyDetail.Change(150, rose = true), MoneyDetail.change("250", "100"))
    }

    @Test
    fun there_is_no_change_without_something_to_compare_with() {
        assertNull(MoneyDetail.change("100.00", null), "no previous month")
        assertNull(MoneyDetail.change("100.00", "0.00"), "a rise from nothing is not a percentage")
    }

    @Test
    fun the_headline_and_its_comparison_come_from_the_dashboard() {
        val data = Dashboard(
            income = Flow(actual = "5000.00", previous = "4000.00"),
            expenses = Flow(actual = "3000.00", previous = null),
            investments = Stock(balance = "40000.00", moved = "500.00", previousMoved = "400.00"),
            debts = Stock(balance = "12000.00", moved = "300.00", previousMoved = "300.00"),
        )

        assertEquals("5000.00", MoneyDetail.headline(MoneyKind.INCOME, data))
        assertEquals("500.00", MoneyDetail.headline(MoneyKind.INVESTMENTS, data))
        assertEquals("12000.00", MoneyDetail.headline(MoneyKind.DEBTS, data), "what is owed, as the wizard was told")
        assertNull(MoneyDetail.previous(MoneyKind.DEBTS, data), "a balance has no observed history")
        assertNull(MoneyDetail.previous(MoneyKind.EXPENSES, data))
    }

    @Test
    fun bars_are_the_last_six_months_and_a_gap_stays_a_gap() {
        val trend = (5..10).map { m ->
            val month = "2026-${m.toString().padStart(2, '0')}-01"
            if (m == 7) MonthPoint(month = month) else MonthPoint(month = month, net = "0", income = "${m}000.00")
        } + emptyList()
        val longer = listOf(MonthPoint(month = "2026-04-01", income = "99999.00")) + trend

        val bars = MoneyDetail.bars(MoneyKind.INCOME, longer, october)

        assertEquals(6, bars.size)
        assertEquals("2026-05-01", bars.first().month, "the oldest month falls off")
        assertNull(bars[2].value, "July had no rows")
        assertEquals(0.0, bars[2].height)
        assertEquals(1.0, bars.last().height, "October is the tallest")
        assertTrue(bars.last().isCurrent)
        assertEquals(0.5, bars.first().height, "5000 of 10000")
    }

    @Test
    fun a_due_day_past_the_months_end_is_its_last_day() {
        assertEquals(LocalDate(2026, 10, 5), MoneyDetail.dueDate(5, october))
        assertEquals(LocalDate(2026, 2, 28), MoneyDetail.dueDate(31, LocalDate(2026, 2, 1)))
        assertNull(MoneyDetail.dueDate(null, october))
        assertNull(MoneyDetail.dueDate(32, october))
    }

    @Test
    fun upcoming_is_what_has_a_day_and_is_not_yet_seen_soonest_first() {
        val commitments = listOf(
            Commitment(name = "Rent", expected = "1800.00", dueDay = 1, match = CommitmentMatch(id = "r")),
            Commitment(name = "Car EMI", expected = "450.00", dueDay = 10),
            Commitment(name = "Phone", expected = "65.00"),
        )
        val debts = listOf(
            Debt(name = "Visa", balance = "2500.00", minimumPayment = "75.00", dueDay = 15),
            Debt(name = "Student loan", balance = "9000.00", minimumPayment = "120.00"),
            Debt(name = "Line of credit", balance = "1000.00", dueDay = 3),
        )

        val upcoming = MoneyDetail.upcoming(commitments, debts, october)

        assertEquals(listOf("Car EMI", "Visa"), upcoming.map { it.name }, "rent was seen paid; the rest have no day or no amount")
        assertEquals(LocalDate(2026, 10, 10), upcoming.first().due)
    }

    @Test
    fun the_met_count_is_what_was_seen() {
        val met = MoneyDetail.met(listOf(Commitment(match = CommitmentMatch(id = "a")), Commitment(), Commitment()))
        assertEquals(MoneyDetail.Met(seen = 1, total = 3), met)
    }

    @Test
    fun shares_sum_to_about_a_hundred_and_the_largest_comes_first() {
        val shares = MoneyDetail.shares(
            listOf(Investment("FD", "5000.00"), Investment("Mutual funds", "25000.00"), Investment("Stocks", "10000.00")),
        )

        assertEquals(listOf("Mutual funds", "Stocks", "FD"), shares.map { it.name })
        assertEquals(listOf(63, 25, 13), shares.map { it.percent })
    }

    @Test
    fun nothing_held_is_no_share_not_a_division_by_zero() {
        assertEquals(listOf(0), MoneyDetail.shares(listOf(Investment("Empty", "0"))).map { it.percent })
    }

    @Test
    fun an_obligation_is_drawn_from_its_name_and_an_unknown_one_plainly() {
        assertEquals(CategoryIcon.HOME, MoneyDetail.iconFor("Rent"))
        assertEquals(CategoryIcon.BOLT, MoneyDetail.iconFor("Hydro bill"))
        assertEquals(CategoryIcon.PHONE, MoneyDetail.iconFor("Internet Bill"))
        assertEquals(CategoryIcon.CARD, MoneyDetail.iconFor("Credit card"))
        assertEquals(CategoryIcon.CAR, MoneyDetail.iconFor("Car loan EMI"), "the first match wins")
        assertEquals(CategoryIcon.DOCUMENT, MoneyDetail.iconFor("Something else"))
    }
}
