package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Budget
import com.humblesolutions.finai.model.BudgetCommitment
import com.humblesolutions.finai.model.CommitmentPayment
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/** One recorded payment that could tick a commitment off. */
data class TickCandidate(
    val transactionId: String,
    val title: String,
    val detail: String,
    val amount: String,
    /** Its amount is close to what the commitment expects — the likely one. */
    val likely: Boolean,
)

/** Why a typed-in payment cannot be saved yet. */
enum class TickBlock(val messageKey: String) {
    AMOUNT(Strings.tick_block_amount),
    DATE(Strings.tick_block_date),
    ACCOUNT(Strings.tick_block_account),
}

/**
 * Ticking a commitment off by hand: with a payment already recorded in the
 * month, or one typed in there and then. The server records the link and
 * counts the money once; this chooses what to offer and words it.
 */
object CommitmentTick {

    private const val MAX_CANDIDATES = 30

    /** "Mark Rent as paid". */
    fun title(commitment: BudgetCommitment, locale: String = "en"): String = format(Strings.tick_title, locale, commitment.name)

    /** "Expected $1,800.00", and when it was seen paid, how. */
    fun subtitle(commitment: BudgetCommitment, budget: Budget, locale: String = "en"): String {
        val expected = format(Strings.tick_expected, locale, Money.format(commitment.expected, budget.currency, locale))
        val paidOn = Dates.parse(commitment.paidOn) ?: return expected
        val paid = format(
            if (commitment.paidByHand) Strings.tick_paid_by_hand else Strings.tick_paid_seen,
            locale,
            Dates.display(paidOn),
            Money.format(commitment.paidAmount ?: commitment.expected, budget.currency, locale),
        )
        return "$expected · $paid"
    }

    /**
     * The month's payments out that could have paid [commitment], closest in
     * amount first. A payment already ticking off another commitment is left
     * out: the server would refuse it, since one payment never pays two.
     */
    fun candidates(rows: List<Transaction>, commitment: BudgetCommitment, budget: Budget, locale: String = "en"): List<TickCandidate> {
        val month = Dates.parse(budget.month)
        val taken = budget.commitments.filter { it.id != commitment.id }.mapNotNull { it.transactionId }.toSet()
        val expected = commitment.expected.toDoubleOrNull() ?: 0.0
        return rows
            .asSequence()
            .filter { it.direction == TransactionDirection.DEBIT && it.id !in taken }
            .filter { row ->
                val day = Dates.parse(row.occurredOn) ?: return@filter false
                month == null || (day.year == month.year && day.month == month.month)
            }
            .sortedWith(compareBy({ kotlin.math.abs((it.amount.toDoubleOrNull() ?: 0.0) - expected) }, { it.occurredOn }))
            .take(MAX_CANDIDATES)
            .map { row ->
                val units = row.amount.toDoubleOrNull() ?: 0.0
                TickCandidate(
                    transactionId = row.id,
                    title = row.merchant?.takeIf { it.isNotBlank() } ?: row.description.orEmpty(),
                    detail = Dates.parse(row.occurredOn)?.let(Dates::display).orEmpty(),
                    amount = Money.format(row.amount, budget.currency, locale),
                    likely = expected > 0 && kotlin.math.abs(units - expected) <= expected * 0.1,
                )
            }
            .toList()
    }

    /**
     * The date a typed-in payment starts on: its due day in the month (the
     * month's last day for a 31 in a shorter month), but never after [today].
     */
    fun defaultDate(commitment: BudgetCommitment, budget: Budget, today: LocalDate): LocalDate {
        val first = Dates.parse(budget.month) ?: return today
        val last = lastDay(first)
        val due = commitment.dueDay?.let { LocalDate(first.year, first.month, minOf(it, last.day)) } ?: first
        val latest = if (today < last) today else last
        return when {
            due > latest -> if (latest < first) first else latest
            else -> due
        }
    }

    /** Whether [date] falls in the budget's month. */
    fun inMonth(date: LocalDate, budget: Budget): Boolean {
        val first = Dates.parse(budget.month) ?: return true
        return date.year == first.year && date.month == first.month
    }

    /** Why the typed-in payment cannot be saved, or null. */
    fun blockingReason(amount: String, date: LocalDate?, accountId: String?, budget: Budget): TickBlock? {
        val digits = budget.fractionDigits
        if (!Money.isPositive(amount, digits)) return TickBlock.AMOUNT
        if (date == null || !inMonth(date, budget)) return TickBlock.DATE
        if (accountId.isNullOrBlank()) return TickBlock.ACCOUNT
        return null
    }

    /** What is sent for a typed-in payment, or null while [blockingReason] says no. */
    fun payment(amount: String, date: LocalDate?, accountId: String?, budget: Budget, allowDuplicate: Boolean = false): CommitmentPayment? {
        if (blockingReason(amount, date, accountId, budget) != null) return null
        val normalized = Money.normalize(amount, budget.fractionDigits) ?: return null
        return CommitmentPayment(accountId = accountId!!, occurredOn = date.toString(), amount = normalized, allowDuplicate = allowDuplicate)
    }

    private fun lastDay(first: LocalDate): LocalDate {
        val days = when (first.month.number) {
            2 -> if (first.year % 4 == 0 && (first.year % 100 != 0 || first.year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return LocalDate(first.year, first.month, days)
    }

    private fun format(key: String, locale: String, vararg args: String) = LocalizationRegistry.format(key, args.toList(), locale)
}
