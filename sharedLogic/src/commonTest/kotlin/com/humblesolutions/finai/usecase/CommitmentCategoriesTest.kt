package com.humblesolutions.finai.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitmentCategoriesTest {

    @Test
    fun every_preset_has_a_category_a_commitment_can_have() {
        SetupWizard.COMMITMENT_PRESETS.forEach { key ->
            val slug = CommitmentCategories.presetSlug(com.humblesolutions.finai.i18n.LocalizationRegistry.get(key))
            assertTrue(CommitmentCategories.isCategory(slug), key)
        }
    }

    @Test
    fun a_preset_is_found_by_name_ignoring_case_and_spaces() {
        assertEquals("rent", CommitmentCategories.presetSlug(" rent "))
        assertEquals("insurance", CommitmentCategories.presetSlug("Car Insurance"))
        assertNull(CommitmentCategories.presetSlug("Rogers"))
    }

    @Test
    fun money_that_is_not_spending_is_never_a_commitment_category() {
        listOf("income", "transfers", "savings", "debt_payment", "").forEach { assertFalse(CommitmentCategories.isCategory(it), it) }
        assertEquals("Rent & mortgage", CommitmentCategories.nameOf("rent"))
    }
}
