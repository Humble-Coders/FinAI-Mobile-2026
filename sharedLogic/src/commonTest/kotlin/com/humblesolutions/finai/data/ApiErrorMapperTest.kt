package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FeatureReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiErrorMapperTest {

    @Test
    fun `a taken phone number is its own error not a generic rejection`() {
        val error = ApiErrorMapper.fromResponse(
            409,
            """{"detail":{"code":"phone_already_linked","message":"already linked"}}""",
        )
        assertIs<ApiException.PhoneAlreadyLinked>(error)
    }

    @Test
    fun `terms that moved on carry the version now in force`() {
        val error = ApiErrorMapper.fromResponse(
            409,
            """{"detail":{"code":"terms_version_mismatch","current_version":"terms-v2"}}""",
        )
        assertIs<ApiException.TermsChanged>(error)
        assertEquals("terms-v2", error.currentVersion)
    }

    @Test
    fun `an amount the server refuses carries the field it refused`() {
        val error = ApiErrorMapper.fromResponse(
            422,
            """{"detail":{"code":"invalid_amount","field":"investments.1.amount"}}""",
        )
        assertIs<ApiException.InvalidAmount>(error)
        assertEquals("investments.1.amount", error.field)
    }

    @Test
    fun `a 409 without a known code stays a generic rejection`() {
        val error = ApiErrorMapper.fromResponse(409, """{"detail":{"code":"something_else"}}""")
        assertIs<ApiException.Validation>(error)
    }

    @Test
    fun `a 409 whose body is not json stays a generic rejection`() {
        val error = ApiErrorMapper.fromResponse(409, "gateway says no")
        assertIs<ApiException.Validation>(error)
    }

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
