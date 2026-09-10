package com.humblesolutions.finai.model

import com.humblesolutions.finai.data.FinAiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeDecodingTest {

    private fun decode(json: String): Me = FinAiJson.decodeFromString(Me.serializer(), json)

    @Test
    fun `decodes the me response`() {
        val me = decode(
            """{"user":{"id":"u1","email":null,"phone":"+14165550100","display_name":"Sam"},
               "household":{"id":"h1","country_code":"CA"},"onboarding_required":[]}""",
        )
        assertEquals("u1", me.user.id)
        assertEquals("Sam", me.user.displayName)
        assertEquals("CA", me.household.countryCode)
    }

    @Test
    fun `decodes a social signup that still owes its phone number`() {
        val me = decode(
            """{"user":{"id":"u2","email":"a@example.com"},"household":{"id":"h2"},
               "onboarding_required":["phone"]}""",
        )
        assertNull(me.user.phone)
        assertNull(me.household.countryCode)
        assertEquals(listOf(OnboardingStep.PHONE), me.onboardingRequired)
    }

    @Test
    fun `decodes an empty object to safe defaults`() {
        assertEquals(Me(), decode("{}"))
    }
}
