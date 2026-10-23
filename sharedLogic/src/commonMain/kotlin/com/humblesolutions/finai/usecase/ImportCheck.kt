package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Category
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
    /**
     * The category the server suggested when it read the statement. Sent back
     * on save beside the one chosen, so the server can tell a correction —
     * which teaches a rule for the merchant — from a suggestion accepted.
     */
    val suggestedCategoryId: String? = null,
)

/** One category's share of the money in or out of a statement being checked. */
data class Slice(
    /** Null for the rows with no category yet. */
    val categoryId: String?,
    /** The category's name, or null when it has none yet or the names did not load. */
    val name: String?,
    /** A decimal string. */
    val amount: String,
)

/**
 * Where a statement's money goes, as Home will count it once saved (backend
 * #73): money in and money out by category, largest first, and apart from both
 * the money that only moved between the household's own accounts.
 */
data class Breakdown(
    val moneyIn: List<Slice>,
    val moneyOut: List<Slice>,
    /** Total of [moneyIn], or null when nothing came in. */
    val totalIn: String?,
    /** Total of [moneyOut], or null when nothing went out. */
    val totalOut: String?,
    /** Transfers and savings, either way; null when there are none. */
    val betweenAccounts: String?,
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
     * Money between the household's own accounts, in neither money in nor
     * money out — the same slugs as the backend's `dashboard.NOT_A_FLOW`.
     */
    val BETWEEN_ACCOUNTS: Set<String> = setOf("transfers", "savings")

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
                categoryId = row.categoryId,
            ),
            suggestedCategoryId = row.categoryId,
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
            categoryId = draft.categoryId,
        )
        return rows.map { if (it.row.id == id) it.copy(row = changed, edited = true) else it }
    }

    /** [rows] without [id]. Removing is not editing: the row is simply not saved. */
    fun remove(rows: List<CheckRow>, id: String): List<CheckRow> = rows.filterNot { it.row.id == id }

    /** Save needs something to save; the server refuses an empty import. */
    fun canSave(rows: List<CheckRow>): Boolean = rows.isNotEmpty()

    /**
     * What Save sends. A row the person changed is sent as confirmed, so the
     * server does not put it back in front of them as one it was unsure of.
     */
    fun rowsToSave(accountId: String, rows: List<CheckRow>, closingBalance: StatementBalance.Found? = null): RowsToSave = RowsToSave(
        accountId = accountId,
        closingBalance = closingBalance?.balance,
        closingDate = closingBalance?.closingDate,
        rows = rows.map { (row, edited, suggested) ->
            RowToSave(
                occurredOn = row.occurredOn,
                description = row.description.orEmpty(),
                amount = row.amount,
                direction = row.direction.wire,
                confidence = if (edited) CONFIRMED else row.extractionConfidence ?: CONFIRMED,
                categoryId = row.categoryId,
                suggestedCategoryId = suggested,
            )
        },
    )

    /**
     * Money in and money out by category, and what only moved between the
     * household's own accounts — so the person sees where the statement's
     * money goes before saving, counted the way Home will count it.
     *
     * A row's side is its direction; transfers and savings are on neither. A
     * category whose name has not loaded still has its own slice, unnamed.
     */
    fun breakdown(rows: List<CheckRow>, categories: List<Category>, fractionDigits: Int = 2): Breakdown {
        val byId = categories.associateBy { it.id }
        val between = mutableListOf<String>()
        val sides = mapOf(
            TransactionDirection.CREDIT to linkedMapOf<String?, String>(),
            TransactionDirection.DEBIT to linkedMapOf<String?, String>(),
        )
        for ((row) in rows) {
            val category = row.categoryId?.let(byId::get)
            if (category?.slug in BETWEEN_ACCOUNTS) {
                between += row.amount
                continue
            }
            val side = sides[row.direction] ?: continue
            side[row.categoryId] = Money.add(side[row.categoryId] ?: "0", row.amount, fractionDigits) ?: continue
        }

        fun slices(side: Map<String?, String>): List<Slice> = side
            .map { (id, amount) -> Slice(id, id?.let(byId::get)?.let(ReviewQueue::categoryName), amount) }
            .sortedWith { a, b -> Money.compare(b.amount, a.amount, fractionDigits) }

        fun total(amounts: Collection<String>): String? = if (amounts.isEmpty()) {
            null
        } else {
            amounts.fold("0") { running, amount -> Money.add(running, amount, fractionDigits) ?: return null }
        }

        return Breakdown(
            moneyIn = slices(sides.getValue(TransactionDirection.CREDIT)),
            moneyOut = slices(sides.getValue(TransactionDirection.DEBIT)),
            totalIn = total(sides.getValue(TransactionDirection.CREDIT).values),
            totalOut = total(sides.getValue(TransactionDirection.DEBIT).values),
            betweenAccounts = total(between),
        )
    }

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
