package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialCodesTest {

    @Test
    fun `the table is well formed`() {
        assertTrue(DialCodes.all.size > 200, "only ${DialCodes.all.size} entries")
        for (entry in DialCodes.all) {
            assertEquals(2, entry.region.length, "region ${entry.region}")
            assertTrue(entry.region.all { it in 'A'..'Z' }, "region ${entry.region} is not A-Z")
            assertTrue(entry.code.isNotEmpty(), "${entry.region} has no code")
            assertTrue(entry.code.all { it.isDigit() }, "${entry.region} code ${entry.code} is not digits")
        }
    }

    @Test
    fun `no region appears twice`() {
        val duplicates = DialCodes.all.groupBy { it.region }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicated: $duplicates")
    }

    @Test
    fun `the codes people would notice are right`() {
        val expected = mapOf(
            "CA" to "1", "US" to "1", "GB" to "44", "IN" to "91", "AU" to "61",
            "DE" to "49", "FR" to "33", "JP" to "81", "BR" to "55", "ZA" to "27",
            "AE" to "971", "SG" to "65", "NZ" to "64", "IE" to "353", "MX" to "52",
        )
        for ((region, code) in expected) {
            assertEquals(code, DialCodes.forRegion(region)?.code, "region $region")
        }
    }

    @Test
    fun `Canada and the US share a prefix which is why this picks a code and not a region`() {
        // The reason the dropdown's country is never sent as the region: +1 is
        // the whole NANP, so the prefix cannot identify the country.
        assertEquals(DialCodes.forRegion("CA")?.code, DialCodes.forRegion("US")?.code)
        val plusOne = DialCodes.all.filter { it.code == "1" }.map { it.region }
        assertTrue(plusOne.size > 5, "expected the NANP to be many countries but got $plusOne")
    }

    @Test
    fun `display carries the plus`() {
        assertEquals("+1", DialCodes.forRegion("CA")?.display)
        assertEquals("+44", DialCodes.forRegion("GB")?.display)
    }

    @Test
    fun `forRegion is case insensitive and tolerates nothing`() {
        assertEquals("CA", DialCodes.forRegion("ca")?.region)
        assertEquals("CA", DialCodes.forRegion(" Ca ")?.region)
        assertNull(DialCodes.forRegion(null))
        assertNull(DialCodes.forRegion(""))
        assertNull(DialCodes.forRegion("   "))
        assertNull(DialCodes.forRegion("ZZ"))
    }

    @Test
    fun `the device region is trusted first`() {
        assertEquals("GB", DialCodes.defaultFor("GB", "America/Toronto").region)
        assertEquals("IN", DialCodes.defaultFor("in", null).region)
    }

    @Test
    fun `the time zone is used only when there is no region`() {
        assertEquals("CA", DialCodes.defaultFor(null, "America/Toronto").region)
        assertEquals("AU", DialCodes.defaultFor(null, "Australia/Sydney").region)
        assertEquals("IN", DialCodes.defaultFor("", "Asia/Kolkata").region)
    }

    @Test
    fun `anything unrecognised falls back to the launch market`() {
        assertEquals("CA", DialCodes.fallback.region)
        assertEquals("CA", DialCodes.defaultFor(null, null).region)
        assertEquals("CA", DialCodes.defaultFor("ZZ", "Mars/Olympus_Mons").region)
        assertEquals("CA", DialCodes.defaultFor(null, "   ").region)
    }

    @Test
    fun `every time zone maps to a region the table knows`() {
        // A zone pointing at a region with no dial code would fall through to
        // the fallback silently, which would look like the mapping working.
        for (zone in listOf(
            "America/Toronto", "America/New_York", "Europe/London", "Asia/Kolkata",
            "Australia/Sydney", "Africa/Lagos", "America/Sao_Paulo", "Pacific/Auckland",
        )) {
            val resolved = DialCodes.defaultFor(null, zone)
            assertNotNull(resolved, zone)
            assertTrue(resolved.region != "CA" || zone.contains("Toronto"), "$zone fell through to the fallback")
        }
    }
}
