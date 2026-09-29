package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.ReviewReason
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules for a hand-typed transaction, built with plain constructors — no
 * view model, no clock, no network. Every reason Save can be disabled for has
 * a test here, and the submit path is held to the same function.
 */
class ManualEntryTest {

    private val today = LocalDate(2026, 9, 29)

    private val complete = ManualEntryDraft(
        accountId = "acct-1",
        occurredOn = LocalDate(2026, 9, 28),
        amount = "12.50",
        direction = TransactionDirection.DEBIT,
        description = "Tim Hortons",
    )

    private fun reason(draft: ManualEntryDraft, currency: String? = "CAD") =
        ManualEntry.blockingReason(draft, currency, today)

    @Test
    fun aCompleteEntryIsNotBlocked() {
        assertNull(reason(complete))
    }

    @Test
    fun anEmptyFormStartsWithNothingAnswered() {
        // No silent defaults: a fresh form asks for the account first, and
        // nothing — least of all the date — has been filled in for the user.
        val fresh = ManualEntryDraft()
        assertNull(fresh.accountId)
        assertNull(fresh.occurredOn)
        assertNull(fresh.direction)
        assertEquals(ManualEntryBlock.NO_ACCOUNT, reason(fresh))
    }

    @Test
    fun reasonsComeInFormOrder() {
        assertEquals(ManualEntryBlock.NO_ACCOUNT, reason(complete.copy(accountId = null)))
        assertEquals(ManualEntryBlock.NO_DATE, reason(complete.copy(occurredOn = null)))
        assertEquals(ManualEntryBlock.NO_AMOUNT, reason(complete.copy(amount = " ")))
        assertEquals(ManualEntryBlock.NO_DIRECTION, reason(complete.copy(direction = null)))
        assertEquals(ManualEntryBlock.NO_DESCRIPTION, reason(complete.copy(description = "  ")))
        // Two things wrong: the earlier field is named.
        assertEquals(
            ManualEntryBlock.NO_DATE,
            reason(complete.copy(occurredOn = null, description = "")),
        )
    }

    @Test
    fun aFutureDateIsRefusedAndTodayIsNot() {
        assertEquals(
            ManualEntryBlock.FUTURE_DATE,
            reason(complete.copy(occurredOn = LocalDate(2026, 9, 30))),
        )
        assertNull(reason(complete.copy(occurredOn = today)))
    }

    @Test
    fun anAmountThatIsNotMoneyIsRefused() {
        assertEquals(ManualEntryBlock.AMOUNT_NOT_MONEY, reason(complete.copy(amount = "abc")))
        // Too many decimal places for dollars.
        assertEquals(ManualEntryBlock.AMOUNT_NOT_MONEY, reason(complete.copy(amount = "12.505")))
    }

    @Test
    fun aNegativeAmountIsRefused() {
        // Direction carries the sign; a minus would make the figure ambiguous.
        assertEquals(ManualEntryBlock.AMOUNT_NOT_MONEY, reason(complete.copy(amount = "-5.00")))
    }

    @Test
    fun zeroIsRefused() {
        assertEquals(ManualEntryBlock.AMOUNT_ZERO, reason(complete.copy(amount = "0")))
        assertEquals(ManualEntryBlock.AMOUNT_ZERO, reason(complete.copy(amount = "0.00")))
    }

    @Test
    fun decimalPlacesFollowTheAccountCurrency() {
        // Yen has no minor unit, so "12.5" is not an amount in yen.
        assertEquals(
            ManualEntryBlock.AMOUNT_NOT_MONEY,
            reason(complete.copy(amount = "12.5"), currency = "JPY"),
        )
        assertNull(reason(complete.copy(amount = "1250"), currency = "JPY"))
    }

    @Test
    fun anUnknownDirectionIsNotAnAnswer() {
        assertEquals(
            ManualEntryBlock.NO_DIRECTION,
            reason(complete.copy(direction = TransactionDirection.UNKNOWN)),
        )
    }

    @Test
    fun theDescriptionLimitMatchesTheServer() {
        assertNull(reason(complete.copy(description = "a".repeat(ManualEntry.DESCRIPTION_MAX))))
        assertEquals(
            ManualEntryBlock.DESCRIPTION_TOO_LONG,
            reason(complete.copy(description = "a".repeat(ManualEntry.DESCRIPTION_MAX + 1))),
        )
    }

    @Test
    fun anUnansweredFieldWaitsUntilTheFormIsTouched() {
        val fresh = ManualEntryDraft()
        assertNull(ManualEntry.notice(fresh, null, today, touched = false))
        assertEquals(
            ManualEntryBlock.NO_ACCOUNT,
            ManualEntry.notice(fresh, null, today, touched = true),
        )
    }

    @Test
    fun aWrongAnswerIsSaidStraightAway() {
        val future = complete.copy(occurredOn = LocalDate(2026, 10, 1))
        assertEquals(
            ManualEntryBlock.FUTURE_DATE,
            ManualEntry.notice(future, "CAD", today, touched = false),
        )
    }

    @Test
    fun theNoticeAndTheButtonAgree() {
        // Whenever the notice names a reason, it is the reason Save is disabled
        // for — the two can never point at different fields.
        val drafts = listOf(
            ManualEntryDraft(),
            complete,
            complete.copy(amount = "abc"),
            complete.copy(occurredOn = LocalDate(2027, 1, 1)),
            complete.copy(direction = null),
        )
        for (draft in drafts) {
            val notice = ManualEntry.notice(draft, "CAD", today, touched = true)
            assertEquals(reason(draft), notice)
        }
    }

    @Test
    fun nothingIsSentWhileSaveIsBlocked() {
        assertNull(ManualEntry.request(complete.copy(amount = "abc"), "CAD", today))
        assertNull(ManualEntry.request(ManualEntryDraft(), null, today))
    }

    @Test
    fun theRequestCarriesTheNormalisedAmountAndTrimmedDescription() {
        val entry = assertNotNull(
            ManualEntry.request(
                complete.copy(amount = "1,200.5", description = "  Rent  "),
                "CAD",
                today,
            ),
        )
        assertEquals("1200.50", entry.amount)
        assertEquals("Rent", entry.description)
        assertEquals("2026-09-28", entry.occurredOn)
        assertEquals(TransactionDirection.DEBIT, entry.direction)
    }

    @Test
    fun noCategoryMeansTheBackendChooses() {
        val entry = assertNotNull(ManualEntry.request(complete, "CAD", today))
        assertNull(entry.categoryId)
        val chosen = assertNotNull(
            ManualEntry.request(complete.copy(categoryId = "cat-1"), "CAD", today),
        )
        assertEquals("cat-1", chosen.categoryId)
    }

    @Test
    fun keepingADuplicateIsOnlyEverAskedFor() {
        assertFalse(assertNotNull(ManualEntry.request(complete, "CAD", today)).allowDuplicate)
        assertTrue(
            assertNotNull(
                ManualEntry.request(complete, "CAD", today, allowDuplicate = true),
            ).allowDuplicate,
        )
    }

    @Test
    fun theDuplicateWarningNamesWhatItMatched() {
        val match = DuplicateMatch(id = "t-1", occurredOn = "2026-09-12", amount = "1200.5", description = "Rent")

        assertEquals(
            "You already have $1,200.50 on Sep 12, 2026: \"Rent\". Is this a second one?",
            ManualEntry.duplicateMessage(match, "CAD", "en-CA"),
        )
    }

    @Test
    fun aDuplicateWithNothingToShowSaysSoPlainly() {
        val plain = "This looks like a transaction you already have."

        assertEquals(plain, ManualEntry.duplicateMessage(null, "CAD", "en-CA"))
        assertEquals(
            plain,
            ManualEntry.duplicateMessage(DuplicateMatch(occurredOn = "2026-09-12", amount = "5"), "CAD", "en-CA"),
        )
        assertEquals(
            plain,
            ManualEntry.duplicateMessage(
                DuplicateMatch(occurredOn = "not a date", amount = "5", description = "Rent"),
                "CAD",
                "en-CA",
            ),
        )
    }

    @Test
    fun aFiledEntrySaysSaved() {
        val filed = Transaction(id = "t-1", categoryId = "cat-1", needsReview = false)

        assertEquals(ManualEntrySaved.SAVED, ManualEntry.savedAs(filed))
    }

    @Test
    fun anUncategorizedEntrySaysItNeedsACategory() {
        val waiting = Transaction(needsReview = true, reviewReason = ReviewReason.UNKNOWN_CATEGORY)

        assertEquals(ManualEntrySaved.NEEDS_CATEGORY, ManualEntry.savedAs(waiting))
    }

    @Test
    fun aPossibleDuplicateSaysSo() {
        val flagged = Transaction(needsReview = true, reviewReason = ReviewReason.SUSPECTED_DUPLICATE)

        assertEquals(ManualEntrySaved.LOOKS_LIKE_A_DUPLICATE, ManualEntry.savedAs(flagged))
    }

    @Test
    fun aRowWaitingForAnUnknownReasonNeverReadsAsAllFine() {
        for (reason in listOf(ReviewReason.UNKNOWN, ReviewReason.LOW_CONFIDENCE, null)) {
            val waiting = Transaction(needsReview = true, reviewReason = reason)
            assertEquals(ManualEntrySaved.NEEDS_REVIEW, ManualEntry.savedAs(waiting), "$reason")
        }
    }

    @Test
    fun everyOutcomeHasItsOwnMessage() {
        val keys = ManualEntrySaved.entries.map { it.messageKey }

        assertEquals(keys.size, keys.toSet().size)
    }
}
