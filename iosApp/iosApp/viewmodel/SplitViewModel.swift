import Foundation
import SharedLogic
import UIKit

/**
 Split expense, from the first choice to the saved split and its messages,
 and the splits made before. Android's `SplitViewModel`.

 Every rule and every word is shared `SplitExpense`'s. Swift cannot `copy()`
 the Kotlin draft, so the structural parts — where the figures come from, how
 it is split, the items and the people — are only ever replaced with what
 `SplitExpense` returns, and the typed fields (what it was for, the date, the
 total, tax and tip) live here as strings and are put into the draft whenever
 a shared function reads it.

 The receipt is read on the phone by the statement reader (Vision / PDFKit);
 only its masked text (`ReceiptText`) is sent. Every share shown after saving,
 and every WhatsApp message, is the saved split's — the server's figures.

 The draft is not kept for the scene: the app opening again goes Home
 (RootView), so a restored draft would never be seen.
 */
@MainActor
final class SplitViewModel: ObservableObject {

    /// Where the person is in the flow. Back pops one.
    enum Step: Equatable { case start, bill, people, assign, review, history }

    /// The receipt, from choosing it to what it said.
    enum Reading: Equatable {
        case choosing
        case reading
        case read
        /// Why it could not be read, and whether sending the same text again could help.
        case failed(messageKey: String, retryable: Bool)
    }

    @Published private(set) var steps: [Step] = [.start]

    // MARK: The draft
    /// The draft as shared last returned it. Its typed fields are the strings below.
    @Published private(set) var held: SplitDraft
    @Published var title = "" { didSet { saveErrorKey = nil } }
    @Published var occurredOn = "" { didSet { saveErrorKey = nil } }
    @Published var total = "" { didSet { saveErrorKey = nil } }
    @Published var tax = "" { didSet { saveErrorKey = nil } }
    @Published var tip = "" { didSet { saveErrorKey = nil } }

    @Published private(set) var currency = ""
    @Published private(set) var locale = "en"

    // MARK: The receipt
    @Published private(set) var reading: Reading = .choosing
    /// Lines the AI offered that are not on the receipt, and were left out.
    @Published private(set) var unreadable = 0
    /// The masked text last read, so a retry or consent does not read the photo again.
    private var receiptText: String?
    /// What the receipt said, so changing how it is split takes what that way needs from it.
    private var lastRead: ReceiptRead?

    // MARK: Consent, asked for when reading a receipt needs it
    @Published private(set) var consentAsked = false
    @Published private(set) var policy: AiPolicy?
    /// Unticked until the person ticks it — express consent, nothing pre-ticked.
    @Published var consentTicked = false
    @Published private(set) var consentBusy = false
    @Published private(set) var consentErrorKey: String?

    // MARK: Saved people
    @Published private(set) var contacts: [SplitContact] = []
    @Published private(set) var contactsLoading = false
    @Published private(set) var contactsLoaded = false
    @Published private(set) var contactsErrorKey: String?
    @Published var search = ""
    @Published var addingPerson = false
    @Published var personName = "" { didSet { personErrorKey = nil } }
    @Published var personPhone = "" { didSet { personErrorKey = nil } }
    @Published private(set) var personSaving = false
    @Published private(set) var personErrorKey: String?
    /// A number picked from the phone's contacts, being saved.
    @Published private(set) var pickingSaving = false

    // MARK: Saving, and the saved split
    @Published private(set) var saving = false
    @Published private(set) var saveErrorKey: String?
    @Published private(set) var saved: Split?
    /// Opened from Your splits rather than made just now: back goes to the list.
    @Published private(set) var savedFromHistory = false
    @Published private(set) var openingSplit = false
    @Published private(set) var settlingId: String?
    @Published private(set) var reviewErrorKey: String?
    /// The person WhatsApp could not be opened for; their message can be copied instead.
    @Published var noWhatsAppFor: SplitParticipant?
    @Published private(set) var copied = false

    // MARK: Your splits
    @Published private(set) var history: [SplitSummary] = []
    @Published private(set) var historyLoading = false
    @Published private(set) var historyLoaded = false
    @Published private(set) var historyErrorKey: String?
    @Published var confirmingDeleteId: String?

    private var splitRepository: SplitRepository?
    private var consentRepository: AiConsentRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private let reader = IOSStatementReader()
    private var work: Task<Void, Never>?
    private var owner: String?
    /// Rises on every bind and every receipt, so a late answer cannot land on another.
    private var generation = 0

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    init() {
        held = SplitExpense.shared.start(today: ManualEntry.shared.today().description())
        occurredOn = held.occurredOn
    }

    // MARK: Binding

    func bind(userId: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
        }
        guard splitRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        splitRepository = KtorSplitRepository(baseUrl: base, tokens: tokens, logging: logging)
        consentRepository = KtorAiConsentRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        loadCapabilities()
        loadContacts()
        if step == .history { loadHistory() }
    }

    func unbind() {
        work?.cancel()
        work = nil
        splitRepository?.close()
        consentRepository?.close()
        capabilitiesRepository?.close()
        splitRepository = nil
        consentRepository = nil
        capabilitiesRepository = nil
        ReceiptFiles.clear()
    }

    deinit {
        splitRepository?.close()
        consentRepository?.close()
        capabilitiesRepository?.close()
    }

    private func reset() {
        unbind()
        generation += 1
        contacts = []
        contactsLoading = false
        contactsLoaded = false
        contactsErrorKey = nil
        history = []
        historyLoading = false
        historyLoaded = false
        historyErrorKey = nil
        startOver()
    }

    /// A new split, at the start. Saved people and the history stay.
    private func startOver() {
        work?.cancel()
        generation += 1
        steps = [.start]
        apply(SplitExpense.shared.start(today: ManualEntry.shared.today().description()))
        reading = .choosing
        unreadable = 0
        receiptText = nil
        lastRead = nil
        ReceiptFiles.clear()
        clearConsent()
        search = ""
        cancelAddPerson()
        pickingSaving = false
        saving = false
        saveErrorKey = nil
        saved = nil
        savedFromHistory = false
        openingSplit = false
        settlingId = nil
        reviewErrorKey = nil
        noWhatsAppFor = nil
        copied = false
        confirmingDeleteId = nil
    }

    /// Leaving the flow: what was being made is let go.
    func close() {
        startOver()
        unbind()
    }

    private func loadCapabilities() {
        guard let capabilitiesRepository else { return }
        Task {
            // The currency and locale every figure is written in.
            guard let found = try? await capabilitiesRepository.fetch() else { return }
            if !found.currency.isEmpty { currency = found.currency }
            if !found.locale.isEmpty { locale = found.locale }
        }
    }

    // MARK: The draft

    /// The draft with what was typed in it — what every shared function reads.
    var draft: SplitDraft {
        SplitDraft(
            source: held.source,
            mode: held.mode,
            title: title,
            occurredOn: occurredOn,
            total: total,
            items: held.items,
            tax: tax,
            tip: tip,
            people: held.people
        )
    }

    /// What shared returned, kept whole: the typed fields follow it.
    private func apply(_ next: SplitDraft) {
        held = next
        if title != next.title { title = next.title }
        if occurredOn != next.occurredOn { occurredOn = next.occurredOn }
        if total != next.total { total = next.total }
        if tax != next.tax { tax = next.tax }
        if tip != next.tip { tip = next.tip }
        saveErrorKey = nil
    }

    var words: SplitExpense.Words { SplitExpense.Words(currency: currency, locale: locale) }

    var symbol: String { Money.shared.symbol(currency: currency) }

    var amountPlaceholder: String {
        Money.shared.normalize(raw: "0", fractionDigits: Money.shared.fractionDigits(currency: currency)) ?? ""
    }

    func money(_ amount: String) -> String { words.money(amount: amount) }

    var source: SplitSource? { held.source }
    var mode: SplitMode? { held.mode }
    var people: [SplitPerson] { held.people }
    var items: [SplitItemDraft] { held.items }

    func label(_ person: SplitPerson) -> String { SplitExpense.shared.label(person: person, locale: locale) }

    func initials(_ person: SplitPerson) -> String { SplitExpense.shared.initials(person: person, locale: locale) }

    var dateLabel: String? { Dates.shared.parse(iso: occurredOn).map { Dates.shared.display(date: $0) } }

    var occurredDate: Kotlinx_datetimeLocalDate? { Dates.shared.parse(iso: occurredOn) }

    var today: Kotlinx_datetimeLocalDate { ManualEntry.shared.today() }

    func chooseDate(_ date: Kotlinx_datetimeLocalDate) { occurredOn = date.description() }

    // MARK: The steps

    var step: Step { steps.last ?? .start }

    /// Where the step below this one is, for People opened from the start.
    private var previous: Step? { steps.count > 1 ? steps[steps.count - 2] : nil }

    var peopleFromStart: Bool { step == .people && previous == .start }

    /// "Step 2 of 4": the bill, the people, the items when split by them, and the review.
    var stepNumber: Int? {
        switch step {
        case .bill: 1
        case .people: peopleFromStart ? nil : 2
        case .assign: 3
        case .review: savedFromHistory ? nil : stepCount
        default: nil
        }
    }

    var stepCount: Int { mode == .items ? 4 : 3 }

    private func push(_ next: Step) { steps.append(next) }

    /// One step back. False when there is nowhere to go back to inside the flow,
    /// and it should close: from the start, or from a split just saved — going
    /// back into its draft would make it twice.
    func back() -> Bool {
        switch step {
        case .start:
            return false
        case .review where saved != nil && !savedFromHistory:
            return false
        case .review where savedFromHistory:
            saved = nil
            savedFromHistory = false
            reviewErrorKey = nil
            loadHistory()
        case .bill where reading == .reading:
            work?.cancel()
            generation += 1
            reading = .choosing
            ReceiptFiles.clear()
        default:
            break
        }
        steps.removeLast()
        return true
    }

    // MARK: Start

    func chooseSource(_ source: SplitSource) { apply(SplitExpense.shared.withSource(draft: draft, source: source)) }

    func chooseMode(_ mode: SplitMode) {
        let changed = self.mode != mode
        apply(SplitExpense.shared.withMode(draft: draft, mode: mode))
        // Read for the other way: equally takes its total, by items its items.
        if changed, source == .receipt, reading == .read, let lastRead {
            apply(SplitExpense.shared.fromReceipt(draft: draft, read: lastRead))
        }
    }

    var canContinueFromStart: Bool { source != nil && mode != nil }

    func continueFromStart() {
        guard canContinueFromStart else { return }
        push(.bill)
    }

    func openPeople() { push(.people) }

    func openHistory() {
        push(.history)
        loadHistory()
    }

    // MARK: The bill

    /**
     Why the bill itself is not ready — its title, total or items — leaving out
     the people and who had what, which the next steps ask for. The one shared
     rule, read as if those were answered: someone else on the bill and every
     item everyone's, both through `SplitExpense`.
     */
    var billBlock: SplitBlock? {
        SplitExpense.shared.billBlockingReason(draft: draft, currency: currency)
    }

    /// Said once something has been entered: a blank form is not wrong yet.
    var billNotice: String? {
        guard let block = billBlock, !billUntouched else { return nil }
        return SplitExpense.shared.blockText(block: block, locale: locale)
    }

    private var billUntouched: Bool {
        title.isEmpty && total.isEmpty && items.allSatisfy { $0.name.isEmpty && $0.amount.isEmpty }
    }

    var canContinueFromBill: Bool {
        if source == .receipt && reading != .read { return false }
        return billBlock == nil
    }

    func continueFromBill() {
        guard canContinueFromBill else { return }
        push(.people)
    }

    /// The receipt could not be read, or the person would rather type it: the
    /// same bill, entered by hand.
    func switchToManual() {
        work?.cancel()
        generation += 1
        receiptText = nil
        lastRead = nil
        ReceiptFiles.clear()
        reading = .choosing
        apply(SplitExpense.shared.withSource(draft: draft, source: .manual))
    }

    func setItemCount(_ count: Int) { apply(SplitExpense.shared.withItemCount(draft: draft, count: Int32(count))) }

    func addItem() { setItemCount(items.count + 1) }

    /// A line misread from a receipt, off the bill.
    func removeItem(_ index: Int) { apply(SplitExpense.shared.withoutItem(draft: draft, index: Int32(index))) }

    var canAddItem: Bool { items.count < Int(SplitExpense.shared.MAX_ITEMS) }

    func itemName(_ index: Int) -> String { items.indices.contains(index) ? items[index].name : "" }

    func itemAmount(_ index: Int) -> String { items.indices.contains(index) ? items[index].amount : "" }

    func setItemName(_ index: Int, _ name: String) {
        guard items.indices.contains(index) else { return }
        apply(SplitExpense.shared.withItem(draft: draft, index: Int32(index), name: name, amount: items[index].amount))
    }

    func setItemAmount(_ index: Int, _ amount: String) {
        guard items.indices.contains(index) else { return }
        apply(SplitExpense.shared.withItem(draft: draft, index: Int32(index), name: items[index].name, amount: amount))
    }

    // MARK: The receipt

    /// A photo or file to read. Read here; only its masked text leaves the phone.
    func receiptPicked(_ url: URL) {
        work?.cancel()
        generation += 1
        let started = generation
        reading = .reading
        unreadable = 0
        receiptText = nil
        lastRead = nil
        work = Task { [weak self] in
            guard let self else { return }
            do {
                let document = try await self.reader.read(url: url, password: nil)
                ReceiptFiles.clear()
                guard started == self.generation else { return }
                let text = ReceiptText.shared.of(document: document)
                self.receiptText = text
                self.send(text)
            } catch let error as IOSStatementReader.ReadError {
                ReceiptFiles.clear()
                guard started == self.generation else { return }
                self.reading = .failed(messageKey: Self.readKey(error), retryable: false)
            } catch {
                ReceiptFiles.clear()
                guard started == self.generation, !Task.isCancelled else { return }
                self.reading = .failed(messageKey: Strings.shared.split_receipt_nothing, retryable: false)
            }
        }
    }

    private static func readKey(_ error: IOSStatementReader.ReadError) -> String {
        switch error {
        case .tooManyPages: Strings.shared.split_error_receipt_long
        case .unsupported, .passwordRequired: Strings.shared.statement_unsupported
        case .nothingReadable: Strings.shared.split_receipt_nothing
        }
    }

    private func send(_ text: String) {
        guard let splitRepository else { return }
        let started = generation
        reading = .reading
        work = Task { [weak self] in
            guard let self else { return }
            do {
                let read = try await splitRepository.readReceipt(text: text)
                guard started == self.generation else { return }
                // Nothing at all came back: nothing to check, so nothing to show.
                if SplitExpense.shared.isEmpty(read: read, mode: self.draft.mode) {
                    self.reading = .failed(messageKey: Strings.shared.split_receipt_nothing, retryable: false)
                    return
                }
                self.apply(SplitExpense.shared.fromReceipt(draft: self.draft, read: read))
                self.lastRead = read
                self.unreadable = Int(read.unreadable)
                self.reading = .read
            } catch {
                guard started == self.generation, !Self.cancelled(error) else { return }
                let kotlin = Self.kotlin(error)
                if kotlin is ApiException.ConsentRequired {
                    self.reading = .failed(messageKey: Strings.shared.import_consent_required, retryable: true)
                    self.askForConsent()
                    return
                }
                let retryable = kotlin is ApiException.Network || kotlin is ApiException.Server
                self.reading = .failed(messageKey: Self.messageKey(error), retryable: retryable)
            }
        }
    }

    /// The same text again — after consent, or a network failure.
    func retryReceipt() {
        guard let receiptText else {
            reading = .choosing
            return
        }
        generation += 1
        send(receiptText)
    }

    /// Back to choosing a photo or file.
    func retake() {
        work?.cancel()
        generation += 1
        receiptText = nil
        lastRead = nil
        unreadable = 0
        ReceiptFiles.clear()
        reading = .choosing
    }

    // MARK: Consent

    private func askForConsent(errorKey: String? = nil) {
        guard let consentRepository else { return }
        consentAsked = true
        policy = nil
        consentTicked = false
        consentBusy = true
        consentErrorKey = errorKey
        Task {
            do {
                policy = try await consentRepository.policy()
                consentBusy = false
            } catch {
                guard !Self.cancelled(error) else { return }
                consentBusy = false
                consentErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Records consent to the version shown, then reads the receipt it was asked for.
    func agree() {
        guard let consentRepository, let policy, consentTicked, !consentBusy else { return }
        consentBusy = true
        consentErrorKey = nil
        Task {
            do {
                try await consentRepository.consent(version: policy.version)
                clearConsent()
                retryReceipt()
            } catch {
                guard !Self.cancelled(error) else { return }
                if let changed = Self.kotlin(error) as? ApiException.AiPolicyChanged {
                    // The policy moved on while it was open: show the new one to agree to.
                    askForConsent(errorKey: changed.messageKey)
                    return
                }
                consentBusy = false
                consentErrorKey = Self.messageKey(error)
            }
        }
    }

    /// "Not now": the receipt waits, and the bill can still be typed in.
    func declineConsent() { clearConsent() }

    private func clearConsent() {
        consentAsked = false
        policy = nil
        consentTicked = false
        consentBusy = false
        consentErrorKey = nil
    }

    // MARK: People

    func loadContacts() {
        guard let splitRepository else { return }
        contactsLoading = !contactsLoaded
        contactsErrorKey = nil
        Task {
            do {
                let list = try await splitRepository.contacts()
                contacts = list
                contactsLoading = false
                contactsLoaded = true
            } catch {
                guard !Self.cancelled(error) else { return }
                // A refresh that fails keeps what it had.
                contactsLoading = false
                contactsErrorKey = Self.messageKey(error)
            }
        }
    }

    /// The saved people whose name or number has what was typed.
    var shownContacts: [SplitContact] {
        contacts.filter { SplitExpense.shared.matches(contact: $0, query: search) }
    }

    var contactsEmpty: Bool { contactsLoaded && contacts.isEmpty && contactsErrorKey == nil }

    private func index(of contact: SplitContact) -> Int? { people.firstIndex { $0.contactId == contact.id } }

    func isChosen(_ contact: SplitContact) -> Bool { index(of: contact) != nil }

    /// Everyone on the bill but the person themselves.
    var others: Int { max(people.count - 1, 0) }

    var atPeopleLimit: Bool { people.count >= Int(SplitExpense.shared.MAX_PEOPLE) }

    func toggle(_ contact: SplitContact) {
        if let index = index(of: contact) {
            apply(SplitExpense.shared.withoutPerson(draft: draft, index: Int32(index)))
        } else {
            apply(SplitExpense.shared.withPerson(draft: draft, contact: contact))
        }
    }

    /// Off the bill from the start's row — never the person themselves.
    func remove(personAt index: Int) {
        apply(SplitExpense.shared.withoutPerson(draft: draft, index: Int32(index)))
    }

    var canContinueFromPeople: Bool { people.count >= 2 }

    func continueFromPeople() {
        guard canContinueFromPeople else { return }
        if peopleFromStart {
            steps.removeLast()
        } else {
            push(mode == .items ? .assign : .review)
        }
    }

    func startAddPerson(name: String = "", phone: String = "", errorKey: String? = nil) {
        personName = name
        personPhone = phone
        personErrorKey = errorKey
        personSaving = false
        addingPerson = true
    }

    func cancelAddPerson() {
        addingPerson = false
        personName = ""
        personPhone = ""
        personSaving = false
        personErrorKey = nil
    }

    var canSavePerson: Bool {
        !personSaving
            && !personName.trimmingCharacters(in: .whitespaces).isEmpty
            && !personPhone.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Saved for next time, and on this bill.
    func savePerson() {
        guard let splitRepository, canSavePerson else { return }
        personSaving = true
        personErrorKey = nil
        let name = personName
        let phone = personPhone
        Task {
            do {
                let contact = try await splitRepository.addContact(name: name, phone: phone)
                adopt(contact)
                cancelAddPerson()
            } catch {
                guard !Self.cancelled(error) else { return }
                personSaving = false
                personErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Someone chosen from the phone's contacts: saved straight away. A number
    /// the server will not take opens the form with it, to correct.
    func pickedFromContacts(name: String, phone: String) {
        guard let splitRepository else { return }
        pickingSaving = true
        contactsErrorKey = nil
        Task {
            do {
                let contact = try await splitRepository.addContact(name: name, phone: phone)
                pickingSaving = false
                adopt(contact)
            } catch {
                guard !Self.cancelled(error) else { return }
                pickingSaving = false
                startAddPerson(name: name, phone: phone, errorKey: Self.messageKey(error))
            }
        }
    }

    /// A saved person into the list (the same number again only renames) and onto the bill.
    private func adopt(_ contact: SplitContact) {
        if let at = contacts.firstIndex(where: { $0.id == contact.id }) {
            contacts[at] = contact
        } else {
            contacts.append(contact)
            contacts.sort { $0.name.lowercased() < $1.name.lowercased() }
        }
        if !isChosen(contact) { apply(SplitExpense.shared.withPerson(draft: draft, contact: contact)) }
    }

    /// Gone at once, from the list and the bill; put back if the server refuses.
    /// The saved person a delete is waiting on: asked first, whichever way it was started.
    @Published var confirmingContact: SplitContact?

    func deleteContact(_ contact: SplitContact) {
        guard let splitRepository else { return }
        let before = contacts
        contacts = before.filter { $0.id != contact.id }
        if let index = index(of: contact) { apply(SplitExpense.shared.withoutPerson(draft: draft, index: Int32(index))) }
        Task {
            do {
                try await splitRepository.deleteContact(id: contact.id)
            } catch {
                guard !Self.cancelled(error) else { return }
                contacts = before
                contactsErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: Who had what

    func isAssigned(item index: Int, person: Int) -> Bool {
        guard items.indices.contains(index) else { return false }
        return items[index].assignees.contains { $0.intValue == person }
    }

    func isEveryone(item index: Int) -> Bool {
        guard items.indices.contains(index) else { return false }
        return people.indices.allSatisfy { isAssigned(item: index, person: $0) }
    }

    func toggle(item index: Int, person: Int) {
        apply(SplitExpense.shared.toggled(draft: draft, index: Int32(index), person: Int32(person)))
    }

    func everyone(item index: Int) { apply(SplitExpense.shared.everyone(draft: draft, index: Int32(index))) }

    var billTotal: String? { SplitExpense.shared.total(draft: draft, currency: currency) }

    /// What the unassigned items come to, as a figure; nil when every item has someone.
    var unassignedAmount: String? { SplitExpense.shared.unassigned(draft: draft, currency: currency) }

    /// What the items with someone on them come to (shared, as Android has it).
    var assignedAmount: String? { SplitExpense.shared.assigned(draft: draft, currency: currency) }

    /// Each person's share as it would be saved, formatted; nil while the figures are incomplete.
    var previewShares: [String]? {
        SplitExpense.shared.preview(draft: draft, currency: currency).map { $0.map { money($0) } }
    }

    var canContinueFromAssign: Bool { SplitExpense.shared.blockingReason(draft: draft, currency: currency) == nil }

    func continueFromAssign() {
        guard canContinueFromAssign else { return }
        push(.review)
    }

    // MARK: Review and save

    /// Why Save is off, or nil. The one shared rule (CLAUDE.md → Blocking reasons).
    var saveBlock: SplitBlock? { SplitExpense.shared.blockingReason(draft: draft, currency: currency) }

    var saveNotice: String? { saveBlock.map { SplitExpense.shared.blockText(block: $0, locale: locale) } }

    var canSave: Bool { !saving && saveBlock == nil }

    func save() {
        guard let splitRepository, canSave else { return }
        let toSave = SplitExpense.shared.toSave(draft: draft, currency: currency)
        saving = true
        saveErrorKey = nil
        Task {
            do {
                saved = try await splitRepository.create(split: toSave)
                saving = false
                savedFromHistory = false
                // The list is read again next time it is opened.
                historyLoaded = false
            } catch {
                guard !Self.cancelled(error) else { return }
                saving = false
                saveErrorKey = Self.messageKey(error)
            }
        }
    }

    /// The words for the saved split: in its own currency.
    var savedWords: SplitExpense.Words {
        let own = saved?.currency ?? ""
        return SplitExpense.Words(currency: own.isEmpty ? currency : own, locale: locale)
    }

    var shareRows: [SplitShareRow] {
        guard let saved else { return [] }
        return SplitExpense.shared.shareRows(split: saved, words: savedWords)
    }

    var groupMessage: String? { saved.map { SplitExpense.shared.groupMessage(split: $0, words: savedWords) } }

    func participant(at index: Int) -> SplitParticipant? {
        guard let saved, saved.participants.indices.contains(index) else { return nil }
        return saved.participants[index]
    }

    func message(for participant: SplitParticipant) -> String? {
        saved.map { SplitExpense.shared.message(split: $0, participant: participant, words: savedWords) }
    }

    func whatsAppURL(for participant: SplitParticipant) -> URL? {
        guard let message = message(for: participant),
              let link = SplitExpense.shared.whatsAppLink(phone: participant.phone, message: message) else { return nil }
        return URL(string: link)
    }

    /// WhatsApp, opened with their message typed in; they press send. Only the
    /// app itself — without it, the message can be copied instead.
    func sendOnWhatsApp(_ participant: SplitParticipant) {
        guard let url = whatsAppURL(for: participant) else { return }
        UIApplication.shared.open(url, options: [.universalLinksOnly: true]) { [weak self] opened in
            Task { @MainActor [weak self] in
                if !opened { self?.noWhatsAppFor = participant }
            }
        }
    }

    func copy(_ text: String) {
        UIPasteboard.general.string = text
        copied = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            copied = false
        }
    }

    func copyGroupMessage() {
        if let groupMessage { copy(groupMessage) }
    }

    func settle(_ participant: SplitParticipant) {
        guard let splitRepository, let saved, settlingId == nil else { return }
        settlingId = participant.id
        reviewErrorKey = nil
        Task {
            do {
                self.saved = try await splitRepository.settle(splitId: saved.id, participantId: participant.id, settled: !participant.settled)
                settlingId = nil
                historyLoaded = false
            } catch {
                guard !Self.cancelled(error) else { return }
                settlingId = nil
                reviewErrorKey = Self.messageKey(error)
            }
        }
    }

    var savedDateLabel: String? {
        guard let saved else { return nil }
        return Dates.shared.parse(iso: saved.occurredOn).map { Dates.shared.display(date: $0) }
    }

    // MARK: Your splits

    func loadHistory() {
        guard let splitRepository else { return }
        historyLoading = !historyLoaded
        historyErrorKey = nil
        Task {
            do {
                let list = try await splitRepository.splits()
                history = list
                historyLoading = false
                historyLoaded = true
            } catch {
                guard !Self.cancelled(error) else { return }
                historyLoading = false
                historyErrorKey = Self.messageKey(error)
            }
        }
    }

    var historyEmpty: Bool { historyLoaded && history.isEmpty && historyErrorKey == nil }

    func summaryLine(_ summary: SplitSummary) -> String { SplitExpense.shared.summaryLine(summary: summary, words: words) }

    func dateLabel(_ iso: String) -> String { Dates.shared.parse(iso: iso).map { Dates.shared.display(date: $0) } ?? iso }

    func openSplit(_ id: String) {
        guard let splitRepository, !openingSplit else { return }
        openingSplit = true
        historyErrorKey = nil
        Task {
            do {
                let split = try await splitRepository.split(id: id)
                openingSplit = false
                saved = split
                savedFromHistory = true
                reviewErrorKey = nil
                push(.review)
            } catch {
                guard !Self.cancelled(error) else { return }
                openingSplit = false
                historyErrorKey = Self.messageKey(error)
            }
        }
    }

    func delete() {
        guard let splitRepository, let id = confirmingDeleteId else { return }
        confirmingDeleteId = nil
        let before = history
        history = before.filter { $0.id != id }
        Task {
            do {
                try await splitRepository.delete(splitId: id)
            } catch {
                guard !Self.cancelled(error) else { return }
                history = before
                historyErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: The coin

    /// What earns the app's loader: reading the receipt, opening a saved split,
    /// and the first read of the history.
    var working: Bool {
        reading == .reading && step == .bill
            || openingSplit
            || (step == .history && historyLoading && !historyLoaded)
    }

    var loaderCaption: String? { reading == .reading && step == .bill ? L.t(Strings.shared.split_reading) : nil }

    // MARK: Errors

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }

    /// A superseded call is not a failure to show.
    private static func cancelled(_ error: Error) -> Bool {
        error is CancellationError || kotlin(error) is KotlinCancellationException
    }
}

/// The app's own folder for a receipt being read: a copy of the picked file,
/// or the photo just taken. Cleared as soon as it has been read, and on leaving.
enum ReceiptFiles {
    private static var folder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("split-receipt", isDirectory: true)
    }

    /// Copies a picked file in: access to the original ends with the picker.
    static func adopt(_ url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        clear()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let copy = folder.appendingPathComponent("receipt." + (url.pathExtension.isEmpty ? "pdf" : url.pathExtension.lowercased()))
        try FileManager.default.copyItem(at: url, to: copy)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: copy.path)
        return copy
    }

    /// Writes a photo (from the library or the camera) in as a JPEG.
    static func write(_ data: Data) throws -> URL {
        clear()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("receipt.jpg")
        try data.write(to: file, options: .completeFileProtection)
        return file
    }

    static func clear() {
        try? FileManager.default.removeItem(at: folder)
    }
}
