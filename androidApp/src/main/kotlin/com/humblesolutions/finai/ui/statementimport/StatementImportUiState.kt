package com.humblesolutions.finai.ui.statementimport

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.usecase.ImportProblem
import com.humblesolutions.finai.usecase.ImportStep
import com.humblesolutions.finai.usecase.ImportedRows
import com.humblesolutions.finai.usecase.NewAccountBlock
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.usecase.StatementImportFlow
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

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
            ImportedRows.categoryOf(row, categories)?.name
                ?: LocalizationRegistry.get(Strings.import_extracted_uncategorised, locale),
            amountLabel(row),
        ),
        locale,
    )

    private fun money(amount: String): String = Money.format(amount, currencyOf, locale)

    val account: Account? get() = accounts.firstOrNull { it.id == accountId }

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
