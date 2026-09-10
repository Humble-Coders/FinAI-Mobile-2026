package com.humblesolutions.finai

import com.humblesolutions.finai.i18n.EnglishStrings
import com.humblesolutions.finai.i18n.Strings
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every key in [Strings] has a non-blank English value. Reads the keys by
 * reflection rather than from a hand-kept list, which is exactly what drifts.
 */
class StringsCoverageTest {

    @Test
    fun `every key in Strings has a non-blank English value`() {
        val keys = Strings::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue(keys.isNotEmpty(), "no keys found on Strings; the check would prove nothing")
        assertEquals(emptyList(), keys.filter { EnglishStrings[it].isNullOrBlank() })
    }
}
