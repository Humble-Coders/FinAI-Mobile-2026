package com.humblesolutions.finai.ui.statementimport

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.usecase.ImportProblem
import com.humblesolutions.finai.usecase.ImportStep
import com.humblesolutions.finai.usecase.NewAccountBlock
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.usecase.StatementImportFlow

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
) {
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
