package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.NewAccount

/**
 * An account being added from the manual entry screen (#30).
 *
 * The kind starts **unanswered**: it decides how the account's money is read
 * later (a credit card's balance is owed, a chequing one is held), so a
 * preselected "Chequing" would quietly misfile every card added in a hurry.
 */
data class NewAccountDraft(
    val name: String = "",
    val kind: AccountKind? = null,
)

/** Why Add cannot go ahead — one reason, in form order. */
enum class NewAccountBlock(val messageKey: String, val isUnanswered: Boolean) {
    NO_NAME(Strings.account_new_block_no_name, isUnanswered = true),
    NAME_TOO_LONG(Strings.account_new_block_name_too_long, isUnanswered = false),
    NO_KIND(Strings.account_new_block_no_kind, isUnanswered = true),
}

/** The rules for [NewAccountDraft] — the button, the notice and the request all read [blockingReason]. */
object NewAccountForm {

    /**
     * The backend's limit on an account's name (`AccountIn.name`). Not
     * `NAME_MAX`: that is a C macro, and the iOS header would not compile.
     */
    const val MAX_NAME_LENGTH = 255

    fun blockingReason(draft: NewAccountDraft): NewAccountBlock? {
        val name = draft.name.trim()
        if (name.isEmpty()) return NewAccountBlock.NO_NAME
        // UTF-16 units here, code points on the server: only ever stricter.
        if (name.length > MAX_NAME_LENGTH) return NewAccountBlock.NAME_TOO_LONG
        if (draft.kind == null || draft.kind == AccountKind.UNKNOWN) return NewAccountBlock.NO_KIND
        return null
    }

    /** As [ManualEntry.notice]: a blank field is not scolded until the form is [touched]. */
    fun notice(draft: NewAccountDraft, touched: Boolean): NewAccountBlock? {
        val block = blockingReason(draft) ?: return null
        return block.takeIf { touched || !it.isUnanswered }
    }

    /** The request, or null while [blockingReason] says no. */
    fun request(draft: NewAccountDraft): NewAccount? {
        if (blockingReason(draft) != null) return null
        return NewAccount(name = draft.name.trim(), kind = draft.kind ?: return null)
    }
}
