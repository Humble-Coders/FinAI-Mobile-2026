package com.humblesolutions.finai.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The backend's `test_split_math.py` cases: the preview and the saved shares cannot disagree. */
class SplitMathTest {

    @Test
    fun `the left over minor units go to the first people`() {
        assertEquals(listOf(334L, 333L, 333L), SplitMath.equalShares(1000, 3))
        assertEquals(listOf(24850L, 24850L, 24850L), SplitMath.equalShares(74550, 3))
    }

    @Test
    fun `nobody to split with is refused`() {
        assertFailsWith<IllegalArgumentException> { SplitMath.equalShares(1000, 0) }
    }

    @Test
    fun `each item to its people and tax and tip by what each had`() {
        val shares = SplitMath.itemShares(
            4,
            listOf(2850L to setOf(1, 3), 3400L to setOf(2), 1800L to setOf(0, 1, 2, 3), 1650L to setOf(0)),
            861,
            1746,
        )

        assertEquals(listOf(2664L, 2379L, 4885L, 2379L), shares)
        assertEquals(2850L + 3400 + 1800 + 1650 + 861 + 1746, shares.sum())
    }

    @Test
    fun `someone with no items pays no tax`() {
        assertEquals(listOf(1150L, 1150L, 0L), SplitMath.itemShares(3, listOf(1000L to setOf(0), 1000L to setOf(1)), 300, 0))
    }

    @Test
    fun `amounts cross as minor units and back`() {
        assertEquals(1250L, SplitMath.minor("12.5", "CAD"))
        assertEquals("12.50", SplitMath.decimal(1250, "CAD"))
        assertEquals("0.05", SplitMath.decimal(5, "INR"))
        assertEquals("1200", SplitMath.decimal(1200, "JPY"))
    }
}
