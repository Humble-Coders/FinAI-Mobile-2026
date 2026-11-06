package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.MeUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTest {

    private fun me(name: String? = null, email: String? = null, phone: String? = null) = Me(user = MeUser(id = "u", email = email, phone = phone, displayName = name))

    @Test
    fun the_name_shown_is_theirs_else_their_email_else_their_phone() {
        assertEquals("Sam Rivera", Profile.displayName(me(name = " Sam Rivera ", email = "sam@example.test")))
        assertEquals("sam@example.test", Profile.displayName(me(name = " ", email = "sam@example.test")))
        assertEquals("+14165550100", Profile.displayName(me(phone = "+14165550100")))
        assertEquals("", Profile.displayName(me()))
    }

    @Test
    fun the_monogram_is_first_and_last_initials() {
        assertEquals("SR", Profile.initials(me(name = "sam  rivera")))
        assertEquals("SJ", Profile.initials(me(name = "Sam Alex Jones")))
        assertEquals("P", Profile.initials(me(name = "Priya")))
        assertEquals("S", Profile.initials(me(email = "sam@example.test")))
        assertEquals("#", Profile.initials(me(phone = "+14165550100")))
    }

    @Test
    fun a_name_must_be_there_and_not_too_long() {
        assertEquals(Strings.profile_name_missing, Profile.nameBlock("   "))
        assertEquals(Strings.profile_name_too_long, Profile.nameBlock("x".repeat(81)))
        assertNull(Profile.nameBlock(" Sam "))
    }

    @Test
    fun deleting_needs_the_word_typed_exactly() {
        assertTrue(Profile.canDelete(" DELETE "))
        assertFalse(Profile.canDelete("delete"))
        assertFalse(Profile.canDelete(""))
    }
}
