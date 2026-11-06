package com.humblesolutions.finai.ui.profile

import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.usecase.Profile

/** Which text the reading sheet is showing. */
enum class LegalText { TERMS, DISCLAIMER }

/**
 * Everything Profile shows. What a name may be, the monogram and the delete
 * gate are shared `Profile`'s; this only holds the answers.
 */
data class ProfileUiState(
    val me: Me? = null,
    val loading: Boolean = true,
    val loadErrorKey: String? = null,

    // ── The name sheet ──────────────────────────────────────────────────
    val editingName: Boolean = false,
    val nameDraft: String = "",
    val nameErrorKey: String? = null,
    val savingName: Boolean = false,

    // ── Region ──────────────────────────────────────────────────────────
    val regionBusy: Boolean = false,
    val regionErrorKey: String? = null,

    // ── Terms and the disclaimer ────────────────────────────────────────
    /** The region's disclaimer; null when it has none, which hides its row. */
    val disclaimer: Terms? = null,
    /** The text open in the reading sheet, or null when it is closed. */
    val reading: LegalText? = null,
    /** The terms' text, read when first opened. */
    val terms: Terms? = null,
    val readingErrorKey: String? = null,

    // ── AI processing ───────────────────────────────────────────────────
    /** Null until read, which leaves the row without a status. */
    val aiStatus: AiConsentStatus? = null,
    /** The "Turn on" sheet is open. */
    val turningAiOn: Boolean = false,
    /** The policy shown in that sheet; its version is the one agreed to. */
    val aiPolicy: AiPolicy? = null,
    /** Nothing is pre-ticked: agreeing is a deliberate act. */
    val aiTicked: Boolean = false,
    val aiBusy: Boolean = false,
    val aiErrorKey: String? = null,
    /** "Turn off" asks first. */
    val confirmingAiOff: Boolean = false,

    // ── Deleting the account ────────────────────────────────────────────
    val confirmingDelete: Boolean = false,
    val deleteTyped: String = "",
    val deleting: Boolean = false,
    val deleteErrorKey: String? = null,

    /** A one-off line under the header — "Saved", "Your setup is updated". */
    val noticeKey: String? = null,
) {
    val displayName: String get() = me?.let(Profile::displayName).orEmpty()

    val initials: String get() = me?.let(Profile::initials).orEmpty()

    val email: String? get() = me?.user?.email?.takeIf { it.isNotBlank() }

    val phone: String? get() = me?.user?.phone?.takeIf { it.isNotBlank() }

    val countryCode: String? get() = me?.household?.countryCode?.takeIf { it.isNotBlank() }

    /** The terms version this person accepted, or null when none is on record. */
    val acceptedTerms: String? get() = me?.terms?.takeIf { it.accepted }?.version

    val aiOn: Boolean get() = aiStatus?.consented == true

    val canAgreeToAi: Boolean get() = aiPolicy != null && aiTicked && !aiBusy

    val canDelete: Boolean get() = Profile.canDelete(deleteTyped) && !deleting

    /** The body the reading sheet shows, once it has been read. */
    val readingBody: String? get() = when (reading) {
        LegalText.TERMS -> terms?.body
        LegalText.DISCLAIMER -> disclaimer?.body
        null -> null
    }
}
