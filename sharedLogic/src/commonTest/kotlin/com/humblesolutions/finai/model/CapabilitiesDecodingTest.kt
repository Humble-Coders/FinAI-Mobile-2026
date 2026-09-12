package com.humblesolutions.finai.model

import com.humblesolutions.finai.data.FinAiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilitiesDecodingTest {

    private fun decode(json: String): Capabilities = FinAiJson.decodeFromString(Capabilities.serializer(), json)

    @Test
    fun `decodes a real payload for a Canadian household`() {
        val capabilities = decode(
            """{"region":"CA","currency":"CAD","locale":"en-CA",
               "features":{"document_upload":{"enabled":true,"reason":null},
                           "bank_linking":{"enabled":false,"reason":"coming_soon"}},
               "content":{"tax_accounts":["RRSP","TFSA","FHSA"],"disclaimer_version":"ca-v1"},
               "onboarding_required":[]}""",
        )
        assertEquals("CA", capabilities.region)
        assertEquals("CAD", capabilities.currency)
        assertTrue(capabilities.isEnabled("document_upload"))
        assertNull(capabilities.blockingReason("document_upload"))
        assertEquals(FeatureReason.COMING_SOON, capabilities.blockingReason("bank_linking"))
        assertEquals(listOf("RRSP", "TFSA", "FHSA"), capabilities.content.taxAccounts)
        assertEquals("ca-v1", capabilities.content.disclaimerVersion)
        assertFalse(capabilities.needsOnboarding)
    }

    @Test
    fun `decodes the payload for a household whose region is unknown`() {
        val capabilities = decode(
            """{"region":null,"currency":"CAD","locale":"en-CA","features":{},
               "content":{},"onboarding_required":["phone"]}""",
        )
        assertNull(capabilities.region)
        assertEquals(listOf(OnboardingStep.PHONE), capabilities.onboardingRequired)
        assertTrue(capabilities.needsOnboarding)
        assertEquals(CapabilityContent(), capabilities.content)
    }

    @Test
    fun `maps a feature reason this build does not know to UNKNOWN`() {
        val capabilities = decode("""{"features":{"x":{"enabled":false,"reason":"invented_later"}}}""")
        assertEquals(FeatureReason.UNKNOWN, capabilities.features.getValue("x").reason)
    }

    @Test
    fun `decodes every onboarding step the API can send`() {
        val capabilities = decode(
            """{"onboarding_required":["phone","region","consent","financial_setup"]}""",
        )
        assertEquals(
            listOf(
                OnboardingStep.PHONE,
                OnboardingStep.REGION,
                OnboardingStep.CONSENT,
                OnboardingStep.FINANCIAL_SETUP,
            ),
            capabilities.onboardingRequired,
        )
        assertTrue(capabilities.needsOnboarding)
    }

    @Test
    fun `keeps an onboarding step this build does not know`() {
        val capabilities = decode("""{"onboarding_required":["phone","selfie"]}""")
        assertEquals(listOf(OnboardingStep.PHONE, OnboardingStep.UNKNOWN), capabilities.onboardingRequired)
        assertTrue(capabilities.needsOnboarding)
    }

    @Test
    fun `decodes an empty object to safe defaults`() {
        val capabilities = decode("{}")
        assertNull(capabilities.region)
        assertEquals("", capabilities.currency)
        assertTrue(capabilities.features.isEmpty())
        assertFalse(capabilities.isEnabled("document_upload"))
    }

    @Test
    fun `treats a feature with no enabled field as off`() {
        val capabilities = decode("""{"features":{"x":{}}}""")
        assertFalse(capabilities.isEnabled("x"))
        assertEquals(FeatureReason.UNKNOWN, capabilities.blockingReason("x"))
    }

    @Test
    fun `treats a feature the payload does not mention as unavailable`() {
        assertEquals(FeatureReason.UNKNOWN_FEATURE, decode("{}").blockingReason("never_sent"))
    }

    @Test
    fun `ignores fields this build does not know`() {
        val capabilities = decode("""{"region":"GB","launched_at":"2027-01-01","content":{"new_copy":"x"}}""")
        assertEquals("GB", capabilities.region)
    }

    @Test
    fun `falls back to the default when the server sends null for a required field`() {
        assertEquals("", decode("""{"currency":null}""").currency)
    }
}
