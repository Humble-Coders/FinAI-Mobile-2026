package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.model.ReviewReason
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * A row being corrected, as far as the person has got (#32).
 *
 * Starts as a copy of the row, because a correction is an edit of something
 * that exists — unlike a transaction typed in from nothing, where every
 * money-critical field starts unanswered. Only what actually changed is sent.
 */
data class CorrectionDraft(
    val occurredOn: LocalDate? = null,
    val amount: String = "",
    val direction: TransactionDirection? = null,
    val description: String = "",
    val categoryId: String? = null,
)

/** Why a correction cannot be saved — one reason, in form order. */
enum class CorrectionBlock(val messageKey: String) {
    NO_DATE(Strings.manual_entry_block_no_date),
    FUTURE_DATE(Strings.manual_entry_block_future_date),
    NO_AMOUNT(Strings.manual_entry_block_no_amount),
    AMOUNT_NOT_MONEY(Strings.manual_entry_block_amount_invalid),
    AMOUNT_ZERO(Strings.manual_entry_block_amount_zero),
    NO_DIRECTION(Strings.manual_entry_block_no_direction),
    NO_DESCRIPTION(Strings.manual_entry_block_no_description),
    DESCRIPTION_TOO_LONG(Strings.manual_entry_block_description_too_long),
    NOTHING_CHANGED(Strings.review_edit_nothing_changed),
}

/**
 * The rules of the review queue — **the one place they live**. Both apps read
 * these; the screens only show the answers (kmp-arch-v2).
 */
object ReviewQueue {

    /**
     * The rows "Confirm all" would actually clear: the ones that have a
     * category.
     *
     * A row with no category cannot leave the queue — the server keeps it,
     * asking for one, because an uncategorized row would silently miss every
     * budget (Finance-backend #48). Counting it here would promise something
     * the request cannot deliver.
     */
    fun confirmable(rows: List<Transaction>): List<Transaction> =
        rows.filter { it.needsReview && it.categoryId != null }

    /** What the Confirm-all button says, counting only what it can clear. */
    fun confirmAllLabel(rows: List<Transaction>): String {
        val count = confirmable(rows).size
        return when (count) {
            0 -> LocalizationRegistry.get(Strings.review_confirm_all_none)
            1 -> LocalizationRegistry.get(Strings.review_confirm_all_one)
            else -> LocalizationRegistry.format(Strings.review_confirm_all_other, listOf(count.toString()))
        }
    }

    /**
     * What to say after a bulk confirm — from what the server actually
     * cleared, not from what was sent. A row that stayed is a row still
     * asking for a category, and saying "18 confirmed" over it would be a
     * lie the next screen contradicts.
     */
    fun confirmedMessage(outcome: ConfirmOutcome, sent: Int): String {
        val left = outcome.confirmed
        val stayed = (sent - left).coerceAtLeast(0)
        if (stayed > 0) {
            return LocalizationRegistry.format(
                Strings.review_confirm_partial,
                listOf(left.toString(), stayed.toString()),
            )
        }
        return if (left == 1) {
            LocalizationRegistry.get(Strings.review_confirmed_one)
        } else {
            LocalizationRegistry.format(Strings.review_confirmed_other, listOf(left.toString()))
        }
    }

    /** Why this row is here, in a person's words. */
    fun reasonKey(row: Transaction): String = when (row.reviewReason) {
        ReviewReason.LOW_CONFIDENCE -> Strings.review_reason_low_confidence
        ReviewReason.UNKNOWN_CATEGORY -> Strings.review_reason_unknown_category
        ReviewReason.SUSPECTED_DUPLICATE -> Strings.review_reason_suspected_duplicate
        ReviewReason.UNKNOWN, null -> Strings.review_reason_other
    }

    /**
     * What a suspected duplicate matched, written out. Anything missing falls
     * back to saying so plainly rather than to a half-finished sentence — the
     * user is being asked "is this the same?", and a blank is not an answer.
     */
    fun duplicateOf(match: DuplicateMatch?, currency: String, locale: String): String {
        val date = Dates.parse(match?.occurredOn)
        val amount = match?.let { Money.format(it.amount, currency, locale) }.orEmpty()
        if (date == null || amount.isEmpty()) {
            return LocalizationRegistry.get(Strings.review_duplicate_of_plain)
        }
        return LocalizationRegistry.format(Strings.review_duplicate_of, listOf(amount, Dates.display(date)))
    }

    /**
     * What a category is called on screen.
     *
     * The shared taxonomy is translated here, keyed by slug, because the
     * server's names are English. A slug this build does not know falls back
     * to the server's name, which is honest and never blank. A household's own
     * category always shows the name the person typed — translating that would
     * be rewriting their words.
     */
    fun categoryName(category: Category): String {
        if (!category.isSystem) return category.name
        val key = "category_" + category.slug
        val translated = LocalizationRegistry.get(key)
        return if (translated == key) category.name else translated
    }

    /** The one for this id, or null when the row has none yet. */
    fun categoryName(categories: List<Category>, id: String?): String? =
        categories.firstOrNull { it.id == id }?.let(::categoryName)

    /** The draft a correction starts from: the row as it stands. */
    fun draftOf(row: Transaction): CorrectionDraft = CorrectionDraft(
        occurredOn = Dates.parse(row.occurredOn),
        amount = row.amount,
        direction = row.direction.takeIf { it != TransactionDirection.UNKNOWN },
        description = row.description.orEmpty(),
        categoryId = row.categoryId,
    )

    /**
     * Why the correction cannot be saved, or null. The disabled button and the
     * notice beneath it read this, as [ManualEntry.blockingReason] does for a
     * typed-in entry — and the same money rules apply, because it is the same
     * ledger.
     */
    fun blockingReason(row: Transaction, draft: CorrectionDraft, today: LocalDate): CorrectionBlock? {
        val date = draft.occurredOn ?: return CorrectionBlock.NO_DATE
        if (date > today) return CorrectionBlock.FUTURE_DATE

        if (draft.amount.isBlank()) return CorrectionBlock.NO_AMOUNT
        val digits = Money.fractionDigits(row.currency)
        val amount = Money.normalize(draft.amount, digits) ?: return CorrectionBlock.AMOUNT_NOT_MONEY
        if (!Money.isPositive(amount, digits)) return CorrectionBlock.AMOUNT_ZERO

        if (draft.direction == null || draft.direction == TransactionDirection.UNKNOWN) {
            return CorrectionBlock.NO_DIRECTION
        }

        val description = draft.description.trim()
        if (description.isEmpty()) return CorrectionBlock.NO_DESCRIPTION
        if (description.length > ManualEntry.DESCRIPTION_MAX) return CorrectionBlock.DESCRIPTION_TOO_LONG

        if (patch(row, draft).isEmpty) return CorrectionBlock.NOTHING_CHANGED
        return null
    }

    /**
     * Only what changed. A field the person did not touch is left out, so a
     * correction of one word cannot re-send — and re-validate — the rest of
     * the row.
     */
    fun patch(row: Transaction, draft: CorrectionDraft): TransactionPatch {
        val digits = Money.fractionDigits(row.currency)
        val amount = Money.normalize(draft.amount, digits)
        val description = draft.description.trim()
        return TransactionPatch(
            occurredOn = draft.occurredOn?.toString()?.takeIf { it != row.occurredOn },
            // Compared normalised against normalised: "12.5" and "12.50" are
            // the same amount, and sending it would be a change that is not one.
            amount = amount?.takeIf { it != Money.normalize(row.amount, digits) },
            direction = draft.direction?.takeIf { it != row.direction },
            description = description.takeIf { it.isNotEmpty() && it != row.description.orEmpty() },
            categoryId = draft.categoryId?.takeIf { it != row.categoryId },
        )
    }

    /** The request to send, or null while [blockingReason] says no. */
    fun correction(row: Transaction, draft: CorrectionDraft, today: LocalDate): TransactionPatch? {
        if (blockingReason(row, draft, today) != null) return null
        return patch(row, draft)
    }

    /**
     * What a correction did beyond this row: the rule it taught, and the other
     * rows it moved. Never silent — these are changes to someone's financial
     * records that they did not make one by one.
     */
    fun aftermath(outcome: PatchOutcome, merchant: String?): List<String> = buildList {
        if (outcome.ruleRecorded && !merchant.isNullOrBlank()) {
            add(LocalizationRegistry.format(Strings.review_rule_recorded, listOf(merchant)))
        }
        when {
            outcome.recategorized == 1 -> add(LocalizationRegistry.get(Strings.review_also_applied_one))
            outcome.recategorized > 1 ->
                add(
                    LocalizationRegistry.format(
                        Strings.review_also_applied_other,
                        listOf(outcome.recategorized.toString()),
                    ),
                )
        }
        if (outcome.importFinished) add(LocalizationRegistry.get(Strings.review_import_done))
    }

    /**
     * Whether the queue must be re-read after a correction.
     *
     * Only when the server says it moved other rows: it returns a count, not
     * which ones, so there is no honest way to patch them in place — and the
     * common correction moves nothing, so it costs nothing (manager decision,
     * 2026-10-01). The corrected row itself always comes back in the response
     * and needs no reload.
     */
    fun mustReload(outcome: PatchOutcome): Boolean = outcome.recategorized > 0
}
