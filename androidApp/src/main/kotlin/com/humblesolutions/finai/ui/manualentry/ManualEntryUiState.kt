package com.humblesolutions.finai.ui.manualentry

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ManualEntryBlock
import com.humblesolutions.finai.usecase.ManualEntryDraft
import com.humblesolutions.finai.usecase.NewAccountBlock
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * The server said this entry matches one already there. [match] is what it
 * matched, when the server named it; the warning is shown either way.
 */
data class DuplicateWarning(val match: DuplicateMatch?)

/**
 * Everything the manual entry screen shows (#30).
 *
 * The gates are computed here from the shared rules, never in a composable, so
 * a test asserts them with a plain constructor (kmp-arch-v2).
 */
data class ManualEntryUiState(
    val draft: ManualEntryDraft = ManualEntryDraft(),
    /** The device's date when the state was last touched: what "Today" and "the future" mean. */
    val today: LocalDate = ManualEntry.today(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** The language figures are written in, from capabilities. Blank formats as English. */
    val locale: String = "",
    /** Accounts are loading; the app's coin loader covers the screen. */
    val loading: Boolean = true,
    /** Accounts could not be had, so there is nothing to file into; the screen offers a retry. */
    val loadFailed: Boolean = false,
    /** The entry is on its way to the server; Save must not send it twice. */
    val saving: Boolean = false,
    /** Whether the user has tried to save or changed anything — unanswered fields are not scolded before. */
    val touched: Boolean = false,
    val errorKey: String? = null,
    /** The last save went through; cleared by the next change. */
    val saved: Boolean = false,
    val duplicate: DuplicateWarning? = null,
    /** An account being added, while its sheet is open. */
    val newAccount: NewAccountDraft? = null,
    val newAccountTouched: Boolean = false,
    val creatingAccount: Boolean = false,
    val newAccountErrorKey: String? = null,
) {
    /** The chosen account, if it is one of the household's. */
    val account: Account? get() = accounts.firstOrNull { it.id == draft.accountId }

    /** The chosen account's currency. Null until one is chosen — never a guessed one. */
    val currency: String? get() = account?.currency?.ifBlank { null }

    val fractionDigits: Int get() = currency?.let(Money::fractionDigits) ?: 2

    /** What to draw beside the amount: blank until an account says what it is in. */
    val symbol: String get() = currency?.let(Money::symbol).orEmpty()

    /** Zero, written at the currency's scale — the faint figure in an empty amount box. */
    val amountPlaceholder: String get() = Money.normalize("0", fractionDigits).orEmpty()

    /** Why Save is disabled, or null — the one rule, from shared. */
    val block: ManualEntryBlock? get() = ManualEntry.blockingReason(draft, currency, today)

    /** What the notice under Save says: the same rule, held back until the form is touched. */
    val notice: ManualEntryBlock? get() = ManualEntry.notice(draft, currency, today, touched)

    val canSave: Boolean get() = !loading && !loadFailed && !saving && block == null

    val dateLabel: String? get() = draft.occurredOn?.let(Dates::display)

    /** The chosen category's name, or null when the backend is to choose. */
    val categoryName: String? get() = categories.firstOrNull { it.id == draft.categoryId }?.name

    /** The duplicate warning's sentence, written by shared so both apps say the same. */
    val duplicateMessage: String?
        get() = duplicate?.let { ManualEntry.duplicateMessage(it.match, currency.orEmpty(), locale) }

    val newAccountNotice: NewAccountBlock?
        get() = newAccount?.let { NewAccountForm.notice(it, newAccountTouched) }

    val canCreateAccount: Boolean
        get() = newAccount != null && !creatingAccount && NewAccountForm.blockingReason(newAccount) == null
}
