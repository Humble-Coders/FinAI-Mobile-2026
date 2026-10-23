package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

/**
 * A card or loan statement's closing balance, read on the phone **before**
 * redaction (ticket #76, decision 3).
 *
 * The balance sits in the summary block — "New balance $1,234.56" — that
 * [StatementRedactor] drops along with the name and address, so the server's
 * reader would almost never see it. It is read here, as [StatementPeriod] is,
 * by a pattern and no AI; the person confirms it on the check screen; and only
 * then does the save request carry it. A balance is not personal data, and
 * nothing else from the block leaves the phone.
 *
 * Unreadable means absent: no balance is offered and the import is unaffected.
 */
object StatementBalance {

    /** The figure and the day it was true. [balance] is a decimal string at the currency's scale. */
    data class Found(val balance: String, val closingDate: String)

    /** A balance found, and the debt linked to the statement's account that it would update. */
    data class Offer(val found: Found, val debtId: String, val debtName: String)

    // What statements call the figure owed at the close. "Previous balance"
    // is deliberately not among them.
    private val LABEL = Regex(
        """\b(new balance|closing balance|statement balance|total balance due|balance due|new balance total)\b""",
        RegexOption.IGNORE_CASE,
    )

    // A figure with cents, an optional currency mark, and the marks of a
    // credit: brackets, a minus, or "CR".
    private val AMOUNT = Regex("""(\()?(-)?\s?(?:[$€£₹]|CA\$|C\$|US\$)?\s?(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})(\))?(-)?(\s?CR\b)?""", RegexOption.IGNORE_CASE)

    private val DATE_LABEL = Regex("""\b(statement date|closing date|statement closing date)\b""", RegexOption.IGNORE_CASE)

    /** The summary is near the top, but some statements put it on the last page. */
    private const val LINES_PER_PAGE = 60

    /**
     * The closing balance and its date, or null when either cannot be read.
     *
     * The date is the statement's own "Statement date" or "Closing date" line,
     * else the end of [periodEnd] — the period [StatementPeriod] found. A
     * balance with no date is not offered: the server keeps a newer balance
     * over an older one, and an undated figure cannot be placed.
     *
     * A balance in credit (brackets, a minus or "CR") is not offered either:
     * the server refuses it, as there is nothing to pay off.
     */
    fun find(document: ExtractedDocument, periodEnd: String?, currency: String?): Found? {
        val lines = document.pages.flatMap { page -> page.lines.take(LINES_PER_PAGE).map { it.text } }
        val digits = Money.fractionDigits(currency ?: "")
        val balance = balanceIn(lines, digits) ?: return null
        val date = closingDateIn(lines) ?: periodEnd?.takeIf { Dates.parse(it) != null } ?: return null
        return Found(balance, date)
    }

    private fun balanceIn(lines: List<String>, digits: Int): String? {
        lines.forEachIndexed { index, line ->
            val label = LABEL.find(line) ?: return@forEachIndexed
            // The figure after the label on its line, else alone on the next:
            // a two-column summary often reads as label and figure on two lines.
            val after = line.substring(label.range.last + 1)
            val match = AMOUNT.find(after)
                ?: lines.getOrNull(index + 1)?.takeIf { LABEL.find(it) == null }?.let { next -> AMOUNT.find(next)?.takeIf { next.trim().length - it.value.trim().length < 8 } }
                ?: return@forEachIndexed
            return amountOf(match, digits)
        }
        return null
    }

    private fun amountOf(match: MatchResult, digits: Int): String? {
        val (open, minus, whole, cents, close, trailingMinus, credit) = match.destructured
        if (minus.isNotEmpty() || trailingMinus.isNotEmpty() || credit.isNotBlank() || (open.isNotEmpty() && close.isNotEmpty())) return null
        return Money.normalize("${whole.replace(",", "")}.$cents", digits)
    }

    private fun closingDateIn(lines: List<String>): String? {
        for (line in lines) {
            val label = DATE_LABEL.find(line) ?: continue
            StatementPeriod.datesIn(line.substring(label.range.last + 1)).firstOrNull()?.let { return it }
        }
        return null
    }

    /**
     * What the check screen offers: [found] for the debt linked to [accountId],
     * when there is one and the statement closed after the balance it has —
     * the server's own rule, so the person is never asked about a balance it
     * would then not apply.
     */
    fun offer(found: Found?, debts: List<DebtRecord>, accountId: String?): Offer? {
        val balance = found ?: return null
        val account = accountId ?: return null
        val debt = debts.firstOrNull { it.accountId == account } ?: return null
        val closing = Dates.parse(balance.closingDate) ?: return null
        val asOf = Dates.parse(debt.balanceAsOf)
        if (asOf != null && asOf >= closing) return null
        return Offer(balance, debt.id, debt.name)
    }

    /** "This statement says you owed $1,234.56 on 31 Aug 2026. Update Visa to this balance?" */
    fun question(offer: Offer, currency: String, locale: String = "en"): String = LocalizationRegistry.format(
        Strings.import_balance_question,
        listOf(
            Money.format(offer.found.balance, currency, locale),
            Dates.parse(offer.found.closingDate)?.let(Dates::display) ?: offer.found.closingDate,
            offer.debtName,
        ),
        locale,
    )
}
