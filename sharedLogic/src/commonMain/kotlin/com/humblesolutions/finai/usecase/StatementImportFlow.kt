package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.RowToSave
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.repository.StatementReadException
import com.humblesolutions.finai.util.Dates

/**
 * Where an import is, for the screen that shows it (#31).
 *
 * The account comes first and is never defaulted: an import filed against the
 * wrong account silently breaks dedup for both. Consent is a step, not an
 * error — `409 consent_required` routes here.
 */
enum class ImportStep {
    CHOOSE_ACCOUNT,
    CHOOSE_FILE,
    CONSENT,
    PASSWORD,
    READING,
    SENDING,
    SAVING,
    DONE,
    FAILED,
}

/**
 * Every way an import can end without rows, each with its own words (#31).
 *
 * "Something went wrong" for a quota limit generates support mail, and the two
 * size refusals are the cases where the person can act. So each outcome says
 * what happened and what to do, and carries what the screen offers next.
 *
 * @property canRetry the same file again is worth trying — the file is still on
 *   the phone, and a failed import does not use up the month.
 * @property offersManualEntry typing transactions in is the other way in, by
 *   design: offered wherever the statement cannot be imported.
 * @property offersDiagnostics the "help us fix this" offer (unticked). Only
 *   after an import the **server** read and failed on — there is redacted text to
 *   keep, and a failed import does not count against the quota, so sending it
 *   again is allowed (manager decision, 2026-09-30).
 */
enum class ImportFailure(
    val messageKey: String,
    val canRetry: Boolean,
    val offersManualEntry: Boolean,
    val offersDiagnostics: Boolean,
) {
    UNSUPPORTED_FILE(Strings.statement_unsupported, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    NOTHING_READABLE(Strings.statement_nothing_readable, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    NOTHING_TO_SEND(Strings.statement_no_transactions, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    TOO_MANY_PAGES(Strings.statement_too_many_pages, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    TOO_LONG(Strings.statement_too_long, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    TOO_MANY_TRANSACTIONS(Strings.import_too_many_transactions, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    NO_TRANSACTIONS_FOUND(Strings.statement_no_transactions, canRetry = false, offersManualEntry = true, offersDiagnostics = true),
    PARSE_FAILED(Strings.import_parse_failed, canRetry = true, offersManualEntry = true, offersDiagnostics = true),
    QUOTA_USED(Strings.import_quota_exceeded, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    UNAVAILABLE(Strings.import_unavailable, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    FEATURE_UNAVAILABLE(Strings.import_feature_unavailable, canRetry = false, offersManualEntry = true, offersDiagnostics = false),
    NETWORK(Strings.error_network, canRetry = true, offersManualEntry = false, offersDiagnostics = false),
    OTHER(Strings.error_unexpected, canRetry = true, offersManualEntry = true, offersDiagnostics = false),
}

/** A failure and the one detail some of them need — when the quota resets. */
data class ImportProblem(
    val failure: ImportFailure,
    /** ISO date or instant from the 429; only for [ImportFailure.QUOTA_USED]. */
    val resetsAt: String? = null,
)

/**
 * The rules of importing a statement — **the one place they live**. Both apps
 * read these; the screens only show the answers (kmp-arch-v2).
 */
object StatementImportFlow {

    /**
     * What a thrown error means for the import, or null when it is not a
     * failure at all: consent needed (a step) or a password needed (a prompt).
     * Reading errors are the platform reader's (Android's), refusals are
     * [ImportStatement]'s, and the rest are the server's.
     */
    fun problemFor(error: Throwable): ImportProblem? = when (error) {
        is ApiException.ConsentRequired, is ApiException.AiPolicyChanged -> null
        is StatementReadException.PasswordRequired -> null
        is StatementReadException.Unsupported -> ImportProblem(ImportFailure.UNSUPPORTED_FILE)
        is StatementReadException.NothingReadable -> ImportProblem(ImportFailure.NOTHING_READABLE)
        is StatementReadException.TooManyPages, is StatementTooManyPages ->
            ImportProblem(ImportFailure.TOO_MANY_PAGES)
        is StatementTooLong, is ApiException.StatementTooLarge -> ImportProblem(ImportFailure.TOO_LONG)
        is StatementHasNothingToSend -> ImportProblem(ImportFailure.NOTHING_TO_SEND)
        is ApiException.TooManyTransactions -> ImportProblem(ImportFailure.TOO_MANY_TRANSACTIONS)
        is ApiException.ParseFailed -> ImportProblem(ImportFailure.PARSE_FAILED)
        is ApiException.ImportQuotaExceeded -> ImportProblem(ImportFailure.QUOTA_USED, error.resetsAt)
        is ApiException.ImportUnavailable -> ImportProblem(ImportFailure.UNAVAILABLE)
        is ApiException.FeatureUnavailable -> ImportProblem(ImportFailure.FEATURE_UNAVAILABLE)
        is ApiException.Network -> ImportProblem(ImportFailure.NETWORK)
        else -> ImportProblem(ImportFailure.OTHER)
    }

    /** Whether [error] means "show the consent step". */
    fun needsConsent(error: Throwable): Boolean =
        error is ApiException.ConsentRequired || error is ApiException.AiPolicyChanged

    /**
     * A parse that came back with no rows is a failure — the server records it
     * as one (so it does not use up the month), and so does the screen.
     */
    fun problemAfterParse(parsed: ParsedStatement): ImportProblem? =
        if (parsed.rows.isEmpty()) ImportProblem(ImportFailure.NO_TRANSACTIONS_FOUND) else null

    /** The parsed rows, sent back unchanged to be saved against the chosen account. */
    fun rowsToSave(accountId: String, parsed: ParsedStatement): RowsToSave =
        RowsToSave(
            accountId = accountId,
            rows = parsed.rows.map {
                RowToSave(
                    occurredOn = it.occurredOn,
                    description = it.description,
                    amount = it.amount,
                    direction = it.direction,
                    confidence = it.confidence,
                )
            },
        )

    /** The sentence the failure screen shows, written here so both apps say the same. */
    fun message(problem: ImportProblem): String {
        if (problem.failure == ImportFailure.QUOTA_USED) {
            val resets = problem.resetsAt?.take(10)?.let(Dates::parse)
            return if (resets != null) {
                LocalizationRegistry.format(Strings.import_quota_exceeded_until, listOf(Dates.display(resets)))
            } else {
                LocalizationRegistry.get(Strings.import_quota_exceeded)
            }
        }
        return LocalizationRegistry.get(problem.failure.messageKey)
    }

    /** "Reading page 3 of 12…", or null before the reader has said how many. */
    fun readingProgress(page: Int, pages: Int): String? =
        if (pages <= 0) null else LocalizationRegistry.format(
            Strings.statement_reading_page,
            listOf(page.coerceIn(0, pages).toString(), pages.toString()),
        )

    /**
     * What the import came to, one line per fact that is not zero — so "3 were
     * already recorded" never appears as "0 were already recorded".
     */
    fun summary(parsed: ParsedStatement, saved: SaveOutcome): List<String> = buildList {
        add(counted(parsed.rows.size, Strings.import_result_found_one, Strings.import_result_found_other))
        if (saved.duplicates > 0) {
            add(counted(saved.duplicates, Strings.import_result_duplicates_one, Strings.import_result_duplicates_other))
        }
        if (saved.needsReview > 0) {
            add(counted(saved.needsReview, Strings.import_result_review_one, Strings.import_result_review_other))
        }
        if (parsed.unparsedLineCount > 0) {
            add(counted(parsed.unparsedLineCount, Strings.import_result_unread_one, Strings.import_result_unread_other))
        }
    }

    /** The thank-you after the diagnostic offer, saying until when the text is kept. */
    fun diagnosticsThanks(retainedUntil: String?): String {
        val until = retainedUntil?.take(10)?.let(Dates::parse)
        return if (until != null) {
            LocalizationRegistry.format(Strings.import_diagnostics_kept, listOf(Dates.display(until)))
        } else {
            LocalizationRegistry.get(Strings.import_diagnostics_not_kept)
        }
    }

    private fun counted(count: Int, one: String, other: String): String =
        LocalizationRegistry.format(if (count == 1) one else other, listOf(count.toString()))
}
