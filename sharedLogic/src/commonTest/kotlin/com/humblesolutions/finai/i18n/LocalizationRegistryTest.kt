package com.humblesolutions.finai.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalizationRegistryTest {

    @Test
    fun `resolves a known key to its English value`() {
        assertEquals("FinAI", LocalizationRegistry.get(Strings.app_name))
    }

    @Test
    fun `falls back to the key itself when it is unknown`() {
        assertEquals("no_such_key", LocalizationRegistry.get("no_such_key"))
    }

    @Test
    fun `every key in Strings has an English value`() {
        val missing = listOf(Strings.app_name, Strings.placeholder_title, Strings.placeholder_body)
            .filterNot { EnglishStrings.containsKey(it) }
        assertEquals(emptyList(), missing)
    }
}
