package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FeatureReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiErrorMapperTest {

    @Test
    fun `maps 401 to Unauthorized`() {
        assertIs<ApiException.Unauthorized>(ApiErrorMapper.fromResponse(401, """{"detail":"token expired"}"""))
    }

    @Test
    fun `maps a feature gate 403 to FeatureUnavailable with its feature and reason`() {
        val error = ApiErrorMapper.fromResponse(
            403,
            """{"detail":{"code":"feature_unavailable","feature":"bank_linking","reason":"not_in_plan"}}""",
        )
        assertIs<ApiException.FeatureUnavailable>(error)
        assertEquals("bank_linking", error.feature)
        assertEquals(FeatureReason.NOT_IN_PLAN, error.reason)
    }

    @Test
    fun `maps the 403 FastAPI sends for a missing token to Unauthorized not FeatureUnavailable`() {
        assertIs<ApiException.Unauthorized>(ApiErrorMapper.fromResponse(403, """{"detail":"Not authenticated"}"""))
    }

    @Test
    fun `maps any other 403 to Forbidden`() {
        assertIs<ApiException.Forbidden>(ApiErrorMapper.fromResponse(403, """{"detail":"not your household"}"""))
    }

    @Test
    fun `maps a 403 with an unreadable body to Forbidden instead of failing`() {
        assertIs<ApiException.Forbidden>(ApiErrorMapper.fromResponse(403, "<html>oops</html>"))
    }

    @Test
    fun `maps 404 to NotFound`() {
        assertIs<ApiException.NotFound>(ApiErrorMapper.fromResponse(404, ""))
    }

    @Test
    fun `maps rejected input to Validation`() {
        for (status in listOf(400, 409, 422)) {
            assertEquals(status, assertIs<ApiException.Validation>(ApiErrorMapper.fromResponse(status, "{}")).status)
        }
    }

    @Test
    fun `maps 5xx to Server`() {
        assertEquals(503, assertIs<ApiException.Server>(ApiErrorMapper.fromResponse(503, "")).status)
    }

    @Test
    fun `maps an unexpected status to Unexpected`() {
        assertIs<ApiException.Unexpected>(ApiErrorMapper.fromResponse(418, ""))
    }
}
