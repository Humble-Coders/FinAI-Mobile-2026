import Foundation
import SharedLogic

/// Drives the manual entry screen (#30): one transaction typed in by hand.
///
/// What may be saved is never decided here — `ManualEntry` answers that, and
/// Android reads the same rule. This model only moves answers in and out, and
/// sends.
///
/// **What was typed survives the app being killed.** `snapshot` is the draft as
/// plain values, which the view keeps in scene storage; `bind` hands it back.
/// It carries the id of the user who typed it, so a restore can never give one
/// person's draft to another.
@MainActor
final class ManualEntryViewModel: ObservableObject {

    @Published private(set) var draft = ManualEntryViewModel.emptyDraft
    /// The device's date when the state was last touched: what "Today" and "the future" mean.
    @Published private(set) var today = ManualEntry.shared.today()
    @Published private(set) var accounts: [Account] = []
    @Published private(set) var categories: [SharedLogic.Category] = []
    /// The language figures are written in, from capabilities. Blank formats as English.
    @Published private(set) var locale = ""
    /// Accounts are loading; the app's coin loader covers the screen.
    @Published private(set) var loading = true
    /// Accounts could not be had, so there is nothing to file into.
    @Published private(set) var loadFailed = false
    /// Whether trying the load again could help. Not when there is no user to load for.
    @Published private(set) var canRetry = true
    /// The entry is on its way; Save must not send it twice.
    @Published private(set) var saving = false
    /// Unanswered fields are not scolded before the user has done anything.
    @Published private(set) var touched = false
    @Published private(set) var errorKey: String?
    /// The last save went through; cleared by the next change.
    @Published private(set) var saved = false
    /// The server said this matches one already there. The match itself may be
    /// nil when the server did not name it; the warning shows either way.
    @Published private(set) var duplicate: DuplicateWarning?
    /// An account being added, while its sheet is open.
    @Published private(set) var newAccount: NewAccountDraft?
    @Published private(set) var newAccountTouched = false
    @Published private(set) var creatingAccount = false
    @Published private(set) var newAccountErrorKey: String?

    struct DuplicateWarning {
        let match: DuplicateMatch?
    }

    private var accountsRepository: AccountsRepository?
    private var categoriesRepository: CategoriesRepository?
    private var transactionsRepository: TransactionsRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    /// Whose draft this is. The model outlives a sign-out, so this is checked
    /// on every bind.
    private var owner: String?
    /// Bumped on every unbind, so a reply for the session being left is dropped.
    private var generation = 0
    /// Bumped when the entry is discarded, so a save or an account create still
    /// on its way cannot write into the clean form of the next visit.
    private var entry = 0

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    private static let emptyDraft = ManualEntryDraft(
        accountId: nil, occurredOn: nil, amount: "", direction: nil, description: "", categoryId: nil
    )

    // MARK: - Derived

    var account: Account? { accounts.first { $0.id == draft.accountId } }

    /// The chosen account's currency. Nil until one is chosen — never a guessed one.
    var currency: String? {
        guard let currency = account?.currency, !currency.isEmpty else { return nil }
        return currency
    }

    var fractionDigits: Int32 { currency.map { Money.shared.fractionDigits(currency: $0) } ?? 2 }
    var symbol: String { currency.map { Money.shared.symbol(currency: $0) } ?? "" }
    var amountPlaceholder: String { Money.shared.normalize(raw: "0", fractionDigits: fractionDigits) ?? "" }

    /// Why Save is disabled, or nil — the one rule, from shared.
    var block: ManualEntryBlock? {
        ManualEntry.shared.blockingReason(draft: draft, currency: currency, today: today)
    }

    /// What the notice under Save says: the same rule, held back until touched.
    var notice: ManualEntryBlock? {
        ManualEntry.shared.notice(draft: draft, currency: currency, today: today, touched: touched)
    }

    var canSave: Bool { !loading && !loadFailed && !saving && block == nil }

    var dateLabel: String? { draft.occurredOn.map { Dates.shared.display(date: $0) } }

    var isToday: Bool { draft.occurredOn.map { $0.isEqual(today) } ?? false }

    var categoryName: String? { categories.first { $0.id == draft.categoryId }?.name }

    /// The duplicate warning's sentence, written by shared so both apps say the same.
    var duplicateMessage: String? {
        guard let duplicate else { return nil }
        return ManualEntry.shared.duplicateMessage(match: duplicate.match, currency: currency ?? "", locale: locale)
    }

    var newAccountNotice: NewAccountBlock? {
        newAccount.flatMap { NewAccountForm.shared.notice(draft: $0, touched: newAccountTouched) }
    }

    var canCreateAccount: Bool {
        guard let newAccount, !creatingAccount else { return false }
        return NewAccountForm.shared.blockingReason(draft: newAccount) == nil
    }

    // MARK: - Lifecycle

    /// Binds to `userId`, taking back `snapshot` from scene storage if that user typed it.
    func bind(userId: String, restoring snapshot: String) {
        guard !userId.isEmpty else {
            reset()
            loading = false
            loadFailed = true
            canRetry = false
            errorKey = Strings.shared.error_unexpected
            return
        }
        if owner != userId {
            reset()
            owner = userId
            restore(snapshot, for: userId)
        }
        guard accountsRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        accountsRepository = KtorAccountsRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        transactionsRepository = KtorTransactionsRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        load()
    }

    func unbind() {
        accountsRepository?.close()
        accountsRepository = nil
        categoriesRepository?.close()
        categoriesRepository = nil
        transactionsRepository?.close()
        transactionsRepository = nil
        capabilitiesRepository?.close()
        capabilitiesRepository = nil
        generation += 1
        saving = false
        creatingAccount = false
    }

    private func reset() {
        draft = Self.emptyDraft
        today = ManualEntry.shared.today()
        accounts = []
        categories = []
        locale = ""
        loading = true
        loadFailed = false
        canRetry = true
        saving = false
        touched = false
        errorKey = nil
        saved = false
        duplicate = nil
        newAccount = nil
        newAccountTouched = false
        creatingAccount = false
        newAccountErrorKey = nil
        owner = nil
    }

    /// The household's accounts and categories — what an entry can be filed into.
    func load() {
        guard let accountsRepository else { return }
        let categoriesRepository = self.categoriesRepository
        let capabilitiesRepository = self.capabilitiesRepository
        let started = generation
        loading = true
        loadFailed = false
        errorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let accounts = try await accountsRepository.list()
                // Optional extras: without them the entry can still be saved,
                // and the backend chooses the category.
                let categories = try? await categoriesRepository?.list()
                let capabilities = try? await capabilitiesRepository?.fetch()
                guard started == self.generation else { return }
                self.accounts = accounts
                self.categories = (categories ?? []).sorted { a, b in
                    // The household's own first — made on purpose — then by name.
                    a.isSystem != b.isSystem ? !a.isSystem : a.name.lowercased() < b.name.lowercased()
                }
                self.locale = capabilities?.locale ?? ""
                self.today = ManualEntry.shared.today()
                // A restored choice that is no longer there is dropped, not
                // swapped for another: the field shows unanswered again.
                let accountId = self.draft.accountId.flatMap { id in accounts.contains { $0.id == id } ? id : nil }
                let categoryId = self.draft.categoryId.flatMap { id in
                    self.categories.contains { $0.id == id } ? id : nil
                }
                self.setDraft(accountId: accountId, categoryId: categoryId)
                self.loading = false
            } catch {
                guard started == self.generation else { return }
                self.loading = false
                self.loadFailed = true
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    /// Leaving the screen on purpose: what was typed goes with it, so the next
    /// visit starts clean. The household's accounts stay — they are not the entry.
    func discard() {
        // Also mid-request: the person has left, so the reply is theirs to
        // ignore. A save still lands on the server; it just no longer changes
        // this form.
        entry += 1
        saving = false
        creatingAccount = false
        draft = Self.emptyDraft
        touched = false
        saved = false
        duplicate = nil
        newAccount = nil
        newAccountTouched = false
        newAccountErrorKey = nil
        if !loadFailed { errorKey = nil }
    }

    // MARK: - The answers

    func chooseAccount(_ id: String) { edit { $0.setDraft(accountId: id) } }
    func chooseDate(_ date: Kotlinx_datetimeLocalDate) { edit { $0.setDraft(occurredOn: .some(date)) } }
    /// The one-tap Today: the answer is still the user's, just quicker to give.
    func chooseToday() { edit { $0.setDraft(occurredOn: .some(ManualEntry.shared.today())) } }
    /// Re-reads the clock without touching the entry. "Today" otherwise moves
    /// only on an edit, a load or a save, so a screen left open past midnight
    /// would refuse the new day. Called when the app comes back to the front
    /// and when the calendar opens.
    func refreshToday() {
        let now = ManualEntry.shared.today()
        if !now.isEqual(today) { today = now }
    }

    func setAmount(_ value: String) { edit { $0.setDraft(amount: value) } }
    func chooseDirection(_ direction: TransactionDirection) { edit { $0.setDraft(direction: .some(direction)) } }
    func setDescription(_ value: String) { edit { $0.setDraft(description: value) } }
    /// Nil is "choose for me": the backend categorizes.
    func chooseCategory(_ id: String?) { edit { $0.setDraft(categoryId: .some(id)) } }

    private func edit(_ change: (ManualEntryViewModel) -> Void) {
        today = ManualEntry.shared.today()
        change(self)
        touched = true
        saved = false
        errorKey = nil
    }

    /// Kotlin data classes have no Swift `copy`, so every change is rebuilt
    /// through the full initialiser here, in one place (kmp-arch-v2 → SKIE).
    private func setDraft(
        accountId: String?? = nil,
        occurredOn: Kotlinx_datetimeLocalDate?? = nil,
        amount: String? = nil,
        direction: TransactionDirection?? = nil,
        description: String? = nil,
        categoryId: String?? = nil
    ) {
        draft = ManualEntryDraft(
            accountId: accountId ?? draft.accountId,
            occurredOn: occurredOn ?? draft.occurredOn,
            amount: amount ?? draft.amount,
            direction: direction ?? draft.direction,
            description: description ?? draft.description_,
            categoryId: categoryId ?? draft.categoryId
        )
    }

    // MARK: - Saving

    /// Sends the entry. The request comes from `ManualEntry.request`, which is
    /// nil whenever Save would be disabled — so this cannot send what the
    /// button refused. Pressed while blocked, it shows why instead.
    func save() { send(allowDuplicate: false) }

    /// "Yes, keep both": the same entry, marked as meant.
    func keepDuplicate() { send(allowDuplicate: true) }

    /// "No, cancel": nothing is saved, and the form keeps what was typed.
    func dismissDuplicate() { duplicate = nil }

    private func send(allowDuplicate: Bool) {
        guard let transactionsRepository, !saving, !loading, !loadFailed else { return }
        today = ManualEntry.shared.today()
        guard let request = ManualEntry.shared.request(
            draft: draft, currency: currency, today: today, allowDuplicate: allowDuplicate
        ) else {
            touched = true
            duplicate = nil
            return
        }
        let started = generation
        let typed = entry
        saving = true
        errorKey = nil
        duplicate = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await transactionsRepository.create(entry: request)
                guard started == self.generation, typed == self.entry else { return }
                self.saving = false
                self.saved = true
                self.touched = false
                // The account stays chosen for the next line of the same
                // statement; everything else starts unanswered again.
                self.draft = ManualEntryDraft(
                    accountId: self.draft.accountId, occurredOn: nil, amount: "",
                    direction: nil, description: "", categoryId: nil
                )
            } catch {
                guard started == self.generation, typed == self.entry else { return }
                self.saving = false
                if let duplicate = Self.kotlin(error) as? ApiException.DuplicateTransaction {
                    self.duplicate = DuplicateWarning(match: duplicate.match)
                } else {
                    // What was typed stays: a failed save must never lose an entry.
                    self.errorKey = Self.messageKey(error)
                }
            }
        }
    }

    // MARK: - Adding an account

    func openNewAccount() {
        newAccount = NewAccountDraft(name: "", kind: nil)
        newAccountTouched = false
        newAccountErrorKey = nil
    }

    func setNewAccountName(_ name: String) {
        guard let newAccount else { return }
        self.newAccount = NewAccountDraft(name: name, kind: newAccount.kind)
        newAccountTouched = true
        newAccountErrorKey = nil
    }

    func chooseNewAccountKind(_ kind: AccountKind) {
        guard let newAccount else { return }
        self.newAccount = NewAccountDraft(name: newAccount.name, kind: kind)
        newAccountTouched = true
        newAccountErrorKey = nil
    }

    func cancelNewAccount() {
        guard !creatingAccount else { return }
        newAccount = nil
        newAccountTouched = false
        newAccountErrorKey = nil
    }

    /// Adds the account and chooses it for this entry — the reason it was added.
    func createAccount() {
        guard let accountsRepository, let newAccount, !creatingAccount else { return }
        guard let request = NewAccountForm.shared.request(draft: newAccount) else {
            newAccountTouched = true
            return
        }
        let started = generation
        let typed = entry
        creatingAccount = true
        newAccountErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let created = try await accountsRepository.create(account: request)
                guard started == self.generation else { return }
                guard typed == self.entry else {
                    // Left before it answered: the account exists now, so it
                    // is listed, but nobody chose it for the next entry.
                    self.accounts.append(created)
                    return
                }
                self.creatingAccount = false
                self.newAccount = nil
                self.newAccountTouched = false
                self.accounts.append(created)
                self.edit { $0.setDraft(accountId: created.id) }
            } catch {
                guard started == self.generation, typed == self.entry else { return }
                self.creatingAccount = false
                self.newAccountErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: - Scene storage

    /// The draft as plain values, for the view to keep in scene storage.
    var snapshot: String {
        let value = Snapshot(
            owner: owner ?? "",
            accountId: draft.accountId,
            occurredOn: draft.occurredOn?.description(),
            amount: draft.amount,
            direction: draft.direction?.wire,
            description: draft.description_,
            categoryId: draft.categoryId,
            touched: touched,
            newAccountName: newAccount?.name,
            newAccountKind: newAccount?.kind?.wire
        )
        guard let data = try? JSONEncoder().encode(value) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    private func restore(_ snapshot: String, for userId: String) {
        guard let value = try? JSONDecoder().decode(Snapshot.self, from: Data(snapshot.utf8)),
              value.owner == userId else { return }
        let direction = TransactionDirection.companion.fromWire(value: value.direction)
        draft = ManualEntryDraft(
            accountId: value.accountId,
            occurredOn: Dates.shared.parse(iso: value.occurredOn),
            amount: value.amount,
            direction: direction == .unknown ? nil : direction,
            description: value.description,
            categoryId: value.categoryId
        )
        touched = value.touched
        if let name = value.newAccountName {
            let kind = AccountKind.companion.fromWire(value: value.newAccountKind)
            newAccount = NewAccountDraft(name: name, kind: kind == .unknown ? nil : kind)
        }
    }

    private struct Snapshot: Codable {
        let owner: String
        let accountId: String?
        let occurredOn: String?
        let amount: String
        let direction: String?
        let description: String
        let categoryId: String?
        let touched: Bool
        let newAccountName: String?
        let newAccountKind: String?
    }

    private static func kotlin(_ error: Error) -> Any? {
        (error as NSError).userInfo["KotlinException"]
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }
}
