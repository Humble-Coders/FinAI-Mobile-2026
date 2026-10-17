package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.BalanceSource
import com.humblesolutions.finai.model.DebtChanges
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.MoneyInput
import com.humblesolutions.finai.util.Percent
import kotlinx.datetime.LocalDate

/**
 * A debt's details as the person is editing them. The balance is not here:
 * it changes through "Update balance" ([BalanceDraft]), which the server
 * dates and marks as typed by hand.
 *
 * Blank or null is "none" for the fields that may be empty.
 */
data class DebtDraft(
    val name: String = "",
    val kind: DebtKind? = null,
    val minimumPayment: String = "",
    val interestRatePercent: String = "",
    /** A balance transfer's rate; blank for none. Both promotion fields or neither. */
    val promoRatePercent: String = "",
    /** `YYYY-MM-DD`, or null for none. */
    val promoEndsOn: String? = null,
    /** The household's card or loan account, or null for none. */
    val accountId: String? = null,
)

/** Why a debt's details cannot be saved yet. */
enum class DebtBlock(val messageKey: String) {
    NO_NAME(Strings.debts_block_no_name),
    NAME_TOO_LONG(Strings.debts_block_name_too_long),
    MINIMUM_NOT_MONEY(Strings.debts_block_minimum_invalid),
    MINIMUM_NEGATIVE(Strings.debts_block_minimum_negative),
    MINIMUM_TOO_PRECISE(Strings.debts_block_too_precise),
    RATE_INVALID(Strings.debts_block_rate_invalid),
    PROMO_RATE_INVALID(Strings.debts_block_promo_rate_invalid),
    PROMO_INCOMPLETE(Strings.debts_block_promo_incomplete),
    NOTHING_CHANGED(Strings.debts_block_unchanged),
}

/** Why a new balance cannot be saved yet. */
enum class BalanceBlock(val messageKey: String) {
    NO_BALANCE(Strings.debts_balance_block_none),
    NOT_MONEY(Strings.debts_balance_block_invalid),
    NEGATIVE(Strings.debts_balance_block_negative),
    TOO_PRECISE(Strings.debts_block_too_precise),

    /** The server keeps the date of a balance sent unchanged, so this would not refresh it. */
    UNCHANGED(Strings.debts_balance_block_unchanged),
}

/**
 * Editing debts, and the words a debt is shown with (backend #76).
 *
 * **Nothing here computes money.** A debt's balance, payoff month and
 * interest are the server's; what lives here is whether a draft may be sent,
 * exactly what an edit changes, and every sentence, so Android and iOS cannot
 * word the same debt differently (CLAUDE.md → Who computes what).
 */
object DebtEdit {

    /** The server's limit on a name (`NAME_MAX`), after trimming. */
    const val NAME_LIMIT = 255

    /** Accounts a debt can be linked to: what the person owes on, never money they have. */
    private val LINKABLE = setOf(AccountKind.CREDIT_CARD, AccountKind.LOAN)

    fun draftOf(debt: DebtRecord): DebtDraft = DebtDraft(
        name = debt.name,
        kind = debt.kind.takeIf { it != DebtKind.UNKNOWN },
        minimumPayment = debt.minimumPayment.orEmpty(),
        interestRatePercent = debt.interestRatePercent.orEmpty(),
        promoRatePercent = debt.promoRatePercent.orEmpty(),
        promoEndsOn = debt.promoEndsOn,
        accountId = debt.accountId,
    )

    /** The accounts [debt] may be linked to: the household's cards and loans. */
    fun linkable(accounts: List<Account>): List<Account> = accounts.filter { it.kind in LINKABLE }

    /**
     * Why [draft] cannot replace [original]'s details, or null when it can —
     * the one function behind the disabled Save, the notice and the refusal.
     */
    fun blockingReason(original: DebtRecord, draft: DebtDraft, currency: String): DebtBlock? {
        val name = draft.name.trim()
        if (name.isEmpty()) return DebtBlock.NO_NAME
        if (name.length > NAME_LIMIT) return DebtBlock.NAME_TOO_LONG
        if (draft.minimumPayment.isNotBlank()) {
            when (MoneyInput.problem(draft.minimumPayment, currency)) {
                MoneyInput.Problem.NEGATIVE -> return DebtBlock.MINIMUM_NEGATIVE
                MoneyInput.Problem.NOT_MONEY -> return DebtBlock.MINIMUM_NOT_MONEY
                MoneyInput.Problem.TOO_PRECISE -> return DebtBlock.MINIMUM_TOO_PRECISE
                MoneyInput.Problem.BLANK, null -> Unit
            }
        }
        if (draft.interestRatePercent.isNotBlank() && Percent.problem(draft.interestRatePercent) != null) return DebtBlock.RATE_INVALID
        if (draft.promoRatePercent.isNotBlank() && Percent.problem(draft.promoRatePercent) != null) return DebtBlock.PROMO_RATE_INVALID
        if (draft.promoRatePercent.isBlank() != (draft.promoEndsOn == null)) return DebtBlock.PROMO_INCOMPLETE
        if (changes(original, draft, currency).isEmpty) return DebtBlock.NOTHING_CHANGED
        return null
    }

    /**
     * Exactly what [draft] changes about [original]. Amounts compare at the
     * currency's scale and rates as figures, so `"20.990"`… is not a change
     * from `"20.99"`; an emptied field becomes a `clear…` flag.
     */
    fun changes(original: DebtRecord, draft: DebtDraft, currency: String): DebtChanges {
        val digits = Money.fractionDigits(currency)
        fun money(raw: String): String? = raw.takeIf { it.isNotBlank() }?.let { Money.normalize(it, digits) ?: it }

        val name = draft.name.trim().takeIf { it != original.name }
        val kindWas = original.kind.takeIf { it != DebtKind.UNKNOWN } ?: DebtKind.OTHER
        val kind = draft.kind?.takeIf { it != DebtKind.UNKNOWN && it != kindWas }

        val minimumNow = money(draft.minimumPayment)
        val minimumWas = original.minimumPayment?.let(::money)
        val rateNow = draft.interestRatePercent.takeIf { it.isNotBlank() }?.let { Percent.normalize(it) ?: it }

        val promoRateNow = draft.promoRatePercent.takeIf { it.isNotBlank() }?.let { Percent.normalize(it) ?: it }
        val promoWas = original.promoRatePercent != null || original.promoEndsOn != null
        val promoNow = promoRateNow != null && draft.promoEndsOn != null
        val promoChanged = promoNow &&
            (!Percent.same(promoRateNow, original.promoRatePercent) || draft.promoEndsOn != original.promoEndsOn)

        return DebtChanges(
            name = name,
            kind = kind,
            minimumPayment = minimumNow.takeIf { it != null && it != minimumWas },
            clearMinimumPayment = original.minimumPayment != null && minimumNow == null,
            interestRatePercent = rateNow.takeIf { it != null && !Percent.same(it, original.interestRatePercent) },
            clearInterestRate = original.interestRatePercent != null && rateNow == null,
            promoRatePercent = promoRateNow.takeIf { promoChanged },
            promoEndsOn = draft.promoEndsOn.takeIf { promoChanged },
            clearPromotion = promoWas && promoRateNow == null && draft.promoEndsOn == null,
            accountId = draft.accountId.takeIf { it != null && it != original.accountId },
            clearAccount = original.accountId != null && draft.accountId == null,
        )
    }

    // ── Update balance ──────────────────────────────────────────────────

    /** Why [amount] cannot become [debt]'s balance yet. Zero is a balance: a debt paid off. */
    fun blockingReasonForBalance(debt: DebtRecord, amount: String, currency: String): BalanceBlock? {
        when (MoneyInput.problem(amount, currency)) {
            MoneyInput.Problem.BLANK -> return BalanceBlock.NO_BALANCE
            MoneyInput.Problem.NEGATIVE -> return BalanceBlock.NEGATIVE
            MoneyInput.Problem.NOT_MONEY -> return BalanceBlock.NOT_MONEY
            MoneyInput.Problem.TOO_PRECISE -> return BalanceBlock.TOO_PRECISE
            null -> Unit
        }
        val digits = Money.fractionDigits(currency)
        if (Money.normalize(amount, digits) == Money.normalize(debt.balance, digits)) return BalanceBlock.UNCHANGED
        return null
    }

    /** The change "Update balance" sends. Call after [blockingReasonForBalance] said yes. */
    fun balanceChange(amount: String, currency: String): DebtChanges = DebtChanges(balance = Money.normalize(amount, Money.fractionDigits(currency)) ?: amount)

    // ── Words ───────────────────────────────────────────────────────────

    fun blockText(block: DebtBlock, currency: String, locale: String = "en"): String = when (block) {
        DebtBlock.NAME_TOO_LONG -> LocalizationRegistry.format(block.messageKey, listOf(NAME_LIMIT.toString()), locale)
        DebtBlock.MINIMUM_TOO_PRECISE -> LocalizationRegistry.format(block.messageKey, listOf(currency), locale)
        else -> LocalizationRegistry.get(block.messageKey, locale)
    }

    fun balanceBlockText(block: BalanceBlock, currency: String, locale: String = "en"): String = when (block) {
        BalanceBlock.TOO_PRECISE -> LocalizationRegistry.format(block.messageKey, listOf(currency), locale)
        else -> LocalizationRegistry.get(block.messageKey, locale)
    }

    fun kindLabel(kind: DebtKind, locale: String = "en"): String = LocalizationRegistry.get(
        when (kind) {
            DebtKind.CREDIT_CARD -> Strings.debts_kind_credit_card
            DebtKind.LINE_OF_CREDIT -> Strings.debts_kind_line_of_credit
            DebtKind.CAR_LOAN -> Strings.debts_kind_car_loan
            DebtKind.BALANCE_TRANSFER -> Strings.debts_kind_balance_transfer
            DebtKind.STUDENT_LOAN -> Strings.debts_kind_student_loan
            DebtKind.MORTGAGE -> Strings.debts_kind_mortgage
            DebtKind.OTHER, DebtKind.UNKNOWN -> Strings.debts_kind_other
        },
        locale,
    )

    /** The picture for a debt, from the category art both apps already draw. */
    fun iconOf(kind: DebtKind): CategoryIcon = when (kind) {
        DebtKind.CREDIT_CARD, DebtKind.BALANCE_TRANSFER -> CategoryIcon.CARD
        DebtKind.CAR_LOAN -> CategoryIcon.CAR
        DebtKind.MORTGAGE -> CategoryIcon.HOME
        DebtKind.STUDENT_LOAN -> CategoryIcon.SCHOOL
        DebtKind.LINE_OF_CREDIT -> CategoryIcon.DOCUMENT
        DebtKind.OTHER, DebtKind.UNKNOWN -> CategoryIcon.TAG
    }

    /**
     * "Balance from a statement, 31 Aug 2026" — where the figure came from and
     * when it was true, so a person can judge whether to trust it.
     */
    fun sourceLine(debt: DebtRecord, locale: String = "en"): String {
        val key = when (debt.balanceSource) {
            BalanceSource.STATEMENT -> Strings.debts_source_statement
            BalanceSource.MANUAL -> Strings.debts_source_manual
            BalanceSource.SETUP -> Strings.debts_source_setup
            BalanceSource.UNKNOWN -> Strings.debts_source_unknown
        }
        val date = Dates.parse(debt.balanceAsOf)?.let(Dates::display) ?: return LocalizationRegistry.get(Strings.debts_source_undated, locale)
        return LocalizationRegistry.format(key, listOf(date), locale)
    }

    /** The gentle reminder beside a balance the server judges stale; null otherwise. */
    fun staleLine(debt: DebtRecord, locale: String = "en"): String? = if (debt.balanceStale) LocalizationRegistry.get(Strings.debts_stale, locale) else null

    /** "0% until 1 May 2027, then 22.99%"; null without a promotion. */
    fun promotionLine(debt: DebtRecord, locale: String = "en"): String? {
        val promo = debt.promoRatePercent ?: return null
        val ends = Dates.parse(debt.promoEndsOn) ?: return null
        val after = debt.interestRatePercent
        return if (after == null) {
            LocalizationRegistry.format(Strings.debts_promo_until, listOf(Percent.display(promo), Dates.display(ends)), locale)
        } else {
            LocalizationRegistry.format(Strings.debts_promo_until_then, listOf(Percent.display(promo), Dates.display(ends), Percent.display(after)), locale)
        }
    }

    /** The name a linked account is shown by, or null when unlinked or the account is gone. */
    fun linkedAccountName(debt: DebtRecord, accounts: List<Account>): String? = debt.accountId?.let { id -> accounts.firstOrNull { it.id == id }?.name }

    /** A promotion's end can't be before today when it is set; an ended one still shows. */
    fun isPast(date: String?, today: LocalDate): Boolean = Dates.parse(date)?.let { it < today } ?: false
}
