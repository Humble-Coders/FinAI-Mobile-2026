package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FlagsTest {

    @Test
    fun `a region code becomes its flag`() {
        assertEquals("🇨🇦", flagEmoji("CA"))
        assertEquals("🇮🇳", flagEmoji("IN"))
    }

    @Test
    fun `lower case works too`() {
        assertEquals(flagEmoji("CA"), flagEmoji("ca"))
    }

    @Test
    fun `anything that is not two letters is empty`() {
        listOf("", "C", "CAN", "1A", "C1", "C-").forEach { assertEquals("", flagEmoji(it), it) }
    }
}
