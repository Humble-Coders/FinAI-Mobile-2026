package com.humblesolutions.finai.ui.dashboard

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.model.Commitment
import com.humblesolutions.finai.model.CommitmentMatch
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.Flow
import com.humblesolutions.finai.model.MonthPoint
import com.humblesolutions.finai.model.Stock
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
    fun a_month_with_no_rows_is_a_gap_in_the_chart() {
        val bars = state(
            august(
                trend = listOf(
                    MonthPoint("2026-06-01", "300.00"),
                    MonthPoint("2026-07-01", null),
                    MonthPoint("2026-08-01", "1500.00"),
                ),
            ),
        ).bars

        assertNull(bars[1].fraction, "July has no bar height, because July has no data")
        assertEquals("Jul: nothing recorded", bars[1].description)
        assertEquals(1f, bars[2].fraction, "the tallest month fills the chart")
    }

    @Test
    fun a_loss_is_as_tall_as_a_gain_and_differs_by_direction() {
        // Otherwise a bad month draws as a quiet one.
        val bars = state(
            august(
                trend = listOf(MonthPoint("2026-07-01", "-1500.00"), MonthPoint("2026-08-01", "1500.00")),
            ),
        ).bars

        assertEquals(bars[0].fraction, bars[1].fraction)
        assertTrue(bars[0].isNegative)
        assertFalse(bars[1].isNegative)
    }

    @Test
    fun a_tiny_month_still_leaves_a_mark() {
        val bars = state(
            august(
                trend = listOf(MonthPoint("2026-07-01", "2.00"), MonthPoint("2026-08-01", "5000.00")),
            ),
        ).bars

        assertTrue(bars[0].fraction!! > 0f, "a real month must not vanish")
    }

    @Test
    fun the_chart_is_hidden_until_a_month_in_view_holds_something() {
        assertFalse(state(august(trend = listOf(MonthPoint("2026-07-01", null)))).showsTrend)
        assertTrue(state(august(trend = listOf(MonthPoint("2026-07-01", "1.00")))).showsTrend)
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
            month.bars.single().description,
        )
        shown.forEach { assertEquals(it, LocalizationRegistry.get(it), "raw key reached the screen: $it") }
    }
}
