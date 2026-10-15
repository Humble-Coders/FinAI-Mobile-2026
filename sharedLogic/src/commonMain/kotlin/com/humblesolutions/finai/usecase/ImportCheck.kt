package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.RowToSave
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * One row of a statement that has been read but not saved (#55): the row as it
 * would be saved, and whether the person changed it.
 *
 * Held as a [Transaction] so the screen draws it, totals it and edits it with
 * what already exists for saved rows — [ImportedRows] and the editor sheet —
 * rather than a second set that would drift. It has no category: the server
 * files rows when they are saved.
 */
data class CheckRow(
    val row: Transaction,
    val edited: Boolean = false,
)

/**
 * The rules of checking an import before it is saved — **the one place they
 * live**. Both apps read these; the screens only show the answers
 * (kmp-arch-v2).
 *
 * The rows exist only here, in memory. They are a household's financial
 * records, so they are never written to saved state or to disk: if the phone
 * ends the app mid-check, the statement is read again (free since backend #73)
 * and the person is told their changes were not kept.
 */
object ImportCheck {

    /**
     * Below this the reader was unsure of a row, so the screen asks for a look
     * before saving. The same line the server draws when it sends a row to
     * review (`LOW_CONFIDENCE`, Finance-backend `app/services/ledger.py`): a row
     * marked here is exactly one that would otherwise wait in Review.
     */
    const val CHECK_BELOW = 70

    /** What an edited row is sent with: the person has now read it. */
    const val CONFIRMED = 100

    private const val ID_PREFIX = "check-"

    /**
     * The parsed rows, ready to check, in the order the server read them.
     * Each has a made-up id, unique within the import, so the screen can name
     * one — they have no real id until they are saved.
     */
    fun rowsOf(parsed: ParsedStatement, accountId: String): List<CheckRow> = parsed.rows.mapIndexed { index, row ->
        CheckRow(
            Transaction(
                id = "$ID_PREFIX$index",
                accountId = accountId,
                occurredOn = row.occurredOn,
                amount = row.amount,
                currency = parsed.currency,
                direction = TransactionDirection.fromWire(row.direction),
                description = row.description,
                extractionConfidence = row.confidence,
            ),
        )
    }

    /** Just the rows, for [ImportedRows]' totals and grouping. */
    fun transactions(rows: List<CheckRow>): List<Transaction> = rows.map { it.row }

    /** Whether to ask the person to look at this one: read with doubt, and not since changed by them. */
    fun needsCheck(row: CheckRow): Boolean = !row.edited && (row.row.extractionConfidence ?: CONFIRMED) < CHECK_BELOW

    /** How many still ask for a look. */
    fun doubtCount(rows: List<CheckRow>): Int = rows.count(::needsCheck)

    /**
     * [rows] with [id] changed to [draft], or null when the draft cannot be
     * saved — the same rules, and the same reasons, as fixing a saved row
     * ([ReviewQueue.blockingReason]), including "nothing changed". The amount is
     * kept in the form the server expects, never as typed.
     */
    fun edit(rows: List<CheckRow>, id: String, draft: CorrectionDraft, today: LocalDate): List<CheckRow>? {
        val target = rows.firstOrNull { it.row.id == id } ?: return null
        if (ReviewQueue.blockingReason(target.row, draft, today) != null) return null
        val digits = Money.fractionDigits(target.row.currency)
        val changed = target.row.copy(
            occurredOn = draft.occurredOn?.toString() ?: target.row.occurredOn,
            amount = Money.normalize(draft.amount, digits) ?: return null,
            direction = draft.direction ?: target.row.direction,
            description = draft.description.trim(),
        )
        return rows.map { if (it.row.id == id) CheckRow(changed, edited = true) else it }
    }

    /** [rows] without [id]. Removing is not editing: the row is simply not saved. */
    fun remove(rows: List<CheckRow>, id: String): List<CheckRow> = rows.filterNot { it.row.id == id }

    /** Save needs something to save; the server refuses an empty import. */
    fun canSave(rows: List<CheckRow>): Boolean = rows.isNotEmpty()

    /**
     * What Save sends. A row the person changed is sent as confirmed, so the
     * server does not put it back in front of them as one it was unsure of.
     */
    fun rowsToSave(accountId: String, rows: List<CheckRow>): RowsToSave = RowsToSave(
        accountId = accountId,
        rows = rows.map { (row, edited) ->
            RowToSave(
                occurredOn = row.occurredOn,
                description = row.description.orEmpty(),
                amount = row.amount,
                direction = row.direction.wire,
                confidence = if (edited) CONFIRMED else row.extractionConfidence ?: CONFIRMED,
            )
        },
    )

    /** "12 transactions". */
    fun countLabel(rows: List<CheckRow>, locale: String = "en"): String = counted(rows.size, Strings.import_check_count, Strings.import_check_count_one, locale)

    /** "Save 12 transactions" — the button says what pressing it does. */
    fun saveLabel(rows: List<CheckRow>, locale: String = "en"): String = counted(rows.size, Strings.import_check_save, Strings.import_check_save_one, locale)

    private fun counted(count: Int, many: String, one: String, locale: String): String = if (count == 1) {
        LocalizationRegistry.get(one, locale)
    } else {
        LocalizationRegistry.format(many, listOf(count.toString()), locale)
    }
}
