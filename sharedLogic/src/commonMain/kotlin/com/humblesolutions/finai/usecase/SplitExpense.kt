package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ReceiptRead
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitItemToSave
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitParticipant
import com.humblesolutions.finai.model.SplitPersonToSave
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.model.SplitToSave
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import com.humblesolutions.finai.util.MoneyInput
import io.ktor.http.encodeURLParameter

/** Where the bill's figures come from. */
enum class SplitSource { RECEIPT, MANUAL }

/** Someone on the bill: the person themselves ([contactId] null) or a saved contact. */
data class SplitPerson(
    val contactId: String?,
    val name: String,
    val phone: String?,
) {
    val isSelf: Boolean get() = contactId == null
}

/** One line of an itemised bill as it is being entered. [assignees] index into the draft's people. */
data class SplitItemDraft(
    val name: String = "",
    val amount: String = "",
    val assignees: Set<Int> = emptySet(),
)

/**
 * A split as the person is making it. How it is split and where the figures
 * come from start unanswered — nothing chooses for them (CLAUDE.md → No silent
 * defaults). The person themselves is always first.
 */
data class SplitDraft(
    val source: SplitSource? = null,
    val mode: SplitMode? = null,
    val title: String = "",
    /** `YYYY-MM-DD`. */
    val occurredOn: String = "",
    /** Splitting equally: the bill's total. */
    val total: String = "",
    /** Splitting by items. */
    val items: List<SplitItemDraft> = emptyList(),
    val tax: String = "",
    val tip: String = "",
    val people: List<SplitPerson> = listOf(SplitPerson(null, "", null)),
)

/** Why a split cannot be made yet. */
enum class SplitBlock(val messageKey: String) {
    NO_MODE(Strings.split_block_mode),
    NO_TITLE(Strings.split_block_title),
    NO_PEOPLE(Strings.split_block_people),
    TOTAL_MISSING(Strings.split_block_total),
    TOTAL_INVALID(Strings.split_block_total_invalid),
    NO_ITEMS(Strings.split_block_items),
    ITEM_NAME_MISSING(Strings.split_block_item_name),
    ITEM_AMOUNT_INVALID(Strings.split_block_item_amount),
    ITEM_UNASSIGNED(Strings.split_block_item_unassigned),
    EXTRA_INVALID(Strings.split_block_extra),
}

/** One person's line on the review: who, how much, and for what. */
data class SplitShareRow(
    val index: Int,
    val initials: String,
    val label: String,
    val share: String,
    /** "Cappuccino, Chicken Sandwich"; null for an equal split. */
    val detail: String?,
    val isSelf: Boolean,
    val accessibility: String,
)

/**
 * Split expense in words and rules, once for both apps: the draft's
 * operations and its blocking reason, the preview of each share
 * ([SplitMath], as the server works them out), and the WhatsApp messages.
 */
object SplitExpense {

    const val FEATURE = "split_expenses"

    /** The most items an itemised bill takes. */
    const val MAX_ITEMS = 60

    /** The most people on one bill (backend `MAX_PEOPLE`). */
    const val MAX_PEOPLE = 20

    class Words(val currency: String, val locale: String = "en") {
        internal fun text(key: String): String = LocalizationRegistry.get(key, locale)

        internal fun text(key: String, vararg args: String): String = LocalizationRegistry.format(key, args.toList(), locale)

        internal fun money(amount: String): String = Money.format(amount, currency, locale)

        internal fun date(iso: String): String = Dates.parse(iso)
            ?.let { text(Strings.split_date, it.dayOfMonth.toString(), Dates.monthShort(it, locale), it.year.toString()) }
            ?: iso
    }

    // ── The draft ───────────────────────────────────────────────────────

    /** A new split on [today] (`YYYY-MM-DD`): just the person themselves on it. */
    fun start(today: String): SplitDraft = SplitDraft(occurredOn = today)

    fun withSource(draft: SplitDraft, source: SplitSource): SplitDraft = draft.copy(source = source)

    fun withMode(draft: SplitDraft, mode: SplitMode): SplitDraft = draft.copy(mode = mode)

    /** "How many items?" — the list grows or shrinks to [count], keeping what was typed. */
    fun withItemCount(draft: SplitDraft, count: Int): SplitDraft {
        val wanted = count.coerceIn(0, MAX_ITEMS)
        val items = draft.items.take(wanted) + List((wanted - draft.items.size).coerceAtLeast(0)) { SplitItemDraft() }
        return draft.copy(items = items)
    }

    fun withItem(draft: SplitDraft, index: Int, name: String, amount: String): SplitDraft = draft.copy(
        items = draft.items.mapIndexed { i, item -> if (i == index) item.copy(name = name, amount = amount) else item },
    )

    /** [person] on or off item [index]. */
    fun toggled(draft: SplitDraft, index: Int, person: Int): SplitDraft = draft.copy(
        items = draft.items.mapIndexed { i, item ->
            if (i != index) item else item.copy(assignees = if (person in item.assignees) item.assignees - person else item.assignees + person)
        },
    )

    /** Item [index] shared by everyone. */
    fun everyone(draft: SplitDraft, index: Int): SplitDraft = draft.copy(
        items = draft.items.mapIndexed { i, item -> if (i == index) item.copy(assignees = draft.people.indices.toSet()) else item },
    )

    /** [contact] on the bill, once. */
    fun withPerson(draft: SplitDraft, contact: SplitContact): SplitDraft {
        if (draft.people.any { it.contactId == contact.id } || draft.people.size >= MAX_PEOPLE) return draft
        return draft.copy(people = draft.people + SplitPerson(contact.id, contact.name, contact.phone))
    }

    /** Person [index] off the bill — never the person themselves — and off every item. */
    fun withoutPerson(draft: SplitDraft, index: Int): SplitDraft {
        if (index <= 0 || index >= draft.people.size) return draft
        fun shift(set: Set<Int>) = set.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
        return draft.copy(
            people = draft.people.filterIndexed { i, _ -> i != index },
            items = draft.items.map { it.copy(assignees = shift(it.assignees)) },
        )
    }

    /**
     * What the receipt said, into the draft. Splitting equally only its total
     * is taken; by items, every item with tax and tip. The name and date
     * when it printed them.
     */
    fun fromReceipt(draft: SplitDraft, read: ReceiptRead): SplitDraft {
        val title = read.merchant?.takeIf { draft.title.isBlank() } ?: draft.title
        val date = read.occurredOn ?: draft.occurredOn
        return when (draft.mode) {
            SplitMode.ITEMS -> draft.copy(
                title = title,
                occurredOn = date,
                items = read.items.map { SplitItemDraft(name = if (it.quantity > 1) "${it.quantity} × ${it.name}" else it.name, amount = it.amount) },
                tax = read.tax.orEmpty(),
                tip = read.tip.orEmpty(),
            )

            else -> draft.copy(title = title, occurredOn = date, total = read.total ?: read.subtotal.orEmpty())
        }
    }

    // ── The rules ───────────────────────────────────────────────────────

    private fun isAmount(raw: String, currency: String, positive: Boolean): Boolean {
        if (MoneyInput.problem(raw, currency) != null) return false
        val minor = SplitMath.minor(raw, currency) ?: return false
        return if (positive) minor > 0 else minor >= 0
    }

    private fun extraOk(raw: String, currency: String): Boolean = raw.isBlank() || isAmount(raw, currency, positive = false)

    /** Why [draft] cannot be split yet, or null when it can. */
    fun blockingReason(draft: SplitDraft, currency: String): SplitBlock? {
        val mode = draft.mode ?: return SplitBlock.NO_MODE
        if (draft.people.size < 2) return SplitBlock.NO_PEOPLE
        if (mode == SplitMode.EQUAL) {
            if (draft.total.isBlank()) return SplitBlock.TOTAL_MISSING
            if (!isAmount(draft.total, currency, positive = true)) return SplitBlock.TOTAL_INVALID
        } else {
            if (draft.items.isEmpty()) return SplitBlock.NO_ITEMS
            draft.items.forEach { item ->
                if (item.name.isBlank()) return SplitBlock.ITEM_NAME_MISSING
                if (!isAmount(item.amount, currency, positive = true)) return SplitBlock.ITEM_AMOUNT_INVALID
            }
            if (!extraOk(draft.tax, currency) || !extraOk(draft.tip, currency)) return SplitBlock.EXTRA_INVALID
            if (draft.items.any { it.assignees.isEmpty() }) return SplitBlock.ITEM_UNASSIGNED
        }
        if (draft.title.isBlank()) return SplitBlock.NO_TITLE
        return null
    }

    fun blockText(block: SplitBlock, locale: String = "en"): String = LocalizationRegistry.get(block.messageKey, locale)

    /**
     * Each person's share as it will be saved, or null while the figures are
     * not complete — an item unassigned counts as nobody's yet, so the preview
     * shows what is assigned so far.
     */
    fun preview(draft: SplitDraft, currency: String): List<String>? {
        val people = draft.people.size
        if (people == 0) return null
        val shares = when (draft.mode) {
            SplitMode.EQUAL -> SplitMath.equalShares(SplitMath.minor(draft.total, currency)?.takeIf { it > 0 } ?: return null, people)

            SplitMode.ITEMS -> {
                val items = draft.items.mapNotNull { item ->
                    val amount = SplitMath.minor(item.amount, currency)?.takeIf { it > 0 } ?: return@mapNotNull null
                    item.assignees.filter { it in 0 until people }.toSet().takeIf { it.isNotEmpty() }?.let { amount to it }
                }
                if (items.isEmpty()) return null
                val tax = SplitMath.minor(draft.tax.ifBlank { "0" }, currency) ?: 0
                val tip = SplitMath.minor(draft.tip.ifBlank { "0" }, currency) ?: 0
                SplitMath.itemShares(people, items, tax, tip)
            }

            null -> return null
        }
        return shares.map { SplitMath.decimal(it, currency) }
    }

    /** The bill's total as it will be saved: the total, or items, tax and tip added up. */
    fun total(draft: SplitDraft, currency: String): String? = when (draft.mode) {
        SplitMode.EQUAL -> SplitMath.minor(draft.total, currency)?.let { SplitMath.decimal(it, currency) }

        SplitMode.ITEMS -> {
            val parts = draft.items.map { SplitMath.minor(it.amount, currency) } +
                listOf(SplitMath.minor(draft.tax.ifBlank { "0" }, currency), SplitMath.minor(draft.tip.ifBlank { "0" }, currency))
            if (parts.any { it == null }) null else SplitMath.decimal(parts.sumOf { it ?: 0 }, currency)
        }

        null -> null
    }

    /** What the unassigned items come to — "₹35.50 unassigned" — or null when everything has someone. */
    fun unassigned(draft: SplitDraft, currency: String): String? {
        if (draft.mode != SplitMode.ITEMS) return null
        val left = draft.items.filter { it.assignees.isEmpty() }.sumOf { SplitMath.minor(it.amount, currency) ?: 0 }
        return if (left > 0) SplitMath.decimal(left, currency) else null
    }

    /** The split as it is sent. Call after [blockingReason] said yes. */
    fun toSave(draft: SplitDraft, currency: String): SplitToSave {
        val digits = Money.fractionDigits(currency)
        fun money(raw: String) = Money.normalize(raw, digits) ?: raw
        val items = draft.mode == SplitMode.ITEMS
        return SplitToSave(
            title = draft.title.trim(),
            occurredOn = draft.occurredOn,
            mode = (draft.mode ?: SplitMode.EQUAL).wire,
            total = if (items) null else money(draft.total),
            items = if (items) draft.items.map { SplitItemToSave(it.name.trim(), money(it.amount), it.assignees.sorted()) } else emptyList(),
            tax = if (items && draft.tax.isNotBlank()) money(draft.tax) else null,
            tip = if (items && draft.tip.isNotBlank()) money(draft.tip) else null,
            participants = draft.people.map { SplitPersonToSave(it.contactId) },
        )
    }

    // ── Words ───────────────────────────────────────────────────────────

    /** "RS" for Rohan Sharma, "P" for Priya: the monogram shown for a person. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words.first().take(1) + words.last().take(1)).uppercase()
        }
    }

    /** "You" for the person themselves, else the name. */
    fun label(person: SplitPerson, locale: String = "en"): String = if (person.isSelf) LocalizationRegistry.get(Strings.split_you, locale) else person.name

    fun label(participant: SplitParticipant, locale: String = "en"): String = if (participant.isSelf) LocalizationRegistry.get(Strings.split_you, locale) else participant.name

    /** The monogram for a person on the draft: "You" has its own. */
    fun initials(person: SplitPerson, locale: String = "en"): String = initials(label(person, locale))

    /** A saved split's people as the review lists them, with each share. */
    fun shareRows(split: Split, words: Words): List<SplitShareRow> = split.participants.mapIndexed { index, p ->
        val label = label(p, words.locale)
        val share = words.money(p.share)
        val detail = p.items.takeIf { it.isNotEmpty() }?.joinToString(", ")
        SplitShareRow(
            index = index,
            initials = initials(label),
            label = label,
            share = share,
            detail = detail,
            isSelf = p.isSelf,
            accessibility = listOfNotNull(label, share, detail).joinToString(", "),
        )
    }

    /** What WhatsApp is opened with for [participant]: their share, what it is for, the whole bill. */
    fun message(split: Split, participant: SplitParticipant, words: Words): String {
        val lines = mutableListOf(
            words.text(Strings.split_message_hi, participant.name),
            words.text(Strings.split_message_share, split.title, words.date(split.occurredOn), words.money(participant.share)),
        )
        if (participant.items.isNotEmpty()) lines += words.text(Strings.split_message_items, participant.items.joinToString(", "))
        lines += words.text(Strings.split_message_total, words.money(split.total))
        lines += words.text(Strings.split_message_sign)
        return lines.joinToString("\n")
    }

    /** The whole split, one line each, for pasting into a group. */
    fun groupMessage(split: Split, words: Words): String {
        val lines = mutableListOf(words.text(Strings.split_message_group, split.title, words.date(split.occurredOn)))
        split.participants.forEach { lines += words.text(Strings.split_message_line, label(it, words.locale), words.money(it.share)) }
        lines += words.text(Strings.split_message_total, words.money(split.total))
        lines += words.text(Strings.split_message_sign)
        return lines.joinToString("\n")
    }

    /**
     * The link that opens WhatsApp to [phone] (E.164) with [message] typed in;
     * the person presses send themselves. Null without a number.
     */
    fun whatsAppLink(phone: String?, message: String): String? {
        val digits = phone?.filter { it.isDigit() }?.takeIf { it.length >= 6 } ?: return null
        return "https://wa.me/$digits?text=" + message.encodeURLParameter()
    }

    /** "₹1,035.00 still to come" or "All settled", under a split in the history. */
    fun summaryLine(summary: SplitSummary, words: Words): String = if (summary.settled) {
        words.text(Strings.split_all_settled)
    } else {
        words.text(Strings.split_owed, words.money(summary.owedToYou))
    }
}
