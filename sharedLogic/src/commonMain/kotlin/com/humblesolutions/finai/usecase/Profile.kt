package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Me

/**
 * Profile (PM decision, 2026-11-06): who the person is, their settings, every
 * consent and disclaimer, signing out and deleting the account. Opened from
 * where Home's bell was. Shared so both apps say and refuse the same things.
 */
object Profile {

    /** What the person types to confirm deleting the account. */
    const val DELETE_WORD = "DELETE"

    const val MAX_NAME_LENGTH = 80

    /** The name shown at the top: theirs, else their email or phone, else nothing. */
    fun displayName(me: Me): String = listOfNotNull(me.user.displayName, me.user.email, me.user.phone)
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

    /**
     * Up to two letters for the monogram in Home's header: a name's first and
     * last initials, else an email's first letter, else a figure for a phone.
     */
    fun initials(me: Me): String {
        val name = me.user.displayName?.trim().orEmpty()
        if (name.isNotEmpty()) {
            val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val letters = if (words.size > 1) "${words.first().first()}${words.last().first()}" else words.first().take(1)
            return letters.uppercase()
        }
        me.user.email?.trim()?.firstOrNull { it.isLetterOrDigit() }?.let { return it.uppercase() }
        return "#"
    }

    /** Why [name] cannot be saved, as a message key, or null. */
    fun nameBlock(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Strings.profile_name_missing
            trimmed.length > MAX_NAME_LENGTH -> Strings.profile_name_too_long
            else -> null
        }
    }

    /** Delete is only offered once the person has typed the word exactly. */
    fun canDelete(typed: String): Boolean = typed.trim() == DELETE_WORD
}
