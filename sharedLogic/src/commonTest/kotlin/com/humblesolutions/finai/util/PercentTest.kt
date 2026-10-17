package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PercentTest {

    @Test
    fun `a percentage is normalised to two places`() {
        assertEquals("6.00", Percent.normalize("6"))
        assertEquals("20.99", Percent.normalize("20.99"))
        assertEquals("6.50", Percent.normalize(" 6.5 % "))
        assertEquals("100.00", Percent.normalize("100"))
        assertEquals("0.00", Percent.normalize("0"))
    }

    @Test
    fun `each problem is told apart`() {
        assertEquals(Percent.Problem.BLANK, Percent.problem("  "))
        assertEquals(Percent.Problem.NOT_A_PERCENT, Percent.problem("six"))
        assertEquals(Percent.Problem.TOO_PRECISE, Percent.problem("20.999"))
        assertEquals(Percent.Problem.OUT_OF_RANGE, Percent.problem("100.01"))
        assertEquals(Percent.Problem.OUT_OF_RANGE, Percent.problem("-1"))
        assertEquals(Percent.Problem.NOT_A_PERCENT, Percent.problem("1,5"))
        assertNull(Percent.problem("19.99"))
    }

    @Test
    fun `the same figure written two ways is the same`() {
        assertTrue(Percent.same("6", "6.00"))
        assertTrue(Percent.same(null, ""))
    }

    @Test
    fun `a rate is displayed as a person says it`() {
        assertEquals("6%", Percent.display("6.00"))
        assertEquals("20.99%", Percent.display("20.99"))
        assertEquals("9.5%", Percent.display("9.50"))
    }
}
