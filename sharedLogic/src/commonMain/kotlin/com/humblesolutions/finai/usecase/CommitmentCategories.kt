package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings

/**
 * The spending categories a commitment can belong to, and the one each
 * setup preset belongs to.
 *
 * A commitment's category is its budget line: Rent in `rent` holds that line
 * at $1,800 or more. The server takes the same slugs (`COMMITMENT_SLUGS` in
 * `app/services/commitments.py`) — not income or transfers (not spending),
 * not savings (the budget's remainder), and not debt payments (debts are
 * entered as debts).
 */
object CommitmentCategories {

    /** In the order the picker lists them: the usual homes of a bill first. */
    val SLUGS: List<String> = listOf(
        "rent",
        "utilities",
        "insurance",
        "subscriptions",
        "transport",
        "groceries",
        "healthcare",
        "personal_care",
        "education",
        "dining",
        "entertainment",
        "shopping",
        "travel",
        "gifts_donations",
        "fees",
        "other",
    )

    /** Each preset's category, by its name key. */
    private val PRESETS: Map<String, String> = mapOf(
        Strings.setup_commitment_rent to "rent",
        Strings.setup_commitment_mortgage to "rent",
        Strings.setup_commitment_phone to "utilities",
        Strings.setup_commitment_internet to "utilities",
        Strings.setup_commitment_hydro to "utilities",
        Strings.setup_commitment_car_insurance to "insurance",
        Strings.setup_commitment_home_insurance to "insurance",
        Strings.setup_commitment_childcare to "other",
        Strings.setup_commitment_gym to "subscriptions",
        Strings.setup_commitment_streaming to "subscriptions",
        Strings.setup_commitment_transit to "transport",
    )

    /** "Rent & mortgage" for `rent`. */
    fun nameOf(slug: String, locale: String = "en"): String = LocalizationRegistry.get("category_$slug", locale)

    /**
     * The category of the preset called [name] — in [locale] or in English,
     * as the server falls back on — or null for a name no preset has.
     */
    fun presetSlug(name: String, locale: String = "en"): String? {
        val wanted = name.trim().lowercase()
        return PRESETS.entries.firstOrNull { (key, _) ->
            LocalizationRegistry.get(key, locale).lowercase() == wanted ||
                LocalizationRegistry.get(key, "en").lowercase() == wanted
        }?.value
    }

    fun isCategory(slug: String?): Boolean = slug != null && slug in SLUGS
}
