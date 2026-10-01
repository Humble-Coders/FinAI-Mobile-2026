import Foundation
import SharedLogic

/**
 Drives the review queue (#32): the rows extraction could not resolve, and
 what the person does about them.

 What may be confirmed, what a correction sends and what it changed beyond its
 row are never decided here — `ReviewQueue` answers all of it, and Android
 reads the same rules.

 **A correction in progress survives the app being killed.** The row's id and
 the draft go into `snapshot`, which the view keeps in scene storage, tied to
 the user who typed them. The list itself is not saved: it is the server's, and
 re-reading it is both cheap and more honest than restoring rows that may have
 been answered elsewhere.
 */
@MainActor
final class ReviewViewModel: ObservableObject {

    @Published private(set) var rows: [SharedLogic.Transaction] = []
    @Published private(set) var categories: [SharedLogic.Category] = []
    @Published private(set) var locale = ""
    @Published private(set) var today = ManualEntry.shared.today()
    @Published private(set) var loading = true
    @Published private(set) var loadingMore = false
    /// Re-reading after an action, not opening. The list stays on screen: a
    /// refresh that blanks it and shows the coin reads as the app restarting.
    @Published private(set) var refreshing = false
    @Published private(set) var nextCursor: String?
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?
    @Published private(set) var confirmingAll = false
    @Published private(set) var busyRows: Set<String> = []
    @Published private(set) var rowErrors: [String: String] = [:]
    @Published private(set) var announcements: [String] = []
    @Published private(set) var editing: SharedLogic.Transaction?
    @Published private(set) var draft = ReviewViewModel.emptyDraft
    @Published private(set) var saving = false
    @Published private(set) var editErrorKey: String?
    @Published private(set) var newCategoryName: String?
    @Published private(set) var creatingCategory = false
    @Published private(set) var newCategoryErrorKey: String?
    /// Deleted, waiting out its undo window. Nothing has been sent yet.
    @Published private(set) var pendingDelete: SharedLogic.Transaction?

    private var transactionsRepository: TransactionsRepository?
    private var categoriesRepository: CategoriesRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private var owner: String?
    private var generation = 0
    private var undoTimer: Task<Void, Never>?
    private var pendingAt = 0

    /// Long enough to notice and reach, short enough not to feel stuck.
    static let undoWindow: Duration = .seconds(5)

    private static let emptyDraft = CorrectionDraft(
        occurredOn: nil, amount: "", direction: nil, description: "", categoryId: nil
    )

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: - Derived (the same gates as Android's UI state)

    var visibleRows: [SharedLogic.Transaction] {
        guard let pendingDelete else { return rows }
        return rows.filter { $0.id != pendingDelete.id }
    }

    /// Grouped by the date on the statement, in the order the queue came in.
    var byDate: [(day: String, rows: [SharedLogic.Transaction])] {
        var order: [String] = []
        var grouped: [String: [SharedLogic.Transaction]] = [:]
        for row in visibleRows {
            if grouped[row.occurredOn] == nil { order.append(row.occurredOn) }
            grouped[row.occurredOn, default: []].append(row)
        }
        return order.map { (day: $0, rows: grouped[$0] ?? []) }
    }

    var confirmable: [SharedLogic.Transaction] { ReviewQueue.shared.confirmable(rows: visibleRows) }
    var confirmAllLabel: String { ReviewQueue.shared.confirmAllLabel(rows: visibleRows) }
    var canConfirmAll: Bool { !confirmingAll && !confirmable.isEmpty }
    var isEmpty: Bool { !loading && !loadFailed && visibleRows.isEmpty }
    var canLoadMore: Bool { nextCursor != nil && !loadingMore && !loading }

    func isBusy(_ id: String) -> Bool { busyRows.contains(id) }
    func error(for id: String) -> String? { rowErrors[id] }
    func categoryName(for row: SharedLogic.Transaction) -> String? {
        ReviewQueue.shared.categoryName(categories: categories, id: row.categoryId)
    }
    func reasonKey(for row: SharedLogic.Transaction) -> String { ReviewQueue.shared.reasonKey(row: row) }
    func duplicateText(for row: SharedLogic.Transaction) -> String? {
        row.duplicateOf.map { ReviewQueue.shared.duplicateOf(match: $0, currency: row.currency, locale: locale) }
    }
    func amount(for row: SharedLogic.Transaction) -> String {
        Money.shared.format(amount: row.amount, currency: row.currency, locale: locale)
    }

    var editBlock: CorrectionBlock? {
        editing.flatMap { ReviewQueue.shared.blockingReason(row: $0, draft: draft, today: today) }
    }
    var canSaveCorrection: Bool { editing != nil && !saving && editBlock == nil }
    /// "Nothing has changed yet" is not worth saying before they touch anything.
    var editNotice: CorrectionBlock? { editBlock == .nothingChanged ? nil : editBlock }
    var editCategoryName: String? {
        ReviewQueue.shared.categoryName(categories: categories, id: draft.categoryId)
    }
    /// The household's own first, then the shared taxonomy.
    var pickableCategories: [(category: SharedLogic.Category, name: String)] {
        categories
            .map { (category: $0, name: ReviewQueue.shared.categoryName(category: $0)) }
            .sorted { a, b in
                a.category.isSystem != b.category.isSystem
                    ? !a.category.isSystem
                    : a.name.lowercased() < b.name.lowercased()
            }
    }
    var canCreateCategory: Bool {
        guard !creatingCategory, let name = newCategoryName else { return false }
        return !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var currency: String { editing?.currency ?? "" }

    // MARK: - Lifecycle

    func bind(userId: String, restoring snapshot: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
            restored = decode(snapshot, for: userId)
        }
        guard transactionsRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        transactionsRepository = KtorTransactionsRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        load()
    }

    func unbind() {
        // Leaving still owes the server the delete it was given.
        flushPendingDelete()
        transactionsRepository?.close()
        transactionsRepository = nil
        categoriesRepository?.close()
        categoriesRepository = nil
        capabilitiesRepository?.close()
        capabilitiesRepository = nil
        generation += 1
    }

    private func reset() {
        undoTimer?.cancel()
        undoTimer = nil
        generation += 1
        rows = []
        categories = []
        locale = ""
        loading = true
        loadingMore = false
        refreshing = false
        nextCursor = nil
        loadFailed = false
        errorKey = nil
        confirmingAll = false
        busyRows = []
        rowErrors = [:]
        announcements = []
        editing = nil
        draft = Self.emptyDraft
        saving = false
        editErrorKey = nil
        newCategoryName = nil
        creatingCategory = false
        newCategoryErrorKey = nil
        pendingDelete = nil
        restored = nil
        owner = nil
    }

    // MARK: - Reading the queue

    /// - Parameter refresh: re-reading after an action rather than opening
    ///   the screen. Only a first load earns the coin, and a refresh that
    ///   fails keeps the rows it already had.
    func load(refresh: Bool = false) {
        guard let transactionsRepository else { return }
        let categoriesRepository = self.categoriesRepository
        let capabilitiesRepository = self.capabilitiesRepository
        let started = generation
        loading = !refresh
        refreshing = refresh
        loadFailed = false
        errorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let page = try await transactionsRepository.review(cursor: nil)
                let categories = try? await categoriesRepository?.list()
                let capabilities = try? await capabilitiesRepository?.fetch()
                guard started == self.generation else { return }
                self.rows = page.rows
                self.nextCursor = page.nextCursor
                if let categories { self.categories = categories }
                if let locale = capabilities?.locale, !locale.isEmpty { self.locale = locale }
                self.today = ManualEntry.shared.today()
                self.loading = false
                self.refreshing = false
                self.restoreCorrection()
            } catch {
                guard started == self.generation else { return }
                self.loading = false
                self.refreshing = false
                // A refresh that fails leaves what is on screen alone; only a
                // first load has nothing to fall back to.
                self.loadFailed = !refresh
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    /// The next page, keyed off the last one's cursor — never an offset.
    func loadMore() {
        guard let transactionsRepository, let cursor = nextCursor, canLoadMore else { return }
        let started = generation
        loadingMore = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let page = try await transactionsRepository.review(cursor: cursor)
                guard started == self.generation else { return }
                self.loadingMore = false
                // Guarded against a row arriving twice: a replayed cursor
                // would otherwise list it again.
                let known = Set(self.rows.map(\.id))
                self.rows += page.rows.filter { !known.contains($0.id) }
                self.nextCursor = page.nextCursor
            } catch {
                guard started == self.generation else { return }
                self.loadingMore = false
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    func dismissAnnouncements() {
        announcements = []
        errorKey = nil
    }

    // MARK: - Confirming

    /// Accept everything that can be accepted. Optimistic: the rows go at once
    /// and come back if the request fails.
    func confirmAll() {
        guard let transactionsRepository, canConfirmAll else { return }
        let ids = confirmable.map(\.id)
        let before = rows
        let started = generation
        confirmingAll = true
        errorKey = nil
        rows = rows.filter { !ids.contains($0.id) }
        Task { [weak self] in
            guard let self else { return }
            do {
                let outcome = try await transactionsRepository.confirmAll(ids: ids)
                guard started == self.generation else { return }
                self.confirmingAll = false
                self.announcements = [
                    ReviewQueue.shared.confirmedMessage(outcome: outcome, sent: Int32(ids.count))
                ]
                // The server may have kept rows that still need a category. It
                // said how many, not which, so the queue is re-read.
                if Int(outcome.confirmed) != ids.count { self.load(refresh: true) }
            } catch {
                guard started == self.generation else { return }
                // Rolled back visibly: nothing was confirmed, so nothing goes.
                self.confirmingAll = false
                self.rows = before
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    /// Accept one row as it is. A row with no category stays, asking for one.
    func confirm(_ id: String) {
        guard let transactionsRepository, let row = rows.first(where: { $0.id == id }), !isBusy(id) else { return }
        let started = generation
        busyRows.insert(id)
        rowErrors[id] = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let outcome = try await transactionsRepository.confirm(id: id)
                guard started == self.generation else { return }
                self.apply(
                    id: id,
                    updated: outcome.transaction,
                    aftermath: ReviewQueue.shared.aftermath(outcome: outcome, merchant: row.merchant)
                )
            } catch {
                guard started == self.generation else { return }
                self.busyRows.remove(id)
                self.rowErrors[id] = Self.messageKey(error)
            }
        }
    }

    // MARK: - Correcting

    func edit(_ id: String) {
        guard let row = rows.first(where: { $0.id == id }) else { return }
        editing = row
        draft = ReviewQueue.shared.draftOf(row: row)
        editErrorKey = nil
        saving = false
    }

    func cancelEdit() {
        guard !saving else { return }
        editing = nil
        draft = Self.emptyDraft
        editErrorKey = nil
    }

    func setDate(_ date: Kotlinx_datetimeLocalDate) { change { $0.setDraft(occurredOn: .some(date)) } }
    func setAmount(_ value: String) { change { $0.setDraft(amount: value) } }
    func setDirection(_ direction: TransactionDirection) { change { $0.setDraft(direction: .some(direction)) } }
    func setDescription(_ value: String) { change { $0.setDraft(description: value) } }
    func chooseCategory(_ id: String) { change { $0.setDraft(categoryId: .some(id)) } }

    private func change(_ apply: (ReviewViewModel) -> Void) {
        today = ManualEntry.shared.today()
        apply(self)
        editErrorKey = nil
    }

    /// Kotlin data classes have no Swift `copy`, so every change is rebuilt
    /// through the full initialiser here, in one place (kmp-arch-v2 → SKIE).
    private func setDraft(
        occurredOn: Kotlinx_datetimeLocalDate?? = nil,
        amount: String? = nil,
        direction: TransactionDirection?? = nil,
        description: String? = nil,
        categoryId: String?? = nil
    ) {
        draft = CorrectionDraft(
            occurredOn: occurredOn ?? draft.occurredOn,
            amount: amount ?? draft.amount,
            direction: direction ?? draft.direction,
            description: description ?? draft.description_,
            categoryId: categoryId ?? draft.categoryId
        )
    }

    /// Send the correction. The request comes from `ReviewQueue.correction`,
    /// which is nil whenever Save would be disabled — so this cannot send what
    /// the button refused, and never an empty patch.
    func saveCorrection() {
        guard let transactionsRepository, let row = editing, !saving,
              let patch = ReviewQueue.shared.correction(row: row, draft: draft, today: today) else { return }
        let started = generation
        saving = true
        editErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let outcome = try await transactionsRepository.correct(id: row.id, patch: patch)
                guard started == self.generation else { return }
                self.saving = false
                self.editing = nil
                self.draft = Self.emptyDraft
                self.apply(
                    id: row.id,
                    updated: outcome.transaction,
                    aftermath: ReviewQueue.shared.aftermath(outcome: outcome, merchant: row.merchant)
                )
                // Other rows took the new category. The server said how many,
                // not which, so the queue is re-read — and only then.
                if ReviewQueue.shared.mustReload(outcome: outcome) { self.load(refresh: true) }
            } catch {
                guard started == self.generation else { return }
                self.saving = false
                self.editErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: - A category of their own

    func openNewCategory() {
        newCategoryName = ""
        newCategoryErrorKey = nil
    }

    func setNewCategoryName(_ name: String) {
        newCategoryName = name
        newCategoryErrorKey = nil
    }

    func cancelNewCategory() {
        guard !creatingCategory else { return }
        newCategoryName = nil
        newCategoryErrorKey = nil
    }

    /// Adds the category and files this row into it — the reason it was added.
    func createCategory() {
        guard let categoriesRepository, canCreateCategory,
              let name = newCategoryName?.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        let started = generation
        creatingCategory = true
        newCategoryErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let made = try await categoriesRepository.create(name: name)
                guard started == self.generation else { return }
                self.creatingCategory = false
                self.newCategoryName = nil
                self.categories.append(made)
                self.setDraft(categoryId: .some(made.id))
            } catch {
                guard started == self.generation else { return }
                self.creatingCategory = false
                if let taken = Self.kotlin(error) as? ApiException.CategoryExists {
                    // The name is taken by one they can already use, so use
                    // it: thinking of another name would be busywork.
                    self.newCategoryName = nil
                    if let id = taken.categoryId { self.setDraft(categoryId: .some(id)) }
                    self.announcements = [L.t(taken.messageKey)]
                } else {
                    self.newCategoryErrorKey = Self.messageKey(error)
                }
            }
        }
    }

    // MARK: - Deleting, with a window to change their mind

    /// Hide the row and start the undo window. **Nothing is sent yet**: the
    /// server has no undo, so the only honest way to offer one is to wait.
    func delete(_ id: String) {
        guard let row = rows.first(where: { $0.id == id }) else { return }
        // One at a time: deleting another row sends the one already waiting.
        flushPendingDelete()
        pendingAt = rows.firstIndex(where: { $0.id == id }) ?? 0
        pendingDelete = row
        undoTimer = Task { [weak self] in
            try? await Task.sleep(for: Self.undoWindow)
            guard !Task.isCancelled else { return }
            await self?.sendPendingDelete()
        }
    }

    /// Within the window, nothing was ever sent, so the row simply comes back.
    func undoDelete() {
        undoTimer?.cancel()
        undoTimer = nil
        pendingDelete = nil
    }

    /// Leaving still owes the server the delete: the person saw the row go,
    /// and one that reappears with no explanation is worse than one that goes.
    func flushPendingDelete() {
        undoTimer?.cancel()
        undoTimer = nil
        if pendingDelete != nil { sendPendingDelete() }
    }

    private func sendPendingDelete() {
        guard let transactionsRepository, let pending = pendingDelete else { return }
        let at = pendingAt
        let started = generation
        pendingDelete = nil
        rows = rows.filter { $0.id != pending.id }
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await transactionsRepository.delete(id: pending.id)
            } catch {
                guard started == self.generation else { return }
                // It is still on the server, so put it back where it was.
                self.rows.insert(pending, at: min(max(at, 0), self.rows.count))
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: - Plumbing

    /// The row as the server now has it, plus whatever that change did elsewhere.
    private func apply(id: String, updated: SharedLogic.Transaction, aftermath: [String]) {
        if updated.needsReview {
            // Still waiting — now for a different question, most often a
            // category. It stays listed rather than vanishing.
            rows = rows.map { $0.id == id ? updated : $0 }
        } else {
            rows = rows.filter { $0.id != id }
        }
        busyRows.remove(id)
        rowErrors[id] = nil
        // Said once: each action replaces the last, rather than stacking
        // lines above the button for the length of the queue.
        announcements = aftermath
    }

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }

    // MARK: - A correction in progress, across process death

    /// Decoded on bind, applied once the queue has loaded and the row is known
    /// to still be there.
    private var restored: Snapshot?

    var snapshot: String {
        let value = Snapshot(
            owner: owner ?? "",
            editing: editing?.id,
            occurredOn: draft.occurredOn?.description(),
            amount: draft.amount,
            direction: draft.direction?.wire,
            description: draft.description_,
            categoryId: draft.categoryId
        )
        guard let data = try? JSONEncoder().encode(value) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    private func decode(_ snapshot: String, for userId: String) -> Snapshot? {
        guard let value = try? JSONDecoder().decode(Snapshot.self, from: Data(snapshot.utf8)),
              value.owner == userId, value.editing != nil else { return nil }
        return value
    }

    /// A row answered elsewhere in the meantime takes its correction with it.
    private func restoreCorrection() {
        guard let value = restored, let id = value.editing,
              let row = rows.first(where: { $0.id == id }) else {
            restored = nil
            return
        }
        restored = nil
        editing = row
        let direction = value.direction.map { TransactionDirection.companion.fromWire(value: $0) }
        draft = CorrectionDraft(
            occurredOn: Dates.shared.parse(iso: value.occurredOn),
            amount: value.amount,
            direction: direction == .unknown ? nil : direction,
            description: value.description,
            categoryId: value.categoryId
        )
    }

    private struct Snapshot: Codable {
        let owner: String
        let editing: String?
        let occurredOn: String?
        let amount: String
        let direction: String?
        let description: String
        let categoryId: String?
    }
}
