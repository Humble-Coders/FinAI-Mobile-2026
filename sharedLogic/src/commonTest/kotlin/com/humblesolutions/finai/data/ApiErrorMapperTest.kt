package com.humblesolutions.finai.data

import com.humblesolutions.finai.i18n.Strings
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

    // ── The budget (#47) ───────────────────────────────────────────────
    // Each has its own words on screen. A typo in the code string here would
    // fall through to the generic rejection without failing anything else.

    @Test
    fun `a category that cannot hold a budget line is its own error`() {
        val error = ApiErrorMapper.fromResponse(
            422,
            """{"detail":{"code":"not_budgetable","field":"category_id","message":"income is money moving, not money spent."}}""",
        )
        assertIs<ApiException.NotBudgetable>(error)
        assertEquals("category_id", error.field)
        assertEquals(Strings.budget_error_not_budgetable, error.messageKey)
    }

    @Test
    fun `a month the server cannot read is its own error`() {
        val error = ApiErrorMapper.fromResponse(422, """{"detail":{"code":"invalid_month","message":"Expected YYYY-MM."}}""")
        assertIs<ApiException.InvalidMonth>(error)
        assertEquals(Strings.budget_error_invalid_month, error.messageKey)
    }

    @Test
    fun `a month that has not begun is its own error`() {
        val error = ApiErrorMapper.fromResponse(422, """{"detail":{"code":"month_in_future","message":"That month has not begun."}}""")
        assertIs<ApiException.MonthInFuture>(error)
        assertEquals(Strings.budget_error_month_in_future, error.messageKey)
    }

    // ── Goals (#52) ────────────────────────────────────────────────────

    @Test
    fun `the goal limit is its own error and carries the limit`() {
        val error = ApiErrorMapper.fromResponse(409, """{"detail":{"code":"goal_limit_reached","limit":20}}""")
        assertIs<ApiException.GoalLimitReached>(error)
        assertEquals(20, error.limit)
        assertEquals(Strings.goals_error_limit, error.messageKey)
    }

    @Test
    fun `a target date in the past is its own error`() {
        val error = ApiErrorMapper.fromResponse(422, """{"detail":{"code":"date_in_past","field":"target_date"}}""")
        assertIs<ApiException.DateInPast>(error)
        assertEquals(Strings.goals_error_date_in_past, error.messageKey)
    }

    @Test
    fun `a goal order that no longer matches is its own error`() {
        val error = ApiErrorMapper.fromResponse(422, """{"detail":{"code":"order_mismatch"}}""")
        assertIs<ApiException.OrderMismatch>(error)
        assertEquals(Strings.goals_error_order_changed, error.messageKey)
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
    fun `maps 413 to a statement refusal a reader can act on`() {
        // The statement endpoint's own status. Left to Unexpected it showed
        // "something went wrong" for a file the user could simply split.
        val refusal = assertIs<ApiException.StatementTooLarge>(ApiErrorMapper.fromResponse(413, ""))

        assertEquals(Strings.statement_too_long, refusal.messageKey)
    }

    @Test
    fun `maps an unexpected status to Unexpected`() {
        assertIs<ApiException.Unexpected>(ApiErrorMapper.fromResponse(418, ""))
    }

    // ── Importing a statement (#31): each refusal its own error ─────────

    @Test
    fun `no consent to AI processing is its own error carrying the version`() {
        val error = ApiErrorMapper.fromResponse(
            409,
            """{"detail":{"code":"consent_required","policy_version":"ai-v1"}}""",
        )
        assertIs<ApiException.ConsentRequired>(error)
        assertEquals("ai-v1", error.policyVersion)
    }

    @Test
    fun `a policy that moved on carries the version now in force`() {
        val error = ApiErrorMapper.fromResponse(
            409,
            """{"detail":{"code":"ai_policy_version_mismatch","current_version":"ai-v2"}}""",
        )
        assertIs<ApiException.AiPolicyChanged>(error)
        assertEquals("ai-v2", error.currentVersion)
    }

    @Test
    fun `a used-up quota carries its limit and when it resets`() {
        val error = ApiErrorMapper.fromResponse(
            429,
            """{"detail":{"code":"import_quota_exceeded","limit":1,"resets_at":"2026-10-01T00:00:00+00:00"}}""",
        )
        assertIs<ApiException.ImportQuotaExceeded>(error)
        assertEquals(1, error.limit)
        assertEquals("2026-10-01T00:00:00+00:00", error.resetsAt)
    }

    @Test
    fun `any other 429 is not called a quota`() {
        assertIs<ApiException.Unexpected>(ApiErrorMapper.fromResponse(429, """{"detail":"slow down"}"""))
    }

    @Test
    fun `the two 413s are two different errors`() {
        assertIs<ApiException.StatementTooLarge>(
            ApiErrorMapper.fromResponse(413, """{"detail":{"code":"statement_too_long"}}"""),
        )
        assertIs<ApiException.TooManyTransactions>(
            ApiErrorMapper.fromResponse(413, """{"detail":{"code":"too_many_transactions"}}"""),
        )
        // A 413 from something in front of the API, with no body we know.
        assertIs<ApiException.StatementTooLarge>(ApiErrorMapper.fromResponse(413, "<html>too large</html>"))
    }

    @Test
    fun `a model failure is safe to retry and says so`() {
        assertIs<ApiException.ParseFailed>(
            ApiErrorMapper.fromResponse(502, """{"detail":{"code":"parse_failed"}}"""),
        )
        assertIs<ApiException.Server>(ApiErrorMapper.fromResponse(502, "bad gateway"))
    }

    @Test
    fun `production refusing to send anything to a model is its own error`() {
        assertIs<ApiException.ImportUnavailable>(
            ApiErrorMapper.fromResponse(503, """{"detail":{"code":"ai_processing_unavailable"}}"""),
        )
        assertIs<ApiException.Server>(ApiErrorMapper.fromResponse(503, "unavailable"))
    }
}
