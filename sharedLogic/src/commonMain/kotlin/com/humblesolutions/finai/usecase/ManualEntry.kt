package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.DuplicateMatch
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.ReviewReason
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A transaction typed in by hand, as far as the person has got (#30).
 *
 * **Account, date, amount and direction start unanswered** — `CLAUDE.md` → no
 * silent defaults. A date that silently said "today" would file last week's
 * coffee in the wrong period, and nobody would notice until a budget was off.
 * The screen offers a one-tap Today instead.
 *
 * Kept in UI state and saved across process death, so it holds only plain
 * values: ids, an ISO date, and the amount exactly as typed.
 */
data class ManualEntryDraft(
    val accountId: String? = null,
    val occurredOn: LocalDate? = null,
    // As typed — "1,200.50" — so what the person sees is never rewritten under
    // them. Normalised only when the request is built.
    val amount: String = "",
    val direction: TransactionDirection? = null,
    val description: String = "",
    // Optional, and null means "let the backend categorize", which the screen
    // says out loud rather than showing an empty category.
    val categoryId: String? = null,
)

/**
 * Why Save cannot go ahead — one reason, in form order, so the notice points at
 * the first field that needs attention.
 */
enum class ManualEntryBlock(val messageKey: String, val isUnanswered: Boolean) {
    NO_ACCOUNT(Strings.manual_entry_block_no_account, isUnanswered = true),
    NO_DATE(Strings.manual_entry_block_no_date, isUnanswered = true),
    FUTURE_DATE(Strings.manual_entry_block_future_date, isUnanswered = false),
    NO_AMOUNT(Strings.manual_entry_block_no_amount, isUnanswered = true),
    AMOUNT_NOT_MONEY(Strings.manual_entry_block_amount_invalid, isUnanswered = false),
    AMOUNT_ZERO(Strings.manual_entry_block_amount_zero, isUnanswered = false),
    NO_DIRECTION(Strings.manual_entry_block_no_direction, isUnanswered = true),
    NO_DESCRIPTION(Strings.manual_entry_block_no_description, isUnanswered = true),
    DESCRIPTION_TOO_LONG(Strings.manual_entry_block_description_too_long, isUnanswered = false),
}

/**
 * What happened to an entry the server accepted — the line shown after Save.
 *
 * "Saved" alone would be wrong for two of these: the backend may have put the
 * row in the review queue (#38), and a person who is not told will not look.
 */
enum class ManualEntrySaved(val messageKey: String) {
    /** Saved and filed; nothing waits on the person. */
    SAVED(Strings.manual_entry_saved),

    /** Saved with no category — no rule covered it, and no model was asked. */
    NEEDS_CATEGORY(Strings.manual_entry_saved_needs_category),

    /** Saved, but same day and amount as another row under a different name. */
    LOOKS_LIKE_A_DUPLICATE(Strings.manual_entry_saved_possible_duplicate),

    /** Waiting for review for a reason this build does not know. Never "all fine". */
    NEEDS_REVIEW(Strings.manual_entry_saved_needs_review),
}

/**
 * The rules for a transaction typed in by hand — **the one place they live**.
 *
 * The disabled Save button, the notice beneath it and the submit path all read
 * [blockingReason], directly or through [notice] and [request], so a greyed-out
 * button and a refusal from the server can never disagree about why (the same
 * pattern as [SetupWizard.blockingReason]).
 */
object ManualEntry {

    /** Matches the backend's limit on `description` (Finance-backend #38). */
    const val DESCRIPTION_MAX = 512

    /**
     * Why Save cannot go ahead, or null when it can.
     *
     * @param currency the chosen account's, which decides how many decimal
     *   places an amount may have. Null before an account is chosen — and then
     *   [ManualEntryBlock.NO_ACCOUNT] is the reason anyway.
     * @param today the device's local date. A parameter so the rule is testable
     *   without a clock; [today] gives the real one.
     */
    fun blockingReason(
        draft: ManualEntryDraft,
        currency: String?,
        today: LocalDate,
    ): ManualEntryBlock? {
        if (draft.accountId == null) return ManualEntryBlock.NO_ACCOUNT

        val date = draft.occurredOn ?: return ManualEntryBlock.NO_DATE
        // Local "today", which is never ahead of what the server accepts: its
        // limit is a day past UTC, and no timezone's today is more than a day
        // past UTC's. So the device refuses only what the server would too.
        if (date > today) return ManualEntryBlock.FUTURE_DATE

        if (draft.amount.isBlank()) return ManualEntryBlock.NO_AMOUNT
        val fractionDigits = currency?.let(Money::fractionDigits) ?: 2
        val amount = Money.normalize(draft.amount, fractionDigits)
            ?: return ManualEntryBlock.AMOUNT_NOT_MONEY
        // Zero is money but never a transaction: a typed "0" is a slip, and
        // letting it through writes a row that means nothing.
        if (!Money.isPositive(amount, fractionDigits)) return ManualEntryBlock.AMOUNT_ZERO

        if (draft.direction == null || draft.direction == TransactionDirection.UNKNOWN) {
            return ManualEntryBlock.NO_DIRECTION
        }

        val description = draft.description.trim()
        if (description.isEmpty()) return ManualEntryBlock.NO_DESCRIPTION
        // Kotlin counts UTF-16 units and the server counts code points, so an
        // emoji counts twice here. That can only make this stricter than the
        // server, never looser — it never lets through something refused there.
        if (description.length > DESCRIPTION_MAX) return ManualEntryBlock.DESCRIPTION_TOO_LONG

        return null
    }

    /**
     * What the notice under Save should say, or null.
     *
     * The same reason Save is disabled for, with one exception about timing: a
     * field simply not answered yet waits until the user has [touched] the
     * form, so the screen never opens by telling someone off for not having
     * started. Anything already wrong — a future date, "abc" for an amount — is
     * said straight away.
     */
    fun notice(
        draft: ManualEntryDraft,
        currency: String?,
        today: LocalDate,
        touched: Boolean,
    ): ManualEntryBlock? {
        val block = blockingReason(draft, currency, today) ?: return null
        return block.takeIf { touched || !it.isUnanswered }
    }

    /**
     * The request to send, or null while [blockingReason] says no.
     *
     * The submit path goes through here, so it cannot send what the button
     * would have refused.
     *
     * @param allowDuplicate true only as the answer to a duplicate warning —
     *   "keep it anyway".
     */
    fun request(
        draft: ManualEntryDraft,
        currency: String?,
        today: LocalDate,
        allowDuplicate: Boolean = false,
    ): NewTransaction? {
        if (blockingReason(draft, currency, today) != null) return null
        val fractionDigits = currency?.let(Money::fractionDigits) ?: 2
        return NewTransaction(
            accountId = draft.accountId ?: return null,
            occurredOn = (draft.occurredOn ?: return null).toString(),
            amount = Money.normalize(draft.amount, fractionDigits) ?: return null,
            direction = draft.direction ?: return null,
            description = draft.description.trim(),
            categoryId = draft.categoryId,
            allowDuplicate = allowDuplicate,
        )
    }

    /**
     * What the duplicate warning says: the amount, day and description of the
     * transaction this one matched, so the person can tell whether it really is
     * the same one. Anything missing from the server's answer falls back to a
     * plain "this looks like one you already have" rather than a half-filled
     * sentence.
     */
    fun duplicateMessage(match: DuplicateMatch?, currency: String, locale: String): String {
        val date = Dates.parse(match?.occurredOn)
        val amount = match?.let { Money.format(it.amount, currency, locale) }.orEmpty()
        val description = match?.description?.trim().orEmpty()
        if (date == null || amount.isEmpty() || description.isEmpty()) {
            return LocalizationRegistry.get(Strings.manual_entry_duplicate_body_unknown)
        }
        return LocalizationRegistry.format(
            Strings.manual_entry_duplicate_body,
            listOf(amount, Dates.display(date), description),
        )
    }

    /**
     * What to say after the server accepted [saved].
     *
     * A row carries one reason, and the backend keeps a possible duplicate's
     * reason over a missing category (#38), so that is the order here too: the
     * duplicate is the question the person should see first. A row waiting for
     * a reason this build does not know still says it is waiting.
     */
    fun savedAs(saved: Transaction): ManualEntrySaved {
        if (!saved.needsReview) return ManualEntrySaved.SAVED
        return when (saved.reviewReason) {
            ReviewReason.SUSPECTED_DUPLICATE -> ManualEntrySaved.LOOKS_LIKE_A_DUPLICATE
            ReviewReason.UNKNOWN_CATEGORY -> ManualEntrySaved.NEEDS_CATEGORY
            ReviewReason.LOW_CONFIDENCE, ReviewReason.UNKNOWN, null -> ManualEntrySaved.NEEDS_REVIEW
        }
    }

    /** The device's local date — what "Today" means to the person holding it. */
    @OptIn(ExperimentalTime::class)
    fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
