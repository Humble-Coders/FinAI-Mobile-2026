package com.humblesolutions.finai.ui.dashboard

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.CommitmentMatch
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.Flow
import com.humblesolutions.finai.model.MonthPoint
import com.humblesolutions.finai.model.Stock
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the screen renders, with plain constructors and no dispatcher. */
class DashboardUiStateTest {

    private fun state(data: Dashboard, month: LocalDate = LocalDate(2026, 8, 1)) = DashboardUiState(month = month, data = data, loading = false)

    private fun august(
        net: String = "1500.00",
        previousNet: String? = null,
        income: Flow = Flow("5000.00"),
        expenses: Flow = Flow("3500.00"),
        investments: Stock = Stock("40000.00", "0"),
        debts: Stock = Stock("12000.00", "0"),
        commitments: List<Commitment> = emptyList(),
        trend: List<MonthPoint> = emptyList(),
        pendingReview: Int = 0,
    ) = Dashboard(
        month = "2026-08-01",
        currency = "CAD",
        net = net,
        previousNet = previousNet,
        income = income,
        expenses = expenses,
        investments = investments,
        debts = debts,
        commitments = commitments,
        trend = trend,
        pendingReview = pendingReview,
    )

    @Test
    fun the_month_is_labelled_without_a_day() {
        assertEquals("Aug 2026", state(august()).monthLabel)
    }

    @Test
    fun there_is_no_comparison_without_a_previous_month() {
        assertNull(state(august(previousNet = null)).changeLabel)
    }

    @Test
    fun a_better_month_reads_as_more() {
        val label = state(august(net = "1500.00", previousNet = "1100.00")).changeLabel
        assertEquals("$400.00 more than last month", label)
    }

    @Test
    fun a_worse_month_reads_as_less() {
        val label = state(august(net = "1100.00", previousNet = "1500.00")).changeLabel
        assertEquals("$400.00 less than last month", label)
    }

    @Test
    fun a_month_that_matched_the_last_one_says_so() {
        assertEquals(
            "The same as last month",
            state(august(net = "1500.00", previousNet = "1500.00")).changeLabel,
        )
    }

    @Test
    fun climbing_out_of_a_loss_counts_the_whole_distance() {
        // From -200 to 300 is a move of 500. Reporting 100 would be the
        // difference of the magnitudes, which is not the distance travelled.
        val label = state(august(net = "300.00", previousNet = "-200.00")).changeLabel
        assertEquals("$500.00 more than last month", label)
    }

    @Test
    fun a_negative_month_is_formatted_with_its_sign() {
        assertEquals("-$250.00", state(august(net = "-250.00")).net)
        assertFalse(state(august(net = "-250.00")).netIsPositive)
    }

    @Test
    fun an_expectation_is_shown_only_once_the_wizard_has_one() {
        assertNull(state(august(income = Flow("5000.00"))).incomeExpectation)
        assertEquals(
            "of $5,200.00 expected",
            state(august(income = Flow("5000.00", "5200.00"))).incomeExpectation,
        )
    }

    @Test
    fun spending_past_the_expectation_is_flagged_but_earning_past_it_is_not() {
        val over = state(august(expenses = Flow("3500.00", "3000.00")))
        assertTrue(over.expensesAreOver)
        // There is deliberately no incomeIsOver: more income than expected is
        // good news, and a screen should not scold somebody for it.
    }

    @Test
    fun a_balance_that_did_not_move_says_so_rather_than_showing_a_zero() {
        assertEquals("Nothing moved this month", state(august()).investmentsMovement)
        assertEquals(
            "$500.00 set aside this month",
            state(august(investments = Stock("40000.00", "500.00"))).investmentsMovement,
        )
        assertEquals(
            "$400.00 paid this month",
            state(august(debts = Stock("12000.00", "400.00"))).debtsMovement,
        )
    }

    @Test
    fun a_commitment_that_was_found_says_seen_and_never_paid() {
        val row = state(
            august(
                commitments = listOf(
                    Commitment(
                        name = "Rent",
                        expected = "1800.00",
                        match = CommitmentMatch(occurredOn = "2026-08-02", amount = "1800.00"),
                    ),
                ),
            ),
        ).commitments.single()

        assertTrue(row.wasSeen)
        assertTrue(row.detail.startsWith("Seen "), row.detail)
        assertFalse(row.detail.contains("Paid", ignoreCase = true), "cash and other accounts look identical")
    }

    @Test
    fun a_commitment_that_was_not_found_is_not_called_unpaid() {
        val row = state(
            august(commitments = listOf(Commitment(name = "Car payment", expected = "400.00"))),
        ).commitments.single()

        assertFalse(row.wasSeen)
        assertEquals("Not seen this month", row.detail)
        assertFalse(row.detail.contains("unpaid", ignoreCase = true))
    }

    @Test
    fun a_payment_of_a_different_size_points_at_both_figures() {
        val row = state(
            august(
                commitments = listOf(
                    Commitment(
                        name = "Toronto Hydro",
                        expected = "140.00",
                        match = CommitmentMatch(occurredOn = "2026-08-26", amount = "151.00"),
                    ),
                ),
            ),
        ).commitments.single()

        assertEquals("$151.00, not the $140.00 you expected", row.detail)
    }

    @Test
    fun the_summary_counts_what_was_seen() {
        val month = state(
            august(
                commitments = listOf(
                    Commitment(name = "Rent", expected = "1800.00", match = CommitmentMatch(amount = "1800.00")),
                    Commitment(name = "Car payment", expected = "400.00"),
                    Commitment(name = "Insurance", expected = "90.00"),
                ),
            ),
        )

        assertEquals("1 of 3 seen this month", month.commitmentsSummary)
    }

    @Test
    fun there_is_no_summary_without_commitments() {
        assertNull(state(august()).commitmentsSummary)
    }

    @Test
    fun one_row_awaiting_review_is_not_described_as_1_rows() {
        assertEquals("1 still to review", state(august(pendingReview = 1)).pendingReviewLabel)
        assertEquals("4 still to review", state(august(pendingReview = 4)).pendingReviewLabel)
        assertNull(state(august(pendingReview = 0)).pendingReviewLabel)
    }

    @Test
    fun a_month_with_no_rows_is_heard_as_a_gap() {
        // The line says nothing to somebody who cannot see it.
        val descriptions = state(
            august(
                trend = listOf(
                    MonthPoint("2026-06-01", "300.00"),
                    MonthPoint("2026-07-01", null),
                    MonthPoint("2026-08-01", "1500.00"),
                ),
            ),
        ).trendDescriptions

        assertEquals("Jul: nothing recorded", descriptions[1])
        assertEquals(3, descriptions.size)
    }

    @Test
    fun there_is_no_chart_until_a_month_holds_something() {
        assertNull(state(august(trend = listOf(MonthPoint("2026-07-01", null)))).chart)
    }

    // ── The recent list ─────────────────────────────────────────────────

    private val groceries = Category(id = "c1", name = "Groceries")

    private fun row(
        amount: String = "86.40",
        credit: Boolean = false,
        categoryId: String? = "c1",
        merchant: String? = "Loblaws",
    ) = Transaction(
        id = "t1",
        occurredOn = "2026-08-02",
        amount = amount,
        currency = "CAD",
        direction = if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        merchant = merchant,
        categoryId = categoryId,
    )

    @Test
    fun money_out_and_money_in_are_signed_in_words_not_just_colour() {
        val rows = state(august()).copy(
            recent = listOf(row(amount = "86.40"), row(amount = "4100.00", credit = true)),
            categories = listOf(groceries),
        ).recentRows

        assertEquals("− $86.40", rows[0].amount)
        assertFalse(rows[0].isCredit)
        assertEquals("+ $4,100.00", rows[1].amount)
        assertTrue(rows[1].isCredit)
    }

    @Test
    fun a_row_says_what_it_was_filed_as() {
        val line = state(august()).copy(recent = listOf(row()), categories = listOf(groceries)).recentRows.single()

        assertEquals("Groceries", line.category)
        assertTrue(line.isFiled)
        assertEquals("Loblaws", line.title)
    }

    @Test
    fun a_row_nothing_filed_says_so_rather_than_leaving_a_blank() {
        val line = state(august()).copy(recent = listOf(row(categoryId = null))).recentRows.single()

        assertEquals("Not filed yet", line.category)
        assertFalse(line.isFiled)
    }

    @Test
    fun hiding_amounts_hides_them_in_the_recent_list_too() {
        val hidden = state(august()).copy(recent = listOf(row()), amountsHidden = true)
        val line = hidden.recentRows.single()

        assertFalse(line.amount.contains("86"), "a masked screen must not leak a figure: ${line.amount}")
    }

    @Test
    fun a_row_is_read_as_one_sentence() {
        val line = state(august()).copy(recent = listOf(row()), categories = listOf(groceries)).recentRows.single()

        assertTrue(line.description.contains("Loblaws"))
        assertTrue(line.description.contains("− $86.40"))
        assertTrue(line.description.contains("Groceries"))
    }

    @Test
    fun an_account_with_nothing_in_it_gets_the_empty_state() {
        val empty = DashboardUiState(data = Dashboard(currency = "CAD"), loading = false)
        assertTrue(empty.showsEmptyState)
    }

    @Test
    fun a_month_still_loading_does_not_claim_to_be_empty() {
        val loading = DashboardUiState(data = Dashboard(currency = "CAD"), loading = true)
        assertFalse(loading.showsEmptyState)
    }

    @Test
    fun a_failed_load_is_not_an_empty_account() {
        val failed = DashboardUiState(
            data = Dashboard(currency = "CAD"),
            loading = false,
            loadFailed = true,
        )
        assertFalse(failed.showsEmptyState, "an error is not the same as having nothing")
    }

    @Test
    fun there_is_nowhere_forward_to_go_from_the_month_that_is_running() {
        val now = com.humblesolutions.finai.usecase.DashboardMonths.current()
        assertFalse(state(august(), month = now).canGoForward)
        assertTrue(state(august(), month = com.humblesolutions.finai.usecase.DashboardMonths.previous(now)).canGoForward)
    }

    @Test
    fun every_message_the_screen_shows_is_a_sentence_and_not_a_raw_key() {
        // LocalizationRegistry returns an UNKNOWN key unchanged. So resolved
        // text looks up to itself, while a raw key that leaked through would
        // look up to its English value and come back different. The direction
        // matters: asserting the opposite passes on every string there is.
        val month = state(
            august(
                previousNet = "1100.00",
                income = Flow("5000.00", "5200.00"),
                investments = Stock("40000.00", "500.00"),
                commitments = listOf(Commitment(name = "Rent", expected = "1800.00")),
                trend = listOf(MonthPoint("2026-07-01", null)),
                pendingReview = 2,
            ),
        )

        val shown = listOfNotNull(
            month.monthLabel,
            month.changeLabel,
            month.incomeExpectation,
            month.investmentsMovement,
            month.commitmentsSummary,
            month.pendingReviewLabel,
            month.commitments.single().detail,
            month.trendDescriptions.single(),
        )
        shown.forEach { assertEquals(it, LocalizationRegistry.get(it), "raw key reached the screen: $it") }
    }
}
