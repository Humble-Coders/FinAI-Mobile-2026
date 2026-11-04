package com.humblesolutions.finai.ui.split

import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitParticipant
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.usecase.SplitBlock
import com.humblesolutions.finai.usecase.SplitDraft
import com.humblesolutions.finai.usecase.SplitExpense
import com.humblesolutions.finai.usecase.SplitPerson
import com.humblesolutions.finai.usecase.SplitShareRow
import com.humblesolutions.finai.usecase.SplitSource
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money

/** The steps of a split, in the order the flow walks them; back pops the last one. */
internal enum class SplitStep { START, BILL, PEOPLE, ASSIGN, REVIEW, HISTORY }

/** Where a receipt is: waiting to be chosen, being read, or read and under review. */
internal enum class ReceiptStage { PICK, READING, READ }

/** Consent to AI processing, asked before the first receipt is read. Nothing is pre-ticked. */
internal data class SplitConsentAsk(
    val policy: AiPolicy? = null,
    val ticked: Boolean = false,
    val busy: Boolean = false,
    val errorKey: String? = null,
)

/** The "Add new person" sheet: typed, or filled from the phone's contacts. */
internal data class NewPersonDraft(
    val name: String = "",
    val phone: String = "",
    val busy: Boolean = false,
    val errorKey: String? = null,
) {
    val canSave: Boolean get() = name.isNotBlank() && phone.any { it.isDigit() } && !busy
}

/** One person's line before the split is saved: the preview's share, worked out as the server will. */
internal data class PreviewRow(
    val initials: String,
    val label: String,
    val share: String?,
    val detail: String?,
    val isSelf: Boolean,
) {
    val accessibility: String get() = listOfNotNull(label, share, detail).joinToString(", ")
}

/**
 * Everything the split flow shows. Every rule — what blocks Continue, each
 * share, the WhatsApp words — is shared `SplitExpense`'s; this only holds the
 * answers and says which of them a screen needs.
 */
internal data class SplitUiState(
    val stack: List<SplitStep> = listOf(SplitStep.START),
    val draft: SplitDraft = SplitDraft(),
    val currency: String = "",
    val locale: String = "en",
    /** "How many items?" as typed, so an emptied field does not throw away the rows. */
    val itemCountInput: String = "",
    // ── The receipt ──
    val receiptStage: ReceiptStage = ReceiptStage.PICK,
    val receiptErrorKey: String? = null,
    /** The read failed in a way that sending the same text again could fix. */
    val receiptCanRetry: Boolean = false,
    val unreadable: Int = 0,
    val consent: SplitConsentAsk? = null,
    // ── People ──
    val contacts: List<SplitContact> = emptyList(),
    val contactsLoading: Boolean = false,
    val contactsLoaded: Boolean = false,
    val contactsErrorKey: String? = null,
    val search: String = "",
    val newPerson: NewPersonDraft? = null,
    val confirmingContactDelete: SplitContact? = null,
    // ── The review ──
    /** The split being reviewed once saved, or opened from the history. */
    val savedId: String? = null,
    val saved: Split? = null,
    val openingSplit: Boolean = false,
    val openErrorKey: String? = null,
    val saving: Boolean = false,
    val saveErrorKey: String? = null,
    val settling: Set<String> = emptySet(),
    val settleErrorKey: String? = null,
    /** A person WhatsApp could not be opened for: the sheet offering their message to copy. */
    val noWhatsAppFor: String? = null,
    // ── The history ──
    val history: List<SplitSummary> = emptyList(),
    val historyLoading: Boolean = false,
    val historyLoaded: Boolean = false,
    val historyErrorKey: String? = null,
    val confirmingDelete: SplitSummary? = null,
    val deleting: Boolean = false,
) {
    val step: SplitStep get() = stack.last()

    val words: SplitExpense.Words get() = SplitExpense.Words(currency, locale)

    val byItems: Boolean get() = draft.mode == SplitMode.ITEMS

    val fromReceipt: Boolean get() = draft.source == SplitSource.RECEIPT

    // ── Start ──

    /** Continue waits for both answers; neither is chosen for the person. */
    val canContinueFromStart: Boolean get() = draft.source != null && draft.mode != null

    // ── The bill ──

    /** The bill's form: shown for a typed bill, and for a receipt once it has been read. */
    val showsBillForm: Boolean get() = !fromReceipt || receiptStage == ReceiptStage.READ

    /**
     * What stops the bill itself — its total, items, tax, tip or name — with
     * who is on it and who had what left for the steps that ask. The shared
     * rule, asked about this bill with those two answered.
     */
    val billBlock: SplitBlock? get() = SplitExpense.billBlockingReason(draft, currency)

    val canContinueFromBill: Boolean get() = showsBillForm && billBlock == null

    val billNotice: String? get() = billBlock?.let { SplitExpense.blockText(it, locale) }

    // ── People ──

    /** Saved people matching the search, by name or by any run of the number's digits. */
    val shownContacts: List<SplitContact>
        get() = contacts.filter { SplitExpense.matches(it, search) }

    val chosenContactIds: Set<String> get() = draft.people.mapNotNull { it.contactId }.toSet()

    /** Everyone on the bill but the person themselves. */
    val chosenCount: Int get() = draft.people.size - 1

    val peopleBlock: SplitBlock? get() = SplitExpense.blockingReason(draft, currency)?.takeIf { it == SplitBlock.NO_PEOPLE }

    val canContinueFromPeople: Boolean get() = peopleBlock == null

    /** Opened from Start's "Add people": Continue goes back there rather than onward. */
    val peopleReturnsToStart: Boolean get() = stack.size >= 2 && stack[stack.size - 2] == SplitStep.START

    val atPeopleLimit: Boolean get() = draft.people.size >= SplitExpense.MAX_PEOPLE

    // ── Assigning ──

    val assignBlock: SplitBlock? get() = SplitExpense.blockingReason(draft, currency)

    val unassigned: String? get() = SplitExpense.unassigned(draft, currency)?.let(::money)

    val unassignedWarning: String? get() = unassigned?.let { LocalizationRegistry.format(Strings.split_unassigned_warning, listOf(it), locale) }

    /** What the items with someone on them come to. */
    val assigned: String get() = money(SplitExpense.assigned(draft, currency))

    val canAddItem: Boolean get() = draft.items.size < SplitExpense.MAX_ITEMS

    // ── The review ──

    val reviewingSaved: Boolean get() = savedId != null

    val saveBlock: SplitBlock? get() = SplitExpense.blockingReason(draft, currency)

    val canSave: Boolean get() = saveBlock == null && !saving

    val draftTotal: String? get() = SplitExpense.total(draft, currency)?.let(::money)

    /** Each person's share as it will be saved; null shares while the figures are not complete. */
    val previewRows: List<PreviewRow>
        get() {
            val shares = SplitExpense.preview(draft, currency)
            return draft.people.mapIndexed { index, person ->
                val items = draft.items.filter { index in it.assignees }.map { it.name.trim() }.filter { it.isNotEmpty() }
                PreviewRow(
                    initials = SplitExpense.initials(person, locale),
                    label = SplitExpense.label(person, locale),
                    share = shares?.getOrNull(index)?.let(::money),
                    detail = items.takeIf { byItems && it.isNotEmpty() }?.joinToString(", "),
                    isSelf = person.isSelf,
                )
            }
        }

    val shareRows: List<SplitShareRow> get() = saved?.let { SplitExpense.shareRows(it, words) }.orEmpty()

    val groupMessage: String? get() = saved?.let { SplitExpense.groupMessage(it, words) }

    fun participant(id: String): SplitParticipant? = saved?.participants?.firstOrNull { it.id == id }

    /** What WhatsApp is opened with for [participant]: their share of the SAVED split. */
    fun messageFor(participant: SplitParticipant): String? = saved?.let { SplitExpense.message(it, participant, words) }

    fun whatsAppLinkFor(participant: SplitParticipant): String? = messageFor(participant)?.let { SplitExpense.whatsAppLink(participant.phone, it) }

    // ── Words ──

    fun money(amount: String): String = words.money(amount)

    fun dateText(iso: String): String = words.date(iso)

    fun summaryLine(summary: SplitSummary): String = SplitExpense.summaryLine(summary, words)
}
