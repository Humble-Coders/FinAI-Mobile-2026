package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.FeatureReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** Maps a non-2xx response to the error a screen renders. Pure, so it is tested without a network. */
internal object ApiErrorMapper {

    // What FastAPI's HTTPBearer answers when no Authorization header was sent.
    private const val MISSING_BEARER = "Not authenticated"
    private const val FEATURE_UNAVAILABLE = "feature_unavailable"
    private const val PHONE_ALREADY_LINKED = "phone_already_linked"
    private const val TERMS_VERSION_MISMATCH = "terms_version_mismatch"
    private const val INVALID_AMOUNT = "invalid_amount"
    private const val DUPLICATE_TRANSACTION = "duplicate_transaction"
    private const val DUPLICATE_ACCOUNT_NAME = "duplicate_account_name"
    private const val CATEGORY_EXISTS = "category_exists"
    private const val UNNAMED_CATEGORY = "unnamed_category"
    private const val WOULD_DUPLICATE = "would_duplicate"
    private const val CONSENT_REQUIRED = "consent_required"
    private const val AI_POLICY_VERSION_MISMATCH = "ai_policy_version_mismatch"
    private const val IMPORT_QUOTA_EXCEEDED = "import_quota_exceeded"
    private const val STATEMENT_TOO_LONG = "statement_too_long"
    private const val TOO_MANY_TRANSACTIONS = "too_many_transactions"
    private const val PARSE_FAILED = "parse_failed"
    private const val AI_PROCESSING_UNAVAILABLE = "ai_processing_unavailable"

    fun fromResponse(status: Int, body: String): ApiException = when (status) {
        401 -> ApiException.Unauthorized("token rejected")

        403 -> forbidden(body)

        404 -> ApiException.NotFound()

        400, 409, 422 -> rejected(status, body)

        // The statement endpoint's own refusals. Two different 413s, because
        // the advice differs: too much text, or too many rows (#31).
        413 -> when (code(body)) {
            TOO_MANY_TRANSACTIONS -> ApiException.TooManyTransactions()
            else -> ApiException.StatementTooLarge()
        }

        429 -> quota(body)

        502 -> if (code(body) == PARSE_FAILED) ApiException.ParseFailed() else ApiException.Server(status)

        503 -> if (code(body) == AI_PROCESSING_UNAVAILABLE) {
            ApiException.ImportUnavailable()
        } else {
            ApiException.Server(status)
        }

        in 500..599 -> ApiException.Server(status)

        else -> ApiException.Unexpected(status)
    }

    /** The `detail.code` of a structured refusal, or null. */
    private fun code(body: String): String? = detail(body)?.string("code")

    private fun detail(body: String): JsonObject? = runCatching { FinAiJson.parseToJsonElement(body).jsonObject["detail"] }.getOrNull() as? JsonObject

    /** 429 is the import quota when it says so; any other 429 stays unexplained. */
    private fun quota(body: String): ApiException {
        val detail = detail(body)
        if (detail?.string("code") != IMPORT_QUOTA_EXCEEDED) return ApiException.Unexpected(429)
        return ApiException.ImportQuotaExceeded(
            limit = detail.string("limit")?.toIntOrNull(),
            resetsAt = detail.string("resets_at"),
        )
    }

    /**
     * A rejection the client can act on specifically, or a generic one.
     *
     * Two codes matter to onboarding: a number that already belongs to another
     * account, and terms that changed between being shown and being accepted.
     * Both need their own screen behaviour, so neither can stay folded into
     * [ApiException.Validation].
     */
    private fun rejected(status: Int, body: String): ApiException {
        val detail = runCatching { FinAiJson.parseToJsonElement(body).jsonObject["detail"] }.getOrNull()
        if (detail is JsonObject) {
            when (detail.string("code")) {
                PHONE_ALREADY_LINKED -> return ApiException.PhoneAlreadyLinked()

                TERMS_VERSION_MISMATCH ->
                    return ApiException.TermsChanged(detail.string("current_version"))

                INVALID_AMOUNT -> return ApiException.InvalidAmount(detail.string("field"))

                DUPLICATE_TRANSACTION -> return ApiException.DuplicateTransaction(
                    // A match that will not decode still leaves a duplicate:
                    // the screen can say "you already have this" without the
                    // detail, which beats falling back to a generic error.
                    detail["duplicate_of"]?.let {
                        runCatching { FinAiJson.decodeFromJsonElement<DuplicateMatch>(it) }
                            .getOrNull()
                    },
                )

                DUPLICATE_ACCOUNT_NAME -> return ApiException.DuplicateAccountName()

                CATEGORY_EXISTS -> return ApiException.CategoryExists(detail.string("category_id"))

                UNNAMED_CATEGORY -> return ApiException.UnnamedCategory()

                WOULD_DUPLICATE -> return ApiException.WouldDuplicate(detail.string("duplicate_of"))

                CONSENT_REQUIRED -> return ApiException.ConsentRequired(detail.string("policy_version"))

                AI_POLICY_VERSION_MISMATCH ->
                    return ApiException.AiPolicyChanged(detail.string("current_version"))
            }
        }
        return ApiException.Validation(status)
    }

    /**
     * 403 is ambiguous on this API. Feature gating answers 403 with a structured
     * `detail`, but FastAPI's bearer check also answers 403 — "Not authenticated"
     * — when no token was sent. Mapping on status alone would tell a signed-out
     * user a feature is disabled, so the body decides.
     */
    private fun forbidden(body: String): ApiException {
        val detail = runCatching { FinAiJson.parseToJsonElement(body).jsonObject["detail"] }.getOrNull()
        if (detail is JsonObject && detail.string("code") == FEATURE_UNAVAILABLE) {
            return ApiException.FeatureUnavailable(
                feature = detail.string("feature").orEmpty(),
                reason = FeatureReason.fromWire(detail.string("reason")),
            )
        }
        if (detail is JsonPrimitive && detail.contentOrNull == MISSING_BEARER) {
            return ApiException.Unauthorized("no token sent")
        }
        return ApiException.Forbidden()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
