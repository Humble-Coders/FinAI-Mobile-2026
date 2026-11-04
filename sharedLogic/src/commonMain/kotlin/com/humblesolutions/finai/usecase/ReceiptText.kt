package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ExtractedDocument

/**
 * A receipt the phone read, as the only thing sent to be read: every line,
 * with card numbers, account numbers, phone numbers, emails, UPI IDs and tax
 * IDs masked the way a statement's are (`StatementRedactor`), the amounts
 * left exactly as printed.
 *
 * Unlike a statement, nothing is dropped: a receipt's top lines are the
 * shop's name and address, not the person's, and its items can start
 * anywhere.
 */
object ReceiptText {

    /** The most the server reads (backend `MAX_RECEIPT_CHARS`). */
    const val MAX_CHARS = 8_000

    fun of(document: ExtractedDocument): String = document.pages
        .flatMap { page -> page.lines.map { it.text.trim() } }
        .filter { it.isNotEmpty() }
        .joinToString("\n") { StatementRedactor.maskLine(it) }
        .take(MAX_CHARS)
}
