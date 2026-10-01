package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.PatchOutcome
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
 * The rules of the review queue (#32), with plain values — no screen, no
 * network. What can be confirmed, what a correction sends, and what the
 * screen says afterwards are all decided here, once.
 */
class ReviewQueueTest {

    private val today = LocalDate(2026, 10, 1)

    private fun row(
        id: String = "t-1",
        categoryId: String? = "cat-1",
        amount: String = "12.40",
        day: String = "2026-09-28",
        description: String? = "TIM HORTONS",
        reason: ReviewReason? = ReviewReason.LOW_CONFIDENCE,
        needsReview: Boolean = true,
        duplicateOf: DuplicateMatch? = null,
    ) = Transaction(
        id = id,
        occurredOn = day,
        amount = amount,
        currency = "CAD",
        direction = TransactionDirection.DEBIT,
        description = description,
        merchant = "Tim Hortons",
        categoryId = categoryId,
        needsReview = needsReview,
        reviewReason = reason,
        duplicateOf = duplicateOf,
    )

    // ── Confirm all ─────────────────────────────────────────────────────

    @Test
    fun confirmAllCountsOnlyTheRowsItCanActuallyClear() {
        // A row with no category cannot leave the queue (Finance-backend #48),
        // so promising to confirm it would be a promise the request breaks.
        val rows = listOf(row("a"), row("b", categoryId = null), row("c"))

        assertEquals(listOf("a", "c"), ReviewQueue.confirmable(rows).map { it.id })
        assertEquals("Confirm 2 transactions", ReviewQueue.confirmAllLabel(rows))
    }

    @Test
    fun aRowAlreadyAnsweredIsNotCountedAgain() {
        val rows = listOf(row("a"), row("b", needsReview = false))

        assertEquals(listOf("a"), ReviewQueue.confirmable(rows).map { it.id })
        assertEquals("Confirm 1 transaction", ReviewQueue.confirmAllLabel(rows))
    }

    @Test
    fun withNothingConfirmableTheButtonAsksForACategoryInstead() {
        val rows = listOf(row("a", categoryId = null), row("b", categoryId = null))

        assertEquals(emptyList(), ReviewQueue.confirmable(rows))
        assertEquals("Choose a category to confirm these", ReviewQueue.confirmAllLabel(rows))
    }

    @Test
    fun whatIsSaidAfterwardsComesFromWhatTheServerCleared() {
        // Not from what was sent: a row that stayed is still asking for a
        // category, and "18 confirmed" over it is a lie the next screen undoes.
        assertEquals(
            "16 confirmed. 2 still need a category.",
            ReviewQueue.confirmedMessage(ConfirmOutcome(confirmed = 16), sent = 18),
        )
        assertEquals(
            "18 transactions confirmed",
            ReviewQueue.confirmedMessage(ConfirmOutcome(confirmed = 18), sent = 18),
        )
        assertEquals(
            "1 transaction confirmed",
            ReviewQueue.confirmedMessage(ConfirmOutcome(confirmed = 1), sent = 1),
        )
    }

    // ── Why a row is here ───────────────────────────────────────────────

    @Test
    fun everyReasonReadsDifferently() {
        val keys = listOf(
            ReviewReason.LOW_CONFIDENCE,
            ReviewReason.UNKNOWN_CATEGORY,
            ReviewReason.SUSPECTED_DUPLICATE,
            ReviewReason.UNKNOWN,
            null,
        ).map { ReviewQueue.reasonKey(row(reason = it)) }

        // The four known reasons differ; an unknown reason shares the catch-all
        // with none at all, and never reads as "nothing to check".
        assertEquals(4, keys.toSet().size)
        assertEquals(keys[3], keys[4])
    }

    @Test
    fun aSuspectedDuplicateSaysWhatItMatched() {
        val match = DuplicateMatch(id = "t-9", occurredOn = "2026-09-28", amount = "12.40")

        assertEquals("Already recorded: $12.40 on Sep 28, 2026", ReviewQueue.duplicateOf(match, "CAD", "en-CA"))
        // Nothing to show is said plainly, never as a half-finished sentence.
        assertEquals("Already recorded, but we can't show which", ReviewQueue.duplicateOf(null, "CAD", "en-CA"))
    }

    // ── Category names ──────────────────────────────────────────────────

    @Test
    fun theSharedTaxonomyIsTranslatedAndTheHouseholdsOwnIsNot() {
        val shared = Category(id = "c1", slug = "dining", name = "Dining out", isSystem = true)
        val mine = Category(id = "c2", slug = "side_business", name = "side business", isSystem = false)
        // A slug this build does not know falls back to the server's words.
        val future = Category(id = "c3", slug = "crypto_staking", name = "Crypto staking", isSystem = true)

        assertEquals("Dining out", ReviewQueue.categoryName(shared))
        assertEquals("side business", ReviewQueue.categoryName(mine))
        assertEquals("Crypto staking", ReviewQueue.categoryName(future))
    }

    @Test
    fun aRowWithNoCategoryHasNoName() {
        val categories = listOf(Category(id = "c1", slug = "dining", name = "Dining out"))

        assertEquals("Dining out", ReviewQueue.categoryName(categories, "c1"))
        assertNull(ReviewQueue.categoryName(categories, null))
        assertNull(ReviewQueue.categoryName(categories, "gone"))
    }

    // ── Correcting a row ────────────────────────────────────────────────

    @Test
    fun aCorrectionStartsFromTheRowAsItStands() {
        val draft = ReviewQueue.draftOf(row())

        assertEquals(LocalDate(2026, 9, 28), draft.occurredOn)
        assertEquals("12.40", draft.amount)
        assertEquals(TransactionDirection.DEBIT, draft.direction)
        assertEquals("TIM HORTONS", draft.description)
        assertEquals("cat-1", draft.categoryId)
    }

    @Test
    fun onlyWhatChangedIsSent() {
        val row = row()
        val draft = ReviewQueue.draftOf(row).copy(description = "Tim Hortons Oakville")

        val patch = assertNotNull(ReviewQueue.correction(row, draft, today))

        assertEquals("Tim Hortons Oakville", patch.description)
        assertNull(patch.amount)
        assertNull(patch.occurredOn)
        assertNull(patch.direction)
        assertNull(patch.categoryId)
    }

    @Test
    fun theSameAmountWrittenDifferentlyIsNotAChange() {
        // "12.4" and "12.40" are one amount. Sending it would be a change the
        // person did not make, and would teach nothing.
        val row = row(amount = "12.40")

        val patch = ReviewQueue.patch(row, ReviewQueue.draftOf(row).copy(amount = "12.4"))

        assertNull(patch.amount)
        assertTrue(patch.isEmpty)
    }

    @Test
    fun anAmountIsAlwaysADecimalStringAndNeverRounded() {
        val row = row(amount = "12.40")

        val patch = assertNotNull(
            ReviewQueue.correction(row, ReviewQueue.draftOf(row).copy(amount = "1,234.5"), today),
        )

        assertEquals("1234.50", patch.amount)
        // Excess precision is refused rather than quietly rounded away.
        assertEquals(
            CorrectionBlock.AMOUNT_NOT_MONEY,
            ReviewQueue.blockingReason(row, ReviewQueue.draftOf(row).copy(amount = "12.405"), today),
        )
    }

    @Test
    fun theSameMoneyRulesApplyAsATypedInEntry() {
        val row = row()
        fun block(draft: CorrectionDraft) = ReviewQueue.blockingReason(row, draft, today)
        val base = ReviewQueue.draftOf(row)

        assertEquals(CorrectionBlock.FUTURE_DATE, block(base.copy(occurredOn = LocalDate(2026, 10, 2))))
        assertEquals(CorrectionBlock.NO_AMOUNT, block(base.copy(amount = "  ")))
        assertEquals(CorrectionBlock.AMOUNT_ZERO, block(base.copy(amount = "0")))
        assertEquals(CorrectionBlock.AMOUNT_NOT_MONEY, block(base.copy(amount = "abc")))
        assertEquals(CorrectionBlock.NO_DIRECTION, block(base.copy(direction = TransactionDirection.UNKNOWN)))
        assertEquals(CorrectionBlock.NO_DESCRIPTION, block(base.copy(description = "   ")))
        assertEquals(
            CorrectionBlock.DESCRIPTION_TOO_LONG,
            block(base.copy(description = "x".repeat(ManualEntry.DESCRIPTION_MAX + 1))),
        )
    }

    @Test
    fun anEmptyCorrectionIsRefusedRatherThanSent() {
        val row = row()

        assertEquals(CorrectionBlock.NOTHING_CHANGED, ReviewQueue.blockingReason(row, ReviewQueue.draftOf(row), today))
        assertNull(ReviewQueue.correction(row, ReviewQueue.draftOf(row), today))
    }

    @Test
    fun aRowWithNoCategoryCanBeGivenOne() {
        val row = row(categoryId = null)

        val patch = assertNotNull(
            ReviewQueue.correction(row, ReviewQueue.draftOf(row).copy(categoryId = "cat-2"), today),
        )

        assertEquals("cat-2", patch.categoryId)
    }

    // ── What a correction did beyond this row ───────────────────────────

    @Test
    fun movingOtherRowsIsSaidOutLoud() {
        val outcome = PatchOutcome(ruleRecorded = true, recategorized = 3)

        assertEquals(
            listOf("We'll file Tim Hortons this way from now on.", "Also applied to 3 other transactions waiting here."),
            ReviewQueue.aftermath(outcome, "Tim Hortons"),
        )
    }

    @Test
    fun oneOtherRowReadsAsOne() {
        assertEquals(
            listOf("Also applied to 1 other transaction waiting here."),
            ReviewQueue.aftermath(PatchOutcome(recategorized = 1), merchant = null),
        )
    }

    @Test
    fun aCorrectionThatChangedNothingElseSaysNothingElse() {
        assertEquals(emptyList(), ReviewQueue.aftermath(PatchOutcome(), merchant = "Tim Hortons"))
        // A rule with no merchant to name is not announced either.
        assertEquals(emptyList(), ReviewQueue.aftermath(PatchOutcome(ruleRecorded = true), merchant = null))
    }

    @Test
    fun finishingTheImportIsWorthSaying() {
        assertEquals(
            listOf("Statement finished — everything from it has been looked at."),
            ReviewQueue.aftermath(PatchOutcome(importFinished = true), merchant = null),
        )
    }

    @Test
    fun theQueueIsRereadOnlyWhenOtherRowsMoved() {
        // The server returns a count, not which rows — so a reload is the only
        // honest answer, and the common correction moves nothing and costs none.
        assertTrue(ReviewQueue.mustReload(PatchOutcome(recategorized = 1)))
        assertFalse(ReviewQueue.mustReload(PatchOutcome(recategorized = 0, ruleRecorded = true)))
    }
}
