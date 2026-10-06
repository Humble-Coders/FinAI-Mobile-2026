package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.data.FinAiJson
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Dashboard
import com.humblesolutions.finai.model.Feature
import com.humblesolutions.finai.model.HealthScore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home's M4 sections (#46): what is drawn, in what order, and how it is said.
 *
 * Built from `/dashboard` JSON as backend #58 sends it, so the decoding is
 * tested along with the folding.
 */
class HomeInsightsTest {

    private val today = LocalDate(2026, 10, 6)
    private val utc = TimeZone.UTC

    private val bothOn = Capabilities(
        features = mapOf(
            HomeInsights.BUDGET_FEATURE to Feature(enabled = true),
            HomeInsights.SCORE_FEATURE to Feature(enabled = true),
        ),
    )

    private fun dashboard(json: String): Dashboard = FinAiJson.decodeFromString(json)

    private fun sections(
        data: Dashboard,
        capabilities: Capabilities? = bothOn,
        hidden: Boolean = false,
        on: LocalDate = today,
    ) = HomeInsights.sections(data, capabilities, "en", hidden, on, utc)

    private val ready = """
        {
          "month": "2026-10-01", "currency": "CAD", "net": "100.00",
          "spend_by_category": [
            {"category_id": "t", "slug": "transfers", "name": "Transfers", "spent": "500.00"},
            {"category_id": "d", "slug": "debt_payment", "name": "Debt payment", "spent": "300.00"},
            {"category_id": "g", "slug": "groceries", "name": "Groceries", "spent": "150.01"},
            {"category_id": "n", "slug": "dining", "name": "Dining out", "spent": "45.00"},
            {"category_id": "s", "slug": "shopping", "name": "Shopping", "spent": "20.00"},
            {"category_id": null, "slug": null, "name": null, "spent": "12.34"}
          ],
          "budget": {
            "status": "ready",
            "lines": [
              {"category_id": "g", "slug": "groceries", "name": "Groceries", "allocated": "100.00", "spent": "150.01", "over": "50.01"},
              {"category_id": "n", "slug": "dining", "name": "Dining out", "allocated": "50.00", "spent": "45.00", "over": "0.00"},
              {"category_id": "s", "slug": "shopping", "name": "Shopping", "allocated": "400.00", "spent": "20.00", "over": "0.00"},
              {"category_id": "e", "slug": "entertainment", "name": "Entertainment", "allocated": "60.00", "spent": "30.00", "over": "0.00"},
              {"category_id": "u", "slug": "utilities", "name": "Utilities", "allocated": "90.00", "spent": "10.00", "over": "0.00"}
            ],
            "total_allocated": "700.00", "total_spent": "255.01", "shortfall": null
          },
          "health_score": {
            "status": "ready", "score": 72, "formula_version": "v1",
            "scored_on": "2026-10-06", "previous_score": 68, "notice": null
          },
          "as_of": {"latest_transaction_on": "2026-10-02", "last_import_at": "2026-10-03T09:30:00+00:00"},
          "learning": {"ready": true, "complete_months": 4, "transactions": 120,
                       "needs": {"complete_months": 1, "transactions": 20}}
        }
    """.trimIndent()

    // ── An older server, and features off ───────────────────────────────

    @Test
    fun a_payload_from_before_m4_draws_nothing_new() {
        val old = dashboard("""{"month": "2026-10-01", "currency": "CAD", "net": "1.00"}""")

        val drawn = sections(old)

        assertEquals(HomeSections(null, null, null, null, null), drawn)
    }

    @Test
    fun a_feature_switched_off_hides_its_section_even_when_sent() {
        val data = dashboard(ready)
        val budgetOnly = Capabilities(features = mapOf(HomeInsights.BUDGET_FEATURE to Feature(enabled = true)))

        val drawn = sections(data, budgetOnly)

        assertNull(drawn.score)
        assertNotNull(drawn.budget)
        assertNotNull(drawn.spending, "spending needs no feature")
    }

    @Test
    fun capabilities_not_read_yet_hide_both_gated_sections() {
        val drawn = sections(dashboard(ready), capabilities = null)

        assertNull(drawn.score)
        assertNull(drawn.budget)
        assertNull(drawn.learning)
        assertNotNull(drawn.freshness)
    }

    // ── The score ───────────────────────────────────────────────────────

    @Test
    fun the_score_and_its_change_come_from_the_server() {
        val card = assertNotNull(sections(dashboard(ready)).score)

        assertEquals("72", card.score)
        assertEquals(0.72, card.fraction)
        assertEquals("+4 since last month", card.change)
        assertFalse(card.changeIsDown)
        assertEquals("Money Health Score 72 out of 100, up 4 since last month", card.accessibility)
    }

    @Test
    fun a_fall_and_no_change_are_worded_as_such() {
        val down = dashboard(ready.replace("\"previous_score\": 68", "\"previous_score\": 75"))
        val same = dashboard(ready.replace("\"previous_score\": 68", "\"previous_score\": 72"))
        val none = dashboard(ready.replace("\"previous_score\": 68", "\"previous_score\": null"))

        assertEquals("−3 since last month", sections(down).score?.change)
        assertTrue(sections(down).score!!.changeIsDown)
        assertEquals("Same as last month", sections(same).score?.change)
        assertNull(sections(none).score?.change)
        assertEquals("Money Health Score 72 out of 100", sections(none).score?.accessibility)
    }

    @Test
    fun a_held_score_says_which_month_is_missing_and_where_it_is_from() {
        val held = dashboard(
            ready
                .replace("\"scored_on\": \"2026-10-06\"", "\"scored_on\": \"2026-09-15\"")
                .replace(
                    "\"notice\": null",
                    "\"notice\": {\"code\": \"last_month_missing\", \"month\": \"2026-09-01\", \"message\": \"…\"}",
                ),
        )

        val card = assertNotNull(sections(held).score)

        assertEquals("Sep isn't imported yet · score from Sep 15, 2026", card.held)
    }

    @Test
    fun the_score_is_not_money_and_stays_visible_when_amounts_are_hidden() {
        assertEquals("72", sections(dashboard(ready), hidden = true).score?.score)
    }

    // ── Still learning ──────────────────────────────────────────────────

    @Test
    fun learning_replaces_score_and_budget_with_one_card_and_keeps_spending() {
        val learning = dashboard(
            ready
                .replace("\"ready\": true, \"complete_months\": 4, \"transactions\": 120", "\"ready\": false, \"complete_months\": 1, \"transactions\": 12")
                .replace("\"status\": \"ready\", \"score\": 72", "\"status\": \"learning\", \"score\": null"),
        )

        val drawn = sections(learning)

        assertEquals(12, drawn.learning?.transactions)
        assertNull(drawn.score)
        assertNull(drawn.budget)
        assertNotNull(drawn.spending)
    }

    @Test
    fun one_learning_card_even_if_a_score_still_arrives() {
        // Dropped back below the threshold: the card stands alone, never beside a score.
        val learning = dashboard(ready.replace("\"ready\": true", "\"ready\": false"))

        val drawn = sections(learning)

        assertNotNull(drawn.learning)
        assertNull(drawn.score)
        assertNull(drawn.budget)
    }

    @Test
    fun learning_shows_no_card_when_neither_feature_is_on() {
        val learning = dashboard(ready.replace("\"ready\": true", "\"ready\": false"))

        assertNull(sections(learning, Capabilities()).learning)
    }

    // ── The budget ──────────────────────────────────────────────────────

    @Test
    fun home_shows_four_lines_over_budget_first_then_nearest_their_allocation() {
        val budget = assertNotNull(sections(dashboard(ready)).budget)

        assertEquals(listOf("Groceries", "Dining out", "Entertainment", "Utilities"), budget.rows.map { it.name })
        assertEquals("Spent $255.01 of $700.00", budget.totals)
    }

    @Test
    fun an_over_line_says_so_in_words_with_the_server_s_figure() {
        val groceries = sections(dashboard(ready)).budget!!.rows.first()

        assertTrue(groceries.isOver)
        assertEquals("Over by $50.01", groceries.overLabel)
        assertEquals(1.0, groceries.fraction)
        assertEquals("Groceries, spent $150.01 of $100.00, over by $50.01", groceries.accessibility)
        val dining = sections(dashboard(ready)).budget!!.rows[1]
        assertNull(dining.overLabel)
        assertEquals(0.9, dining.fraction)
    }

    @Test
    fun hidden_amounts_mask_every_budget_figure() {
        val budget = sections(dashboard(ready), hidden = true).budget!!

        assertEquals("Spent •••••• of ••••••", budget.totals)
        assertEquals("•••••• of ••••••", budget.rows.first().amounts)
        assertEquals("Over by ••••••", budget.rows.first().overLabel)
    }

    // ── Where it went ───────────────────────────────────────────────────

    @Test
    fun spending_keeps_the_server_s_order_and_shows_five_then_all() {
        val spending = assertNotNull(sections(dashboard(ready)).spending)

        assertEquals(5, spending.top.size)
        assertEquals(6, spending.all.size)
        assertEquals("Transfers", spending.top.first().name)
        assertEquals("Not categorised", spending.all.last().name)
        assertEquals(CategoryIcon.UNFILED, spending.all.last().icon)
        assertEquals("Show all (1 more)", spending.showAllLabel)
    }

    @Test
    fun hidden_amounts_mask_spending_too() {
        assertEquals("••••••", sections(dashboard(ready), hidden = true).spending!!.top.first().amount)
    }

    // ── Freshness ───────────────────────────────────────────────────────

    @Test
    fun freshness_names_the_newest_transaction_and_the_last_import() {
        assertEquals(
            FreshnessLine("As of Oct 2, 2026 · last import 3 days ago", isStale = false),
            sections(dashboard(ready)).freshness,
        )
        assertEquals("As of Oct 2, 2026 · last import today", sections(dashboard(ready), on = LocalDate(2026, 10, 3)).freshness?.text)
        assertEquals("As of Oct 2, 2026 · last import yesterday", sections(dashboard(ready), on = LocalDate(2026, 10, 4)).freshness?.text)
    }

    @Test
    fun past_thirty_days_it_nudges_an_import_instead() {
        val day30 = sections(dashboard(ready), on = LocalDate(2026, 11, 1)).freshness!!
        val day31 = sections(dashboard(ready), on = LocalDate(2026, 11, 2)).freshness!!

        assertFalse(day30.isStale)
        assertTrue(day31.isStale)
        assertEquals("Latest data is from Oct 2, 2026 · import a statement to bring Home up to date", day31.text)
    }

    @Test
    fun no_import_time_still_gives_the_date_and_nothing_yet_gives_no_line() {
        val noImport = dashboard(ready.replace("\"last_import_at\": \"2026-10-03T09:30:00+00:00\"", "\"last_import_at\": null"))
        val nothing = dashboard(ready.replace("\"latest_transaction_on\": \"2026-10-02\"", "\"latest_transaction_on\": null"))

        assertEquals("As of Oct 2, 2026", sections(noImport).freshness?.text)
        assertNull(sections(nothing).freshness)
    }

    // ── The breakdown ───────────────────────────────────────────────────

    private val health = """
        {
          "status": "ready", "score": 62, "formula_version": "v1",
          "components": [
            {"key": "savings_consistency", "score": 100, "weight": "61.54", "available": true, "inputs": {"months": []}},
            {"key": "spending_vs_budget", "score": null, "weight": "0.00", "available": false, "inputs": {"lines": []}},
            {"key": "debt_payments", "score": 0, "weight": "38.46", "available": true, "inputs": {}},
            {"key": "goal_completion", "score": 40, "weight": "10.00", "available": true, "inputs": {}}
          ],
          "history": [], "notice": null, "held_from": null
        }
    """.trimIndent()

    @Test
    fun the_breakdown_lists_every_part_and_never_shows_an_unscored_one_as_zero() {
        val breakdown = assertNotNull(HomeInsights.breakdown(FinAiJson.decodeFromString<HealthScore>(health), "en"))

        assertEquals(listOf("Saving regularly", "Spending against budget", "Debt payments", "Another part of the score"), breakdown.rows.map { it.title })
        val budget = breakdown.rows[1]
        assertFalse(budget.available)
        assertEquals("Not counted yet", budget.score)
        assertEquals("Spending against budget, not counted yet", budget.accessibility)
        assertEquals("0 / 100", breakdown.rows[2].score, "a real zero is still a zero")
        assertEquals("Formula v1", breakdown.formula)
        assertEquals("The Money Health Score is educational guidance, not financial advice.", breakdown.disclaimer)
    }

    @Test
    fun no_score_means_no_breakdown() {
        val learning = FinAiJson.decodeFromString<HealthScore>("""{"status": "learning", "score": null}""")

        assertNull(HomeInsights.breakdown(learning, "en"))
    }
}
