package com.humblesolutions.finai.navigation

import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Feature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which tabs the bar offers, when a person on a gated tab is sent home, and
 * where Review's back arrow goes (#47, #52).
 *
 * The rule these guard was learned the hard way on #47: "capabilities not read
 * yet" is not "off". Treating it as off sent anyone restored onto Budget home
 * after Android reclaimed the process, and stranded the edit they came back for.
 */
class GatedTabsTest {

    private fun caps(budget: Boolean, goals: Boolean) = Capabilities(
        features = mapOf("auto_budget" to Feature(enabled = budget), "goals" to Feature(enabled = goals)),
    )

    private val both = caps(budget = true, goals = true)
    private val neither = caps(budget = false, goals = false)

    // ── The bar ─────────────────────────────────────────────────────────

    /** Goals took Review's place (#52): Home · Transactions · Budget · Goals. */
    @Test
    fun the_bar_is_home_transactions_budget_goals_with_review_off_it() {
        val tabs = tabsFor(both, HomeRoute.HOME)

        assertEquals(listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS, HomeRoute.BUDGET, HomeRoute.GOALS), tabs)
        assertFalse(HomeRoute.REVIEW in tabs)
    }

    @Test
    fun each_gated_tab_follows_its_own_feature() {
        assertEquals(listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS, HomeRoute.GOALS), tabsFor(caps(budget = false, goals = true), HomeRoute.HOME))
        assertEquals(listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS, HomeRoute.BUDGET), tabsFor(caps(budget = true, goals = false), HomeRoute.HOME))
        assertEquals(listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS), tabsFor(neither, HomeRoute.HOME))
    }

    /** A first launch: nothing known, nobody on a gated tab, so neither shows yet. */
    @Test
    fun no_gated_tab_is_offered_before_the_payload_is_read() {
        assertEquals(listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS), tabsFor(null, HomeRoute.HOME))
    }

    /** A restore: the saved route is a gated tab and the payload has not landed. */
    @Test
    fun a_person_restored_onto_a_gated_tab_keeps_it_until_the_payload_says_no() {
        assertTrue(HomeRoute.GOALS in tabsFor(null, HomeRoute.GOALS))
        assertFalse(leavesGatedTab(null, HomeRoute.GOALS))
        assertTrue(HomeRoute.BUDGET in tabsFor(null, HomeRoute.BUDGET))
        assertFalse(leavesGatedTab(null, HomeRoute.BUDGET))
    }

    @Test
    fun a_person_on_a_gated_tab_is_sent_home_only_once_the_payload_says_it_is_off() {
        assertTrue(leavesGatedTab(caps(budget = true, goals = false), HomeRoute.GOALS))
        assertTrue(leavesGatedTab(caps(budget = false, goals = true), HomeRoute.BUDGET))
        assertFalse(leavesGatedTab(both, HomeRoute.GOALS))
        // Nobody on a gated tab, nothing to leave.
        assertFalse(leavesGatedTab(neither, HomeRoute.HOME))
        assertFalse(leavesGatedTab(neither, HomeRoute.REVIEW))
    }

    // ── Review ──────────────────────────────────────────────────────────

    @Test
    fun review_goes_back_to_the_tab_it_was_opened_from() {
        assertEquals(HomeRoute.HOME, reviewReturnsTo(HomeRoute.HOME))
        assertEquals(HomeRoute.BUDGET, reviewReturnsTo(HomeRoute.BUDGET))
        assertEquals(HomeRoute.GOALS, reviewReturnsTo(HomeRoute.GOALS))
    }

    /** Opened at the end of an import, that flow is over: back is home, not the finished import. */
    @Test
    fun review_opened_from_a_flow_goes_back_home() {
        assertEquals(HomeRoute.HOME, reviewReturnsTo(HomeRoute.IMPORT))
        assertEquals(HomeRoute.HOME, reviewReturnsTo(HomeRoute.ADD))
    }
}
