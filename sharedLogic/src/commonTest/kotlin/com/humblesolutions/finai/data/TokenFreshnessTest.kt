package com.humblesolutions.finai.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenFreshnessTest {

    @Test
    fun `an expired token is stale`() {
        assertTrue(TokenFreshness.isStale(expiresAtEpochSeconds = 1_000, nowEpochSeconds = 1_001))
    }

    @Test
    fun `a token inside the margin is stale`() {
        assertTrue(TokenFreshness.isStale(expiresAtEpochSeconds = 1_020, nowEpochSeconds = 1_000, marginSeconds = 30))
    }

    @Test
    fun `a token exactly at the margin is stale`() {
        assertTrue(TokenFreshness.isStale(expiresAtEpochSeconds = 1_030, nowEpochSeconds = 1_000, marginSeconds = 30))
    }

    @Test
    fun `a token well before expiry is fresh`() {
        assertFalse(TokenFreshness.isStale(expiresAtEpochSeconds = 5_000, nowEpochSeconds = 1_000, marginSeconds = 30))
    }
}
