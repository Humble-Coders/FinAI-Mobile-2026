package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

/**
 * What an import produced, arranged for the screen that reports it (#31, F3).
 *
 * "Imported 24 transactions" is a claim nobody can check. These are the 24,
 * and the question they answer is the one a person actually has: did it read
 * my statement right, and did it file things where I would have.
 *
 * Shared because the arrangement is a decision — newest first, grouped by the
 * day on the statement, totals counted one way — and two screens deriving it
 * separately would eventually disagree about a figure sitting next to the
 * same rows.
 */
object ImportedRows {

    /** One day of a statement, with the rows that fell on it. */
    data class Day(
        val date: String,
        val rows: List<Transaction>,
    )

    /**
     * The rows grouped by their date, newest day first.
     *
     * By the date on the statement, not the order they were saved in: a person
     * checking an import is reading down their statement, and insertion order
     * interleaves nothing they recognise. Within a day the server's order is
     * kept, which is the order it read them.
     */
    fun byDate(rows: List<Transaction>): List<Day> = rows
        .groupBy { it.occurredOn }
        .map { (date, onThatDay) -> Day(date = date, rows = onThatDay) }
        .sortedByDescending { it.date }

    /**
     * What left the account across these rows, as a decimal string.
     *
     * Debits only. Money in and money out are different questions and a net
     * figure answers neither — somebody checking an import wants to know what
     * it says they spent.
     */
    fun totalOut(rows: List<Transaction>, fractionDigits: Int = 2): String? = total(rows.filter { it.direction == TransactionDirection.DEBIT }, fractionDigits)

    /** What arrived across these rows. */
    fun totalIn(rows: List<Transaction>, fractionDigits: Int = 2): String? = total(rows.filter { it.direction == TransactionDirection.CREDIT }, fractionDigits)

    private fun total(rows: List<Transaction>, fractionDigits: Int): String? {
        if (rows.isEmpty()) return null
        return rows.fold("0") { running, row ->
            // Every amount here came from the server at this scale, so this
            // cannot refuse today; it returns null rather than a wrong total
            // if that ever stops being true.
            Money.add(running, row.amount, fractionDigits) ?: return null
        }
    }

    /** How many of these the model could not file, and so left for a person. */
    fun waitingCount(rows: List<Transaction>): Int = rows.count { it.needsReview }

    /**
     * The category a row was filed into, or null when nothing filed it.
     *
     * Null is shown as "uncategorised" and never as a blank: a row with no
     * category is the single most useful thing on this screen, because it is
     * the one the person has to do something about.
     */
    fun categoryOf(row: Transaction, categories: List<Category>): Category? = row.categoryId?.let { id -> categories.firstOrNull { it.id == id } }

    /**
     * What a question about one row names it by — its title, amount and day —
     * so "Delete this transaction?" says which, in both apps the same way.
     */
    fun summary(row: Transaction, locale: String = "en"): List<String> = listOf(
        titleOf(row),
        Money.format(row.amount, row.currency, locale),
        Dates.parse(row.occurredOn)?.let { Dates.display(it) } ?: row.occurredOn,
    )

    /** What to call a row: the merchant if the parser found one, else its description. */
    fun titleOf(row: Transaction): String = row.merchant?.takeIf { it.isNotBlank() }
        ?: row.description?.takeIf { it.isNotBlank() }
        ?: ""
}
