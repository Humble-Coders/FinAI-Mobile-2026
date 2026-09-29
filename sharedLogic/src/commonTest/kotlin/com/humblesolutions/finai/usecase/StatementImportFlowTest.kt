package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.model.ParsedRow
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.repository.StatementReadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of importing a statement (#31), with plain values — no screen, no
 * network. What each ending says and offers is decided here, once.
 */
class StatementImportFlowTest {

    private fun failureFor(error: Throwable): ImportFailure =
        assertNotNull(StatementImportFlow.problemFor(error), "$error").failure

    @Test
    fun everyServerRefusalTheTicketNamesHasItsOwnMessage() {
        // 403, 429, both 413s, 502, 503 and a network failure — each its own
        // words, never one "something went wrong" for all of them.
        val refusals = listOf(
            ApiException.FeatureUnavailable("document_upload", FeatureReason.UNKNOWN),
            ApiException.ImportQuotaExceeded(1, "2026-10-01T00:00:00+00:00"),
            ApiException.StatementTooLarge(),
            ApiException.TooManyTransactions(),
            ApiException.ParseFailed(),
            ApiException.ImportUnavailable(),
            ApiException.Network(RuntimeException("offline")),
        )

        val messages = refusals.map { StatementImportFlow.message(assertNotNull(StatementImportFlow.problemFor(it))) }

        assertEquals(messages.size, messages.toSet().size, messages.joinToString("\n"))
        assertTrue(messages.none { it == Strings.error_unexpected }, "a message key leaked through untranslated")
    }

    @Test
    fun consentAndAPasswordAreStepsNotFailures() {
        assertNull(StatementImportFlow.problemFor(ApiException.ConsentRequired("ai-v1")))
        assertNull(StatementImportFlow.problemFor(ApiException.AiPolicyChanged("ai-v2")))
        assertNull(StatementImportFlow.problemFor(StatementReadException.PasswordRequired()))
        assertTrue(StatementImportFlow.needsConsent(ApiException.ConsentRequired(null)))
        assertTrue(StatementImportFlow.needsConsent(ApiException.AiPolicyChanged(null)))
        assertFalse(StatementImportFlow.needsConsent(ApiException.ParseFailed()))
    }

    @Test
    fun theReadersAndTheDevicesOwnRefusalsAreNamed() {
        assertEquals(ImportFailure.UNSUPPORTED_FILE, failureFor(StatementReadException.Unsupported()))
        assertEquals(ImportFailure.NOTHING_READABLE, failureFor(StatementReadException.NothingReadable()))
        assertEquals(ImportFailure.TOO_MANY_PAGES, failureFor(StatementReadException.TooManyPages(600)))
        assertEquals(ImportFailure.TOO_MANY_PAGES, failureFor(StatementTooManyPages(600)))
        assertEquals(ImportFailure.TOO_LONG, failureFor(StatementTooLong(300_000)))
        assertEquals(ImportFailure.NOTHING_TO_SEND, failureFor(StatementHasNothingToSend(4)))
    }

    @Test
    fun aParseWithNoRowsIsAFailureAndOneWithRowsIsNot() {
        assertEquals(
            ImportFailure.NO_TRANSACTIONS_FOUND,
            StatementImportFlow.problemAfterParse(ParsedStatement(importId = "i-1"))?.failure,
        )
        assertNull(StatementImportFlow.problemAfterParse(ParsedStatement(rows = listOf(row()))))
    }

    @Test
    fun diagnosticsAreOfferedOnlyWhereTheServerReadAndFailed() {
        // Manager decision (2026-09-30): only after a failed import, which does
        // not use up the month — so sending the text again is allowed.
        val offered = ImportFailure.entries.filter { it.offersDiagnostics }.toSet()

        assertEquals(setOf(ImportFailure.NO_TRANSACTIONS_FOUND, ImportFailure.PARSE_FAILED), offered)
    }

    @Test
    fun whenNothingCanBeReadManualEntryIsOffered() {
        // Typing it in is the only other way in, by design (#30 → #31).
        for (failure in listOf(
            ImportFailure.UNSUPPORTED_FILE,
            ImportFailure.NOTHING_READABLE,
            ImportFailure.NOTHING_TO_SEND,
            ImportFailure.NO_TRANSACTIONS_FOUND,
            ImportFailure.UNAVAILABLE,
            ImportFailure.QUOTA_USED,
        )) {
            assertTrue(failure.offersManualEntry, "$failure")
        }
    }

    @Test
    fun aRetryIsOfferedOnlyWhereTryingAgainCanHelp() {
        assertTrue(ImportFailure.PARSE_FAILED.canRetry)
        assertTrue(ImportFailure.NETWORK.canRetry)
        assertFalse(ImportFailure.QUOTA_USED.canRetry)
        assertFalse(ImportFailure.TOO_LONG.canRetry)
        assertFalse(ImportFailure.UNAVAILABLE.canRetry)
    }

    @Test
    fun theQuotaMessageSaysWhenImportsComeBack() {
        val message = StatementImportFlow.message(
            ImportProblem(ImportFailure.QUOTA_USED, resetsAt = "2026-10-01T00:00:00+00:00"),
        )

        assertTrue(message.contains("Oct 1, 2026"), message)
        // Without a date it still says what happened, never a blank.
        val undated = StatementImportFlow.message(ImportProblem(ImportFailure.QUOTA_USED))
        assertEquals("You've used this month's statement imports.", undated)
    }

    @Test
    fun theRowsAreSavedAsParsedAgainstTheChosenAccount() {
        val parsed = ParsedStatement(importId = "i-1", rows = listOf(row(), row(amount = "134.02")))

        val body = StatementImportFlow.rowsToSave("acct-1", parsed)

        assertEquals("acct-1", body.accountId)
        assertEquals(listOf("12.40", "134.02"), body.rows.map { it.amount })
        assertEquals("2026-08-14", body.rows.first().occurredOn)
        assertEquals(96, body.rows.first().confidence)
    }

    @Test
    fun theSummarySaysOnlyWhatIsNotZeroAndCountsCorrectly() {
        val parsed = ParsedStatement(rows = List(12) { row() }, unparsedLineCount = 1)

        val lines = StatementImportFlow.summary(parsed, SaveOutcome(saved = 11, duplicates = 1, needsReview = 0))

        assertEquals(
            listOf(
                "12 transactions found",
                "1 was already recorded, so it wasn't added again",
                "1 line couldn't be read as a transaction",
            ),
            lines,
        )
    }

    @Test
    fun progressIsSaidOnlyOnceTheReaderKnowsHowManyPages() {
        assertNull(StatementImportFlow.readingProgress(0, 0))
        assertEquals("Reading page 3 of 12…", StatementImportFlow.readingProgress(3, 12))
    }

    @Test
    fun theDiagnosticThanksSaysUntilWhenOrThatNothingWasKept() {
        assertTrue(StatementImportFlow.diagnosticsThanks("2026-10-30").contains("Oct 30, 2026"))
        assertEquals(
            "Thank you. This time the statement read well enough that we didn't need to keep it.",
            StatementImportFlow.diagnosticsThanks(null),
        )
    }

    private fun row(amount: String = "12.40") = ParsedRow(
        occurredOn = "2026-08-14",
        description = "TIM HORTONS",
        amount = amount,
        direction = "debit",
        confidence = 96,
    )
}
