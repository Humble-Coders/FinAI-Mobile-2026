package com.humblesolutions.finai.navigation

import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.Feature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the bar offers Budget, and when a person on it is sent home (#47).
 *
 * The first version treated "capabilities not read yet" as "off", which sent
 * anyone restored onto the Budget tab straight home after Android reclaimed
 * the process — and stranded the edit they came back for.
 */
class BudgetTabTest {

    private val on = Capabilities(features = mapOf("auto_budget" to Feature(enabled = true)))
    private val off = Capabilities(features = mapOf("auto_budget" to Feature(enabled = false)))

    @Test
    fun budget_sits_between_transactions_and_review_when_enabled() {
        assertEquals(
            listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS, HomeRoute.BUDGET, HomeRoute.REVIEW),
            tabsFor(on, HomeRoute.HOME),
        )
    }

    @Test
    fun budget_is_absent_when_the_payload_says_it_is_off() {
        assertFalse(HomeRoute.BUDGET in tabsFor(off, HomeRoute.HOME))
        assertFalse(HomeRoute.BUDGET in tabsFor(off, HomeRoute.BUDGET))
    }

    /** A first launch: nothing known, nobody on Budget, so no tab yet. */
    @Test
    fun budget_is_not_offered_before_the_payload_is_read() {
        assertFalse(HomeRoute.BUDGET in tabsFor(null, HomeRoute.HOME))
    }

    /** A restore: the saved route is Budget and the payload has not landed. */
    @Test
    fun a_person_restored_onto_budget_keeps_the_tab_until_the_payload_says_no() {
        assertTrue(HomeRoute.BUDGET in tabsFor(null, HomeRoute.BUDGET))
        assertFalse(leavesBudget(null, HomeRoute.BUDGET))
    }

    @Test
    fun a_person_on_budget_is_sent_home_only_once_the_payload_says_it_is_off() {
        assertTrue(leavesBudget(off, HomeRoute.BUDGET))
        assertFalse(leavesBudget(on, HomeRoute.BUDGET))
        // Nobody on Budget, nothing to leave.
        assertFalse(leavesBudget(off, HomeRoute.HOME))
    }
}
