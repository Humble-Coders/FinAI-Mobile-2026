package com.humblesolutions.finai.ui.statementimport

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.ui.edit.TransactionEditorState
import com.humblesolutions.finai.usecase.Breakdown
import com.humblesolutions.finai.usecase.CheckRow
import com.humblesolutions.finai.usecase.CorrectionBlock
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.usecase.ImportCheck
import com.humblesolutions.finai.usecase.ImportProblem
import com.humblesolutions.finai.usecase.ImportStep
import com.humblesolutions.finai.usecase.ImportedRows
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.NewAccountBlock
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.usecase.ReviewQueue
import com.humblesolutions.finai.usecase.StatementBalance
import com.humblesolutions.finai.usecase.StatementImportFlow
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * Everything the import screen shows (#31).
 *
 * What each ending says and offers comes from the shared rules
 * ([StatementImportFlow]); the gates here are computed values so a test
 * asserts them with a plain constructor (kmp-arch-v2).
 */
data class StatementImportUiState(
    val step: ImportStep = ImportStep.CHOOSE_ACCOUNT,
    val accounts: List<Account> = emptyList(),
    val accountsLoading: Boolean = true,
    val accountsErrorKey: String? = null,
    /** Chosen, never defaulted: the wrong account breaks dedup for both. */
    val accountId: String? = null,
    val newAccount: NewAccountDraft? = null,
    val newAccountTouched: Boolean = false,
    val creatingAccount: Boolean = false,
    val newAccountErrorKey: String? = null,
    /** The picked file, as the platform names it. Survives the process being killed. */
    val fileUri: String? = null,
    /** The last password was wrong — the prompt says so. */
    val passwordWrong: Boolean = false,
    val page: Int = 0,
    val pages: Int = 0,
    val policy: AiPolicy? = null,
    /** Unticked until the person ticks it — express consent, nothing pre-ticked. */
    val consentTicked: Boolean = false,
    val consentBusy: Boolean = false,
    val consentErrorKey: String? = null,
    val problem: ImportProblem? = null,
    /**
     * The redacted document is still in memory, so it can be sent again —
     * the only way diagnostics can be offered (manager decision, 2026-09-30).
     */
    val canResend: Boolean = false,
    /** The "help us fix this" box — unticked every time, never remembered. */
    val diagnosticsTicked: Boolean = false,
    val diagnosticsSent: Boolean = false,
    val diagnosticsThanks: String? = null,
    val summary: List<String> = emptyList(),
    val needsReview: Int = 0,

    /** The import just finished, so its rows can be asked for by id. */
    val importId: String? = null,
    /**
     * What the import actually produced.
     *
     * "Imported 24 transactions" is a claim the person cannot check, and the
     * one question they have is whether the categories are right. These are
     * the 24.
     */
    val imported: List<Transaction> = emptyList(),
    val importedLoading: Boolean = false,
    /** A list that would not load must not make a successful import look failed. */
    val importedErrorKey: String? = null,
    val categories: List<Category> = emptyList(),
    val locale: String = "en",

    /**
     * The statement as read and not yet saved (#55): exactly what Save sends.
     * Memory only — a household's figures never go into saved state.
     */
    val checkRows: List<CheckRow> = emptyList(),
    /** The app was ended mid-check and the statement read again, so earlier changes are gone. */
    val readAgain: Boolean = false,
    /**
     * The closing balance read on the phone, for the debt linked to this
     * account (#76) — offered, never applied unasked. Null when there is none.
     */
    val balanceOffer: StatementBalance.Offer? = null,
    /** The person's yes to updating that debt's balance. Starts unticked. */
    val updateBalance: Boolean = false,
    val confirmingDiscard: Boolean = false,
    /** Why Save failed. The checked rows are kept, so it can be pressed again. */
    val saveErrorKey: String? = null,

    /** One row open in the editor: not saved yet ([ImportStep.CHECK]) or saved ([ImportStep.DONE]). */
    val editing: Transaction? = null,
    val draft: CorrectionDraft = CorrectionDraft(),
    val editSaving: Boolean = false,
    val deleting: Boolean = false,
    val editErrorKey: String? = null,
    val today: LocalDate = ManualEntry.today(),
    val newCategoryName: String? = null,
    val creatingCategory: Boolean = false,
    val newCategoryErrorKey: String? = null,
) {
    private val fractionDigits: Int get() = Money.fractionDigits(currencyOf)

    /** The currency these rows are in — the account's, since one import is one account. */
    private val currencyOf: String get() = imported.firstOrNull()?.currency ?: account?.currency.orEmpty()

    /** What left the account across the import, formatted, or null when nothing did. */
    val totalOut: String? get() = ImportedRows.totalOut(imported, fractionDigits)?.let { money(it) }

    /** What arrived, formatted, or null when nothing did. */
    val totalIn: String? get() = ImportedRows.totalIn(imported, fractionDigits)?.let { money(it) }

    /** "3 need you", or null when the model filed every row. */
    val waitingLabel: String? get() = ImportedRows.waitingCount(imported).takeIf { it > 0 }?.let { count ->
        if (count == 1) {
            LocalizationRegistry.get(Strings.import_extracted_waiting_one, locale)
        } else {
            LocalizationRegistry.format(Strings.import_extracted_waiting, listOf(count.toString()), locale)
        }
    }

    /** A day's heading, as a person reads a date. */
    fun dateLabel(iso: String): String = Dates.parse(iso)?.let { Dates.display(it) } ?: iso

    /** One row's amount, formatted in its own currency. */
    fun amountLabel(row: Transaction): String = Money.format(row.amount, row.currency, locale)

    /**
     * The whole row as one sentence, so a screen reader announces it once
     * instead of stopping at the name, the category and the amount in turn.
     */
    fun rowDescription(row: Transaction): String = LocalizationRegistry.format(
        Strings.import_extracted_row,
        listOf(
            ImportedRows.titleOf(row),
            ImportedRows.categoryLabel(row, categories, LocalizationRegistry.get(Strings.import_extracted_uncategorised, locale)).orEmpty(),
            amountLabel(row),
        ),
        locale,
    )

    private fun money(amount: String): String = Money.format(amount, currencyOf, locale)

    val account: Account? get() = accounts.firstOrNull { it.id == accountId }

    // ── Checking before saving (#55) ────────────────────────────────────

    private val checked: List<Transaction> get() = ImportCheck.transactions(checkRows)

    private val checkDigits: Int get() = Money.fractionDigits(checkCurrency)

    private val checkCurrency: String get() = checkRows.firstOrNull()?.row?.currency ?: account?.currency.orEmpty()

    /** The rows grouped by their day on the statement, newest first, as after saving. */
    val checkDays: List<ImportedRows.Day> get() = ImportedRows.byDate(checked)

    fun checkRowOf(row: Transaction): CheckRow? = checkRows.firstOrNull { it.row.id == row.id }

    val checkCount: String get() = ImportCheck.countLabel(checkRows, locale)

    /**
     * Where the statement's money goes, by category, as Home will count it.
     * Null until the category names have loaded: without them nothing is known
     * to be a transfer, and the figures would count a card payment as spending.
     */
    val checkBreakdown: Breakdown?
        get() = if (categories.isEmpty() && checkRows.any { it.row.categoryId != null }) {
            null
        } else {
            ImportCheck.breakdown(checkRows, categories, checkDigits)
        }

    fun checkMoney(amount: String): String = Money.format(amount, checkCurrency, locale)

    /** Money out, without transfers and savings once the breakdown is known. */
    val checkTotalOut: String?
        get() = (checkBreakdown?.let { it.totalOut } ?: ImportedRows.totalOut(checked, checkDigits))?.let(::checkMoney)

    /** Money in, without transfers and savings once the breakdown is known. */
    val checkTotalIn: String?
        get() = (checkBreakdown?.let { it.totalIn } ?: ImportedRows.totalIn(checked, checkDigits))?.let(::checkMoney)

    /** A row's category, "No category yet", or null while the names load. */
    fun checkCategoryLabel(row: Transaction): String? = ImportedRows.categoryLabel(row, categories, LocalizationRegistry.get(Strings.import_extracted_uncategorised, locale))

    val saveCheckedLabel: String get() = ImportCheck.saveLabel(checkRows, locale)

    /** "This statement says you owed $X on …. Update Visa to this balance?", or null with nothing to offer. */
    val balanceQuestion: String? get() = balanceOffer?.let { StatementBalance.question(it, checkCurrency, locale) }

    val canSaveChecked: Boolean get() = step == ImportStep.CHECK && ImportCheck.canSave(checkRows) && editing == null

    /** Everything was removed: only Discard is left. */
    val noneLeft: Boolean get() = step == ImportStep.CHECK && checkRows.isEmpty()

    /** What a row says beside its amount: a doubt to look at, or that the person changed it. */
    fun checkNoteKey(row: Transaction): String? {
        val checkRow = checkRowOf(row) ?: return null
        return when {
            ImportCheck.needsCheck(checkRow) -> Strings.import_check_doubt
            checkRow.edited -> Strings.import_check_edited
            else -> null
        }
    }

    /** The whole row as one sentence, so a screen reader announces it once. */
    fun checkRowDescription(row: Transaction): String = listOfNotNull(
        dateLabel(row.occurredOn),
        ImportedRows.titleOf(row),
        checkCategoryLabel(row),
        amountLabel(row),
        checkNoteKey(row)?.let { LocalizationRegistry.get(it, locale) },
    ).joinToString(", ")

    fun checkRowHint(row: Transaction): String = LocalizationRegistry.format(Strings.import_check_row_hint, listOf(ImportedRows.titleOf(row)), locale)

    fun editHint(row: Transaction): String = LocalizationRegistry.format(Strings.import_extracted_edit_hint, listOf(ImportedRows.titleOf(row)), locale)

    /**
     * The editor sheet's view of the row being edited — the same sheet and the
     * same shared rules as Transactions and Review. Before saving there is no
     * category to choose, and a row is removed rather than deleted.
     */
    val editor: TransactionEditorState?
        get() = editing?.let { row ->
            val block = ReviewQueue.blockingReason(row, draft, today)
            val before = step == ImportStep.CHECK
            TransactionEditorState(
                row = row,
                draft = draft,
                // "Nothing has changed" is not worth saying before a touch.
                notice = block?.takeIf { it != CorrectionBlock.NOTHING_CHANGED },
                errorKey = editErrorKey,
                canSave = !editSaving && block == null,
                saving = editSaving,
                categoryName = ReviewQueue.categoryName(categories, draft.categoryId),
                pickableCategories = categories
                    .map { it to ReviewQueue.categoryName(it) }
                    .sortedWith(compareBy({ it.first.isSystem }, { it.second.lowercase() })),
                today = today,
                newCategoryName = newCategoryName,
                newCategoryErrorKey = newCategoryErrorKey,
                canCreateCategory = !creatingCategory && !newCategoryName.isNullOrBlank(),
                creatingCategory = creatingCategory,
                titleKey = if (before) Strings.import_check_edit_title else Strings.transactions_edit_title,
                deleting = deleting,
                deleteSummary = ImportedRows.summary(row, locale),
                // Before saving too: the category is checked with the rest.
                showsCategory = true,
                deleteKey = if (before) Strings.import_check_remove else Strings.transaction_delete,
                deleteConfirmTitleKey = if (before) Strings.import_check_remove_title else Strings.transaction_delete_confirm_title,
                deleteConfirmBodyKey = if (before) Strings.import_check_remove_body else Strings.transaction_delete_confirm_body,
                deleteConfirmKey = if (before) Strings.import_check_remove else Strings.transaction_delete_confirm,
            )
        }

    val canContinueFromAccount: Boolean get() = account != null

    /** Reading, sending or saving: the app's coin loader covers the screen. */
    val working: Boolean
        get() = step == ImportStep.READING || step == ImportStep.SENDING || step == ImportStep.SAVING

    val problemMessage: String? get() = problem?.let(StatementImportFlow::message)

    val offersRetry: Boolean get() = problem?.failure?.canRetry == true

    /** Typing transactions in — the other way in, one tap from here (#30). */
    val offersManualEntry: Boolean get() = problem?.failure?.offersManualEntry == true

    /**
     * Only after an import the server read and failed on, only while the text
     * can still be sent, and only once. Declining sends nothing.
     */
    val offersDiagnostics: Boolean
        get() = problem?.failure?.offersDiagnostics == true && canResend && !diagnosticsSent

    val canSendDiagnostics: Boolean get() = offersDiagnostics && diagnosticsTicked

    val canAgree: Boolean get() = policy != null && consentTicked && !consentBusy

    val newAccountNotice: NewAccountBlock?
        get() = newAccount?.let { NewAccountForm.notice(it, newAccountTouched) }

    val canCreateAccount: Boolean
        get() = newAccount != null && !creatingAccount && NewAccountForm.blockingReason(newAccount) == null
}
