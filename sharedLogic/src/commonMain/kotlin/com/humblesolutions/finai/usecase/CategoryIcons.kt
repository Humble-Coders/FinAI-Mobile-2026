package com.humblesolutions.finai.usecase

/**
 * The picture a category is drawn with, by meaning rather than by asset.
 *
 * Each platform maps these to its own icon set — Material on Android, SF
 * Symbols on iOS — so both draw a house for rent and a fork for dining
 * without either knowing the other's names. Keyed on the seeded slugs, which
 * are stable for years; a household's own category, or a slug this build
 * does not know, is [TAG].
 */
enum class CategoryIcon {
    HOME,
    TRANSFER,
    DOCUMENT,
    CAR,
    BAG,
    BOLT,
    DINING,
    CART,
    HEART,
    SALARY,
    SHIELD,
    SCHOOL,
    TICKET,
    GIFT,
    SPA,
    PIGGY,
    REPEAT,
    PLANE,
    CARD,
    PHONE,
    TAG,

    /** A row nothing has filed yet — the one that needs a person. */
    UNFILED,
}

object CategoryIcons {

    private val bySlug = mapOf(
        "rent" to CategoryIcon.HOME,
        "transfers" to CategoryIcon.TRANSFER,
        "fees" to CategoryIcon.DOCUMENT,
        "transport" to CategoryIcon.CAR,
        "shopping" to CategoryIcon.BAG,
        "utilities" to CategoryIcon.BOLT,
        "dining" to CategoryIcon.DINING,
        "groceries" to CategoryIcon.CART,
        "healthcare" to CategoryIcon.HEART,
        "income" to CategoryIcon.SALARY,
        "insurance" to CategoryIcon.SHIELD,
        "education" to CategoryIcon.SCHOOL,
        "entertainment" to CategoryIcon.TICKET,
        "gifts_donations" to CategoryIcon.GIFT,
        "personal_care" to CategoryIcon.SPA,
        "savings" to CategoryIcon.PIGGY,
        "subscriptions" to CategoryIcon.REPEAT,
        "travel" to CategoryIcon.PLANE,
        "debt_payment" to CategoryIcon.CARD,
        "other" to CategoryIcon.TAG,
    )

    /** The icon for a row filed under [slug]; [CategoryIcon.UNFILED] for none. */
    fun forSlug(slug: String?): CategoryIcon = when {
        slug == null -> CategoryIcon.UNFILED
        else -> bySlug[slug] ?: CategoryIcon.TAG
    }
}
