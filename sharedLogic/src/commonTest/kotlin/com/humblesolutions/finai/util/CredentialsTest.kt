package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialsTest {

    @Test
    fun `ordinary addresses look like email`() {
        listOf(
            "a@b.co",
            "first.last+tag@example.com",
            "x@sub.domain.ca",
            "abc123@privaterelay.appleid.com",
            "  padded@example.com  ",
        ).forEach { assertTrue(Credentials.looksLikeEmail(it), it) }
    }

    @Test
    fun `things that cannot be an address do not`() {
        listOf("", "plain", "@example.com", "a@", "a@b", "a@.com", "a@b.", "a b@example.com", "a@@b.com", "a@b@c.com")
            .forEach { assertFalse(Credentials.looksLikeEmail(it), it) }
    }

    @Test
    fun `a new password must meet the minimum`() {
        assertEquals(CredentialsProblem.PASSWORD_TOO_SHORT, Credentials.problem("a@b.co", "1234567", creating = true))
        assertNull(Credentials.problem("a@b.co", "12345678", creating = true))
    }

    @Test
    fun `signing in accepts any typed password`() {
        // Set under whatever rule applied then; the server is the judge.
        assertNull(Credentials.problem("a@b.co", "123", creating = false))
    }

    @Test
    fun `a missing password is its own problem`() {
        assertEquals(CredentialsProblem.PASSWORD_MISSING, Credentials.problem("a@b.co", "", creating = false))
    }

    @Test
    fun `the email is judged before the password`() {
        assertEquals(CredentialsProblem.EMAIL_INVALID, Credentials.problem("nope", "", creating = true))
    }

    @Test
    fun `normalising only trims`() {
        assertEquals("Mixed.Case@Example.com", Credentials.normalizeEmail(" Mixed.Case@Example.com\n"))
    }
}
