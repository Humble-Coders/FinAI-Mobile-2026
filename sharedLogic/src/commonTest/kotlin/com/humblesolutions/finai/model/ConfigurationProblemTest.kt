package com.humblesolutions.finai.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigurationProblemTest {

    @Test
    fun `reports the placeholder config a fresh clone builds with`() {
        assertEquals(
            ConfigurationProblem.SUPABASE_NOT_CONFIGURED,
            ConfigurationProblem.check("https://REPLACE_ME.supabase.co", "REPLACE_ME_ANON_KEY"),
        )
    }

    @Test
    fun `reports blank values`() {
        assertEquals(ConfigurationProblem.SUPABASE_NOT_CONFIGURED, ConfigurationProblem.check("", ""))
    }

    @Test
    fun `accepts real values`() {
        assertNull(ConfigurationProblem.check("https://abc.supabase.co", "publishable-key"))
    }
}
