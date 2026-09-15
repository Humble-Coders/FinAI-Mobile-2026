package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FeatureReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Maps a non-2xx response to the error a screen renders. Pure, so it is tested without a network. */
internal object ApiErrorMapper {

    // What FastAPI's HTTPBearer answers when no Authorization header was sent.
    private const val MISSING_BEARER = "Not authenticated"
    private const val FEATURE_UNAVAILABLE = "feature_unavailable"
    private const val PHONE_ALREADY_LINKED = "phone_already_linked"
    private const val TERMS_VERSION_MISMATCH = "terms_version_mismatch"
    private const val INVALID_ORPHAN_TOKEN = "invalid_orphan_token"
    private val LINK_REFUSED = setOf("orphan_not_empty", "link_target_incomplete", "nothing_to_link")

    fun fromResponse(status: Int, body: String): ApiException = when (status) {
        401 -> ApiException.Unauthorized("token rejected")
        403 -> forbidden(body)
        404 -> ApiException.NotFound()
        400, 409, 422 -> rejected(status, body)
        in 500..599 -> ApiException.Server(status)
        else -> ApiException.Unexpected(status)
    }

    /**
     * A rejection the client can act on specifically, or a generic one.
     *
     * Onboarding acts on these codes: a number that already belongs to another
     * account, terms that changed between being shown and being accepted, and
     * the ways linking a sign-in method can be refused. Each needs its own
     * screen behaviour, so none can stay folded into [ApiException.Validation].
     */
    private fun rejected(status: Int, body: String): ApiException {
        val detail = runCatching { FinAiJson.parseToJsonElement(body).jsonObject["detail"] }.getOrNull()
        if (detail is JsonObject) {
            when (detail.string("code")) {
                PHONE_ALREADY_LINKED -> return ApiException.PhoneAlreadyLinked()
                TERMS_VERSION_MISMATCH ->
                    return ApiException.TermsChanged(detail.string("current_version"))
                INVALID_ORPHAN_TOKEN -> return ApiException.LinkExpired()
                in LINK_REFUSED -> return ApiException.LinkRefused()
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
