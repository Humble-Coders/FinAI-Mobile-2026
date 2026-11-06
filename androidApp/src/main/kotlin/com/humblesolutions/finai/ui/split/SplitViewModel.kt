package com.humblesolutions.finai.ui.split

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.AndroidStatementReader
import com.humblesolutions.finai.data.KtorSplitRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.repository.SplitRepository
import com.humblesolutions.finai.repository.StatementReader
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ReceiptText
import com.humblesolutions.finai.usecase.SplitDraft
import com.humblesolutions.finai.usecase.SplitExpense
import com.humblesolutions.finai.usecase.SplitItemDraft
import com.humblesolutions.finai.usecase.SplitPerson
import com.humblesolutions.finai.usecase.SplitSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** The clients a split needs, built fresh on every bind and closed with it. */
internal class SplitRepositories(
    val splits: SplitRepository,
    /** The statement import's own on-device reader: ML Kit for photos, PdfBox for PDFs. */
    val reader: StatementReader,
) {
    fun close() {
        splits.close()
        reader.close()
    }
}

/**
 * Split expense: a bill, the people on it, and each person's share sent on
 * WhatsApp.
 *
 * Every rule is shared `SplitExpense`'s — what blocks each Continue, the
 * preview, the messages; this moves answers in and out and sends them. The
 * draft, the step and the split being reviewed survive rotation and the
 * process being reclaimed ([SavedStateHandle]), kept beside the person they
 * belong to so a restore never hands one person's bill to another.
 *
 * @param today where "today" comes from; a parameter so a test can fix it.
 */
internal class SplitViewModel internal constructor(
    private val saved: SavedStateHandle,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** The constructor `viewModel()` calls; the saved state is Android's. */
    constructor(saved: SavedStateHandle) : this(saved, ManualEntry::today)

    private val _uiState = MutableStateFlow(restored())
    val uiState: StateFlow<SplitUiState> = _uiState.asStateFlow()

    private var repositories: SplitRepositories? = null

    private var boundTo: String?
        get() = saved[KEY_OWNER]
        set(value) {
            saved[KEY_OWNER] = value
        }

    /** Rises on every bind and every fresh start, so a late answer cannot land in the next bill. */
    private var generation = 0

    /** The masked receipt text last read, kept in memory only, to send again after consent or a failure. */
    private var receiptText: String? = null

    private var work: Job? = null

    fun bind(userId: String, currency: String, locale: String, context: Context, logging: Boolean) = bind(userId, currency, locale) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            SplitRepositories(
                splits = KtorSplitRepository(ApiConfig.BASE_URL, tokens, logging),
                reader = AndroidStatementReader(context.applicationContext),
            )
        }
    }

    /** The whole of [bind], with the clients' source left open for tests. */
    internal fun bind(userId: String, currency: String, locale: String, build: () -> SplitRepositories?) {
        if (userId.isBlank()) return
        // The household's currency and language, from capabilities; they can
        // arrive after the screen does.
        update { it.copy(currency = currency, locale = locale.ifBlank { "en" }) }
        if (userId != boundTo) {
            repositories?.close()
            repositories = null
            generation++
            receiptText = null
            if (boundTo != null) clearSaved()
            boundTo = userId
            _uiState.update { SplitUiState(draft = SplitExpense.start(today().toString()), currency = it.currency, locale = it.locale) }
            store()
        }
        if (repositories != null) return
        repositories = build() ?: return
        loadContacts()
        val state = _uiState.value
        state.savedId?.let { loadSplit(it) }
        if (SplitStep.HISTORY in state.stack) loadHistory()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    // ── Moving between steps ────────────────────────────────────────────

    private fun push(step: SplitStep) = change { it.copy(stack = it.stack + step) }

    /** One step back. False at the first step, where back leaves the flow. */
    fun back(): Boolean {
        val state = _uiState.value
        // Nothing to go back to mid-read; the loader covers the screen.
        if (state.receiptStage == ReceiptStage.READING) return true
        if (state.stack.size <= 1) return false
        val leaving = state.step
        change {
            val popped = it.copy(stack = it.stack.dropLast(1), saveErrorKey = null)
            if (leaving == SplitStep.REVIEW && it.reviewingSaved) {
                popped.copy(savedId = null, saved = null, openingSplit = false, openErrorKey = null, settleErrorKey = null)
            } else {
                popped
            }
        }
        return true
    }

    /** Leaving on purpose: the bill goes with it; the saved people stay loaded. */
    fun discard() {
        generation++
        work?.cancel()
        receiptText = null
        change {
            SplitUiState(
                draft = SplitExpense.start(today().toString()),
                currency = it.currency,
                locale = it.locale,
                contacts = it.contacts,
                contactsLoaded = it.contactsLoaded,
            )
        }
    }

    // ── Start ───────────────────────────────────────────────────────────

    fun chooseSource(source: SplitSource) {
        val state = _uiState.value
        if (state.draft.source == source) return
        receiptText = null
        change {
            it.copy(
                draft = SplitExpense.withSource(cleared(it.draft, it.receiptStage == ReceiptStage.READ), source),
                receiptStage = ReceiptStage.PICK,
                receiptErrorKey = null,
                unreadable = 0,
            )
        }
    }

    fun chooseMode(mode: SplitMode) {
        val state = _uiState.value
        if (state.draft.mode == mode) return
        // A receipt read for the other way of splitting holds the wrong figures: read it again.
        val reread = state.fromReceipt && state.receiptStage == ReceiptStage.READ
        change {
            it.copy(
                draft = SplitExpense.withMode(if (reread) cleared(it.draft, true) else it.draft, mode),
                receiptStage = if (reread) ReceiptStage.PICK else it.receiptStage,
            )
        }
    }

    fun continueFromStart() {
        if (_uiState.value.canContinueFromStart) push(SplitStep.BILL)
    }

    fun openPeople() {
        push(SplitStep.PEOPLE)
        val state = _uiState.value
        if (!state.contactsLoaded && !state.contactsLoading) loadContacts()
    }

    fun openHistory() {
        push(SplitStep.HISTORY)
        loadHistory()
    }

    // ── The bill ────────────────────────────────────────────────────────

    fun onTitleChange(title: String) = editDraft { it.copy(title = title) }

    fun onDateChosen(date: LocalDate) = editDraft { it.copy(occurredOn = date.toString()) }

    fun onTotalChange(total: String) = editDraft { it.copy(total = total) }

    fun onTaxChange(tax: String) = editDraft { it.copy(tax = tax) }

    fun onTipChange(tip: String) = editDraft { it.copy(tip = tip) }

    /** "How many items?": the rows follow a number; an emptied field leaves them as they are. */
    fun onItemCountChange(text: String) {
        val typed = text.filter { it.isDigit() }.take(2)
        change { state ->
            val count = typed.toIntOrNull()
            state.copy(
                itemCountInput = typed,
                draft = if (count == null) state.draft else SplitExpense.withItemCount(state.draft, count),
            )
        }
    }

    fun onItemChange(index: Int, name: String, amount: String) = editDraft { SplitExpense.withItem(it, index, name, amount) }

    /** A line misread from a receipt, off the bill. */
    fun removeItem(index: Int) = change {
        val draft = SplitExpense.withoutItem(it.draft, index)
        it.copy(draft = draft, itemCountInput = draft.items.size.toString())
    }

    fun addItem() = change {
        val draft = SplitExpense.withItemCount(it.draft, it.draft.items.size + 1)
        it.copy(draft = draft, itemCountInput = draft.items.size.toString())
    }

    fun continueFromBill() {
        if (!_uiState.value.canContinueFromBill) return
        openPeople()
    }

    /** A photo, a picked image or a PDF: read on the phone, masked, then read by the AI. */
    fun onReceiptPicked(uri: String) {
        val repos = repositories ?: return
        if (_uiState.value.receiptStage == ReceiptStage.READING) return
        val started = generation
        update { it.copy(receiptStage = ReceiptStage.READING, receiptErrorKey = null, receiptCanRetry = false, unreadable = 0) }
        work = viewModelScope.launch {
            val text = try {
                ReceiptText.of(repos.reader.read(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A locked PDF, a format the reader cannot open, a photo with
                // no text: all one answer — nothing could be read from it.
                if (started == generation) receiptFailed(Strings.split_receipt_nothing, canRetry = false)
                return@launch
            }
            if (started != generation) return@launch
            if (text.isBlank()) return@launch receiptFailed(Strings.split_receipt_nothing, canRetry = false)
            receiptText = text
            sendReceipt(repos, text, started)
        }
    }

    /** The same receipt again, after consent or a failure worth another go. */
    fun retryReceipt() {
        val repos = repositories ?: return
        val text = receiptText ?: return
        if (_uiState.value.receiptStage == ReceiptStage.READING) return
        val started = generation
        update { it.copy(receiptStage = ReceiptStage.READING, receiptErrorKey = null, receiptCanRetry = false) }
        work = viewModelScope.launch { sendReceipt(repos, text, started) }
    }

    private suspend fun sendReceipt(repos: SplitRepositories, text: String, started: Int) {
        try {
            val read = repos.splits.readReceipt(text)
            if (started != generation) return
            val draft = SplitExpense.fromReceipt(_uiState.value.draft, read)
            if (SplitExpense.isEmpty(read, draft.mode)) return receiptFailed(Strings.split_receipt_nothing, canRetry = false)
            receiptText = null
            change {
                it.copy(
                    draft = draft,
                    receiptStage = ReceiptStage.READ,
                    unreadable = read.unreadable,
                    itemCountInput = draft.items.size.toString(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.ConsentRequired) {
            if (started != generation) return
            change { it.copy(receiptStage = ReceiptStage.PICK, aiOff = true) }
        } catch (e: ApiException) {
            if (started != generation) return
            receiptFailed(e.messageKey, canRetry = e is ApiException.Network || e is ApiException.Server || e is ApiException.ParseFailed)
        }
    }

    private fun receiptFailed(messageKey: String, canRetry: Boolean) {
        if (!canRetry) receiptText = null
        change { it.copy(receiptStage = ReceiptStage.PICK, receiptErrorKey = messageKey, receiptCanRetry = canRetry) }
    }

    /** "Retake": the receipt's figures go, and another can be chosen. */
    fun retake() {
        receiptText = null
        change { it.copy(draft = cleared(it.draft, true), receiptStage = ReceiptStage.PICK, receiptErrorKey = null, unreadable = 0, itemCountInput = "") }
    }

    /** Typing the bill in instead, when a receipt could not be read. */
    fun useManual() {
        receiptText = null
        change {
            it.copy(
                draft = SplitExpense.withSource(it.draft, SplitSource.MANUAL),
                receiptStage = ReceiptStage.PICK,
                receiptErrorKey = null,
                receiptCanRetry = false,
                itemCountInput = it.draft.items.size.takeIf { n -> n > 0 }?.toString().orEmpty(),
            )
        }
    }

    // ── AI off ──────────────────────────────────────────────────────────

    /** "Not now": nothing is read; the bill can still be typed in. */
    fun dismissAiOff() = update { it.copy(aiOff = false) }

    /** "Open Profile" from the sheet: it closes, and coming back reads the receipt again. */
    fun openProfileForAi() = update { it.copy(aiOff = false, readAfterProfile = true) }

    /** Back from Profile, where AI may have been turned on: the same receipt, read again. */
    fun retryAfterAi() {
        if (!_uiState.value.readAfterProfile) return
        update { it.copy(readAfterProfile = false) }
        retryReceipt()
    }

    // ── People ──────────────────────────────────────────────────────────

    fun loadContacts() {
        val repos = repositories ?: return
        val started = generation
        update { it.copy(contactsLoading = true, contactsErrorKey = null) }
        viewModelScope.launch {
            try {
                val contacts = repos.splits.contacts()
                if (started != generation) return@launch
                update { it.copy(contacts = contacts, contactsLoading = false, contactsLoaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // A refresh that fails keeps who it had.
                update { it.copy(contactsLoading = false, contactsErrorKey = e.messageKey) }
            }
        }
    }

    fun onSearchChange(text: String) = update { it.copy(search = text) }

    /** [contact] on the bill, or off it — and off every item it shared. */
    fun toggleContact(contact: SplitContact) = editDraft { draft ->
        val index = draft.people.indexOfFirst { it.contactId == contact.id }
        if (index > 0) SplitExpense.withoutPerson(draft, index) else SplitExpense.withPerson(draft, contact)
    }

    /** Person [index] off this bill — never the person themselves — and off every item they shared. */
    fun removePerson(index: Int) = editDraft { SplitExpense.withoutPerson(it, index) }

    fun openNewPerson(name: String = "", phone: String = "") = update { it.copy(newPerson = NewPersonDraft(name = name, phone = phone)) }

    fun onNewPersonName(name: String) = update { it.copy(newPerson = it.newPerson?.copy(name = name, errorKey = null)) }

    fun onNewPersonPhone(phone: String) = update { it.copy(newPerson = it.newPerson?.copy(phone = phone, errorKey = null)) }

    fun cancelNewPerson() = update { if (it.newPerson?.busy == true) it else it.copy(newPerson = null) }

    /** Saved once, for every bill after this one, and on this bill straight away. */
    fun saveNewPerson() {
        val repos = repositories ?: return
        val draft = _uiState.value.newPerson ?: return
        if (!draft.canSave) return
        val started = generation
        update { it.copy(newPerson = draft.copy(busy = true, errorKey = null)) }
        viewModelScope.launch {
            try {
                val contact = repos.splits.addContact(draft.name, draft.phone)
                if (started != generation) return@launch
                change { state ->
                    val contacts = (state.contacts.filterNot { it.id == contact.id } + contact).sortedBy { it.name.lowercase() }
                    // Already on the bill under its old name: replaced, not added twice.
                    val people = state.draft.people.map { if (it.contactId == contact.id) SplitPerson(contact.id, contact.name, contact.phone) else it }
                    state.copy(
                        contacts = contacts,
                        newPerson = null,
                        search = "",
                        draft = SplitExpense.withPerson(state.draft.copy(people = people), contact),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                update { it.copy(newPerson = it.newPerson?.copy(busy = false, errorKey = e.messageKey)) }
            }
        }
    }

    fun askDeleteContact(contact: SplitContact) = update { it.copy(confirmingContactDelete = contact) }

    fun cancelDeleteContact() = update { it.copy(confirmingContactDelete = null) }

    /** Gone from the saved people at once, and off this bill; put back if the server refuses. */
    fun deleteContact() {
        val repos = repositories ?: return
        val contact = _uiState.value.confirmingContactDelete ?: return
        val before = _uiState.value.contacts
        change { state ->
            val index = state.draft.people.indexOfFirst { it.contactId == contact.id }
            state.copy(
                contacts = before.filterNot { it.id == contact.id },
                confirmingContactDelete = null,
                draft = if (index > 0) SplitExpense.withoutPerson(state.draft, index) else state.draft,
            )
        }
        viewModelScope.launch {
            try {
                repos.splits.deleteContact(contact.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(contacts = before, contactsErrorKey = e.messageKey) }
            }
        }
    }

    fun continueFromPeople() {
        val state = _uiState.value
        if (!state.canContinueFromPeople) return
        when {
            state.peopleReturnsToStart -> back()
            state.byItems -> push(SplitStep.ASSIGN)
            else -> push(SplitStep.REVIEW)
        }
    }

    // ── Assigning items ─────────────────────────────────────────────────

    fun toggleAssignee(item: Int, person: Int) = editDraft { SplitExpense.toggled(it, item, person) }

    fun assignEveryone(item: Int) = editDraft { SplitExpense.everyone(it, item) }

    fun continueFromAssign() {
        if (_uiState.value.assignBlock == null) push(SplitStep.REVIEW)
    }

    // ── The review ──────────────────────────────────────────────────────

    /** Saves the split as `toSave` builds it; the review then shows the server's shares. */
    fun save() {
        val repos = repositories ?: return
        val state = _uiState.value
        if (!state.canSave) return
        val started = generation
        val body = SplitExpense.toSave(state.draft, state.currency)
        update { it.copy(saving = true, saveErrorKey = null) }
        viewModelScope.launch {
            try {
                val split = repos.splits.create(body)
                if (started != generation) return@launch
                // The bill is done: back from here is the history, and then a fresh start.
                change {
                    it.copy(
                        stack = listOf(SplitStep.START, SplitStep.HISTORY, SplitStep.REVIEW),
                        draft = SplitExpense.start(today().toString()),
                        itemCountInput = "",
                        receiptStage = ReceiptStage.PICK,
                        unreadable = 0,
                        saving = false,
                        savedId = split.id,
                        saved = split,
                        historyLoaded = false,
                    )
                }
                loadHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                update { it.copy(saving = false, saveErrorKey = e.messageKey) }
            }
        }
    }

    /** Paid, or not paid after all — the server answers with the split as it now stands. */
    fun toggleSettled(participantId: String) {
        val repos = repositories ?: return
        val state = _uiState.value
        val split = state.saved ?: return
        val participant = state.participant(participantId) ?: return
        if (participant.isSelf || participantId in state.settling) return
        update { it.copy(settling = it.settling + participantId, settleErrorKey = null) }
        viewModelScope.launch {
            try {
                val updated = repos.splits.settle(split.id, participantId, !participant.settled)
                update { it.copy(saved = if (it.savedId == updated.id) updated else it.saved, settling = it.settling - participantId) }
                // The history's "still to come" changed with it.
                update { it.copy(historyLoaded = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(settling = it.settling - participantId, settleErrorKey = e.messageKey) }
            }
        }
    }

    /** WhatsApp would not open for [participantId]: offer their message to copy instead. */
    fun noWhatsApp(participantId: String) = update { it.copy(noWhatsAppFor = participantId) }

    fun dismissNoWhatsApp() = update { it.copy(noWhatsAppFor = null) }

    fun retryOpen() {
        _uiState.value.savedId?.let { loadSplit(it) }
    }

    private fun loadSplit(id: String) {
        val repos = repositories ?: return
        val started = generation
        update { it.copy(openingSplit = true, openErrorKey = null) }
        viewModelScope.launch {
            try {
                val split = repos.splits.split(id)
                if (started != generation || _uiState.value.savedId != id) return@launch
                update { it.copy(saved = split, openingSplit = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation || _uiState.value.savedId != id) return@launch
                update { it.copy(openingSplit = false, openErrorKey = e.messageKey) }
            }
        }
    }

    // ── The history ─────────────────────────────────────────────────────

    fun loadHistory() {
        val repos = repositories ?: return
        val started = generation
        update { it.copy(historyLoading = true, historyErrorKey = null) }
        viewModelScope.launch {
            try {
                val list = repos.splits.splits()
                if (started != generation) return@launch
                update { it.copy(history = list, historyLoading = false, historyLoaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                update { it.copy(historyLoading = false, historyErrorKey = e.messageKey) }
            }
        }
    }

    /** One split from the history, opened to its review with the server's shares. */
    fun openSplit(id: String) {
        change { it.copy(stack = it.stack + SplitStep.REVIEW, savedId = id, saved = null) }
        loadSplit(id)
    }

    fun askDelete(summary: SplitSummary) = update { it.copy(confirmingDelete = summary) }

    fun cancelDelete() = update { if (it.deleting) it else it.copy(confirmingDelete = null) }

    fun deleteSplit() {
        val repos = repositories ?: return
        val summary = _uiState.value.confirmingDelete ?: return
        if (_uiState.value.deleting) return
        update { it.copy(deleting = true, historyErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.splits.delete(summary.id)
                update { it.copy(deleting = false, confirmingDelete = null, history = it.history.filterNot { s -> s.id == summary.id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(deleting = false, confirmingDelete = null, historyErrorKey = e.messageKey) }
            }
        }
    }

    fun onHistoryShown() {
        val state = _uiState.value
        if (!state.historyLoaded && !state.historyLoading) loadHistory()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** The draft without what a receipt put in it; the name and date the person may have typed stay. */
    private fun cleared(draft: SplitDraft, receiptFigures: Boolean): SplitDraft = if (receiptFigures) {
        draft.copy(items = emptyList(), total = "", tax = "", tip = "")
    } else {
        draft
    }

    private fun editDraft(edit: (SplitDraft) -> SplitDraft) = change { it.copy(draft = edit(it.draft)) }

    private fun update(edit: (SplitUiState) -> SplitUiState) = _uiState.update(edit)

    /** A change worth keeping across the process being reclaimed: the draft, the step, the split open. */
    private fun change(edit: (SplitUiState) -> SplitUiState) {
        _uiState.update(edit)
        store()
    }

    private fun store() {
        val state = _uiState.value
        val draft = state.draft
        saved[KEY_STACK] = ArrayList(state.stack.map { it.name })
        saved[KEY_SAVED_ID] = state.savedId
        saved[KEY_RECEIPT] = state.receiptStage.name
        saved[KEY_UNREADABLE] = state.unreadable
        saved[KEY_ITEM_COUNT] = state.itemCountInput
        saved[KEY_SOURCE] = draft.source?.name
        saved[KEY_MODE] = draft.mode?.name
        saved[KEY_TITLE] = draft.title
        saved[KEY_DATE] = draft.occurredOn
        saved[KEY_TOTAL] = draft.total
        saved[KEY_TAX] = draft.tax
        saved[KEY_TIP] = draft.tip
        saved[KEY_ITEM_NAMES] = ArrayList(draft.items.map { it.name })
        saved[KEY_ITEM_AMOUNTS] = ArrayList(draft.items.map { it.amount })
        saved[KEY_ITEM_WHO] = ArrayList(draft.items.map { item -> item.assignees.sorted().joinToString(",") })
        val others = draft.people.drop(1)
        saved[KEY_PEOPLE_IDS] = ArrayList(others.map { it.contactId.orEmpty() })
        saved[KEY_PEOPLE_NAMES] = ArrayList(others.map { it.name })
        saved[KEY_PEOPLE_PHONES] = ArrayList(others.map { it.phone.orEmpty() })
    }

    private fun clearSaved() {
        saved.keys().filter { it.startsWith(PREFIX) && it != KEY_OWNER }.forEach { saved.remove<Any>(it) }
    }

    /** What the last process left, or a fresh start. Kept only if the bind finds the same person. */
    private fun restored(): SplitUiState {
        val fresh = SplitUiState(draft = SplitExpense.start(today().toString()))
        if (saved.get<String>(KEY_OWNER) == null) return fresh
        val names = saved.get<ArrayList<String>>(KEY_ITEM_NAMES).orEmpty()
        val amounts = saved.get<ArrayList<String>>(KEY_ITEM_AMOUNTS).orEmpty()
        val who = saved.get<ArrayList<String>>(KEY_ITEM_WHO).orEmpty()
        val ids = saved.get<ArrayList<String>>(KEY_PEOPLE_IDS).orEmpty()
        val peopleNames = saved.get<ArrayList<String>>(KEY_PEOPLE_NAMES).orEmpty()
        val phones = saved.get<ArrayList<String>>(KEY_PEOPLE_PHONES).orEmpty()
        val people = listOf(SplitPerson(null, "", null)) + ids.mapIndexedNotNull { i, id ->
            id.takeIf { it.isNotEmpty() }?.let { SplitPerson(it, peopleNames.getOrElse(i) { "" }, phones.getOrNull(i)?.ifEmpty { null }) }
        }
        val items = names.mapIndexed { i, name ->
            SplitItemDraft(
                name = name,
                amount = amounts.getOrElse(i) { "" },
                assignees = who.getOrNull(i).orEmpty().split(",").mapNotNull { it.toIntOrNull() }.filter { it in people.indices }.toSet(),
            )
        }
        val draft = SplitDraft(
            source = saved.get<String>(KEY_SOURCE)?.let { name -> SplitSource.entries.firstOrNull { it.name == name } },
            mode = saved.get<String>(KEY_MODE)?.let { name -> SplitMode.entries.firstOrNull { it.name == name } },
            title = saved.get<String>(KEY_TITLE).orEmpty(),
            occurredOn = saved.get<String>(KEY_DATE)?.ifBlank { null } ?: fresh.draft.occurredOn,
            total = saved.get<String>(KEY_TOTAL).orEmpty(),
            items = items,
            tax = saved.get<String>(KEY_TAX).orEmpty(),
            tip = saved.get<String>(KEY_TIP).orEmpty(),
            people = people,
        )
        val stack = saved.get<ArrayList<String>>(KEY_STACK).orEmpty()
            .mapNotNull { name -> SplitStep.entries.firstOrNull { it.name == name } }
            .ifEmpty { listOf(SplitStep.START) }
            .let { if (it.first() == SplitStep.START) it else listOf(SplitStep.START) + it }
        val savedId = saved.get<String>(KEY_SAVED_ID)
        // A read cut off by the process ending is not resumed: the receipt is chosen again.
        val stage = saved.get<String>(KEY_RECEIPT)?.let { name -> ReceiptStage.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != ReceiptStage.READING } ?: ReceiptStage.PICK
        return fresh.copy(
            // A review of a split that is not known is no review: back to where it came from.
            stack = if (savedId == null && stack.last() == SplitStep.REVIEW && draft.mode == null) stack.dropLast(1) else stack,
            draft = draft,
            savedId = savedId,
            receiptStage = stage,
            unreadable = saved.get<Int>(KEY_UNREADABLE) ?: 0,
            itemCountInput = saved.get<String>(KEY_ITEM_COUNT).orEmpty(),
        )
    }

    private companion object {
        const val PREFIX = "split_"
        const val KEY_OWNER = "split_owner"
        const val KEY_STACK = "split_stack"
        const val KEY_SAVED_ID = "split_saved_id"
        const val KEY_RECEIPT = "split_receipt"
        const val KEY_UNREADABLE = "split_unreadable"
        const val KEY_ITEM_COUNT = "split_item_count"
        const val KEY_SOURCE = "split_source"
        const val KEY_MODE = "split_mode"
        const val KEY_TITLE = "split_title"
        const val KEY_DATE = "split_date"
        const val KEY_TOTAL = "split_total"
        const val KEY_TAX = "split_tax"
        const val KEY_TIP = "split_tip"
        const val KEY_ITEM_NAMES = "split_item_names"
        const val KEY_ITEM_AMOUNTS = "split_item_amounts"
        const val KEY_ITEM_WHO = "split_item_who"
        const val KEY_PEOPLE_IDS = "split_people_ids"
        const val KEY_PEOPLE_NAMES = "split_people_names"
        const val KEY_PEOPLE_PHONES = "split_people_phones"
    }
}
