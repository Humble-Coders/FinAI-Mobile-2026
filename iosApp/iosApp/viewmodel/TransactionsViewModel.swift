import Foundation
import SharedLogic

/**
 Everything the household has, one statement or one month at a time (#F3).

 Which filter each mode sends is decided by shared `TransactionBrowsing` and
 read the same way by Android. This model moves answers in and out.
 */
@MainActor
final class TransactionsViewModel: ObservableObject {

    @Published private(set) var mode: TransactionBrowsing.Mode = TransactionBrowsing.Mode.byMonth { didSet { derivedCache = nil } }
    @Published private(set) var statements: [StatementImportSummary] = []
    @Published private(set) var months: [Kotlinx_datetimeLocalDate] = []
    @Published private(set) var statementId: String?
    @Published private(set) var month: Kotlinx_datetimeLocalDate = DashboardMonths.shared.current(
        timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
    ) { didSet { derivedCache = nil } }

    @Published private(set) var rows: [SharedLogic.Transaction] = [] { didSet { derivedCache = nil } }
    @Published private(set) var categories: [SharedLogic.Category] = [] { didSet { derivedCache = nil } }
    @Published private(set) var nextCursor: String?
    @Published private(set) var loading = true
    /// Changing slice with rows on screen. They stay up until the new ones
    /// arrive rather than the list emptying on every tap.
    @Published private(set) var refreshing = false
    @Published private(set) var loadingMore = false
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?

    // MARK: Editing one row
    @Published private(set) var editing: SharedLogic.Transaction?
    @Published private(set) var draft = TransactionsViewModel.emptyDraft
    @Published private(set) var saving = false
    @Published private(set) var deleting = false
    @Published private(set) var editErrorKey: String?
    @Published private(set) var today = ManualEntry.shared.today()
    @Published private(set) var newCategoryName: String?
    @Published private(set) var creatingCategory = false
    @Published private(set) var newCategoryErrorKey: String?

    private static let emptyDraft = CorrectionDraft(
        occurredOn: nil, amount: "", direction: nil, description: "", categoryId: nil
    )

    private var transactionsRepository: TransactionsRepository?
    private var importsRepository: StatementImportRepository?
    private var categoriesRepository: CategoriesRepository?
    private var owner: String?
    private var generation = 0
    private var listener: Task<Void, Never>?
    private let locale = "en"

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    func bind(userId: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
        }
        guard transactionsRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        transactionsRepository = KtorTransactionsRepository(baseUrl: base, tokens: tokens, logging: logging)
        importsRepository = KtorStatementImportRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        listenForChanges()
        // Coming back with nothing changed since: what is on screen is still
        // right, so it is shown as it was. Every return used to read the
        // whole history again, page after page, on each switch of tab.
        if !knownIds.isEmpty && seenVersion == LedgerChanged.version { return }
        // Coming back after a change — an import, an edit — is handled as
        // one: the rows read last time say what is new.
        loadSlices(afterChange: !knownIds.isEmpty)
    }

    func unbind() {
        listener?.cancel()
        listener = nil
        transactionsRepository?.close()
        transactionsRepository = nil
        importsRepository?.close()
        importsRepository = nil
        categoriesRepository?.close()
        categoriesRepository = nil
        generation += 1
    }

    private func reset() {
        rows = []
        statements = []
        months = []
        statementId = nil
        loading = true
        loadFailed = false
        errorKey = nil
        owner = nil
        knownIds = []
        generation += 1
    }

    /// Re-read when something changed the ledger, so this screen does not go
    /// stale behind a correction made in the review queue.
    private func listenForChanges() {
        guard listener == nil else { return }
        listener = Task { [weak self] in
            for await _ in LedgerChanged.events {
                guard let self, !Task.isCancelled else { return }
                // Statements, months and the slice together, so the screen
                // moves to what just landed rather than sitting on the month
                // it was showing.
                self.loadSlices(afterChange: true)
            }
        }
    }

    /// The rows seen at the last read, so a change can tell which ones it added.
    private var knownIds: Set<String> = []

    /// The statements and months on offer, then the first page of the slice to
    /// show: the newest on a first load, or — `afterChange` — wherever the
    /// change landed (`TransactionBrowsing.monthAfterChange`).
    /// The ledger's version when the rows were last read; see `bind`.
    private var seenVersion = -1

    private func loadSlices(afterChange: Bool = false) {
        guard let importsRepository else { return }
        seenVersion = LedgerChanged.version
        let categoriesRepository = self.categoriesRepository
        let started = generation
        if afterChange { refreshing = true }
        Task { [weak self] in
            guard let self else { return }
            // The pickers steer the screen; the rows are the screen. Losing
            // them leaves browsing by month, which always offers the current.
            let imports = (try? await importsRepository.list().imports) ?? []
            let categories = (try? await categoriesRepository?.list()) ?? []
            // Which months have anything in them, from the transactions' own
            // dates. Losing this leaves the current month on offer.
            let everything = await self.readAll()
            guard started == self.generation else { return }
            let today = Self.today
            let known = afterChange ? self.knownIds : []
            self.knownIds = Set(everything.map { $0.id })
            self.statementId = TransactionBrowsing.shared.statementAfterChange(
                current: afterChange ? self.statementId : nil,
                before: afterChange ? self.statements : [],
                imports: imports
            )
            self.statements = TransactionBrowsing.shared.statementsFrom(imports: imports)
            self.months = TransactionBrowsing.shared.monthsWithRows(rows: everything, today: today)
            self.month = TransactionBrowsing.shared.monthAfterChange(
                current: afterChange ? self.month : nil,
                knownIds: known,
                rows: everything,
                today: today
            )
            self.categories = categories ?? []
            self.load(refresh: afterChange)
        }
    }

    private static var today: Kotlinx_datetimeLocalDate {
        DashboardMonths.shared.current(timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault())
    }

    /// Every row, newest first, page after page — capped; see `categoryPages`.
    /// Empty when it cannot be read: the months then fall back to the current one.
    private func readAll() async -> [SharedLogic.Transaction] {
        guard let transactionsRepository else { return [] }
        do {
            var page = try await transactionsRepository.list(
                statementImportId: nil, month: nil, needsReview: nil, cursor: nil
            )
            var rows = page.rows
            var pages = 1
            while let cursor = page.nextCursor, pages < Self.categoryPages {
                page = try await transactionsRepository.list(
                    statementImportId: nil, month: nil, needsReview: nil, cursor: cursor
                )
                rows += page.rows
                pages += 1
            }
            return rows
        } catch {
            return []
        }
    }

    func show(mode next: TransactionBrowsing.Mode) {
        guard next != mode else { return }
        mode = next
        load(refresh: true)
    }

    func show(statement id: String) {
        guard id != statementId else { return }
        statementId = id
        load(refresh: true)
    }

    func show(month next: Kotlinx_datetimeLocalDate) {
        guard next != month else { return }
        month = next
        load(refresh: true)
    }

    func load(refresh: Bool = false) {
        guard let transactionsRepository else { return }
        generation += 1
        let started = generation
        let query = TransactionBrowsing.shared.query(
            mode: mode, statementImportId: statementId, month: month
        )
        loading = !refresh
        refreshing = refresh
        loadFailed = false
        errorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                var page = try await transactionsRepository.list(
                    statementImportId: query.statementImportId,
                    month: query.month,
                    needsReview: nil,
                    cursor: nil
                )
                var rows = page.rows
                // By category, every page: a category's total over half the
                // ledger is a wrong total. Capped, so a vast ledger stops and
                // offers "Show more" rather than reading forever.
                if self.mode == TransactionBrowsing.Mode.byCategory {
                    var pages = 1
                    while let cursor = page.nextCursor, pages < Self.categoryPages {
                        page = try await transactionsRepository.list(
                            statementImportId: nil, month: nil, needsReview: nil, cursor: cursor
                        )
                        guard started == self.generation else { return }
                        rows += page.rows
                        pages += 1
                    }
                }
                guard started == self.generation else { return }
                self.rows = rows
                self.nextCursor = page.nextCursor
                self.loading = false
                self.refreshing = false
            } catch {
                guard started == self.generation else { return }
                self.loading = false
                self.refreshing = false
                // A failed change keeps the rows it had; a first load has
                // nothing to keep and must say so.
                self.loadFailed = !refresh
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    /// The next page, keyed off the last one's cursor — never an offset, and
    /// carrying the slice it is paging through.
    func loadMore() {
        guard let transactionsRepository, let cursor = nextCursor, !loadingMore else { return }
        let started = generation
        let query = TransactionBrowsing.shared.query(
            mode: mode, statementImportId: statementId, month: month
        )
        loadingMore = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let page = try await transactionsRepository.list(
                    statementImportId: query.statementImportId,
                    month: query.month,
                    needsReview: nil,
                    cursor: cursor
                )
                guard started == self.generation else { return }
                self.rows += page.rows
                self.nextCursor = page.nextCursor
                self.loadingMore = false
            } catch {
                guard started == self.generation else { return }
                self.loadingMore = false
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    private static func messageKey(_ error: Error) -> String {
        ((error as NSError).userInfo["KotlinException"] as? ApiException)?.messageKey
            ?? Strings.shared.error_unexpected
    }

    // MARK: - Derived (the same rules as Android's UI state)

    var browsingByStatement: Bool { mode == TransactionBrowsing.Mode.byStatement }

    /// What the list draws, worked out once per change of rows, slice or
    /// categories — not on every redraw. As computed properties these ran the
    /// shared grouping over every row each time the screen redrew, several
    /// times a redraw, which is where opening the tab hung.
    private struct Derived {
        let visibleRows: [SharedLogic.Transaction]
        let sections: [TransactionSection]
        let totalIn: String?
        let totalOut: String?
    }

    private var derivedCache: Derived?

    private var derived: Derived {
        if let derivedCache { return derivedCache }
        let visible = mode == TransactionBrowsing.Mode.byMonth
            ? TransactionBrowsing.shared.inMonth(rows: rows, month: month)
            : rows
        let made = Derived(
            visibleRows: visible,
            sections: makeSections(visible),
            totalIn: ImportedRows.shared.totalIn(rows: visible, fractionDigits: digits).map(money),
            totalOut: ImportedRows.shared.totalOut(rows: visible, fractionDigits: digits).map(money)
        )
        derivedCache = made
        return made
    }

    /// The rows under a heading per month, newest first.
    var monthGroups: [TransactionBrowsing.MonthGroup] { TransactionBrowsing.shared.byMonth(rows: visibleRows) }

    var browsingByCategory: Bool { mode == TransactionBrowsing.Mode.byCategory }

    /// The list in sections, whichever way it is sliced: by category, one per
    /// category with its total; otherwise one per month.
    var sections: [TransactionSection] { derived.sections }

    private func makeSections(_ visible: [SharedLogic.Transaction]) -> [TransactionSection] {
        if browsingByCategory {
            return TransactionBrowsing.shared.byCategory(rows: visible, categories: categories, fractionDigits: digits)
                .map { group in
                    TransactionSection(
                        title: categories.first { $0.id == group.categoryId }?.name
                            ?? (group.categoryId == nil ? L.t(Strings.shared.import_extracted_uncategorised) : ""),
                        total: money(group.headline),
                        totalIsIn: group.headlineIsIn,
                        rows: group.rows
                    )
                }
        }
        return TransactionBrowsing.shared.byMonth(rows: visible)
            .map { TransactionSection(title: monthHeading($0.month), total: nil, totalIsIn: false, rows: $0.rows) }
    }

    /// "Aug 2026", for a heading.
    func monthHeading(_ month: String) -> String {
        Dates.shared.parse(iso: month).map { monthLabel($0) } ?? month
    }

    /// The picture for a row: its category's, or the unfiled mark.
    func iconFor(_ row: SharedLogic.Transaction) -> CategoryIcon {
        CategoryIcons.shared.forSlug(slug: ImportedRows.shared.categoryOf(row: row, categories: categories)?.slug)
    }

    func isCredit(_ row: SharedLogic.Transaction) -> Bool { row.direction == .credit }

    /// A statement's chip: just when it was imported. The sheet says the rest.
    func statementChip(_ statement: StatementImportSummary) -> String {
        let day = String(statement.createdAt.prefix(10))
        return Dates.shared.parse(iso: day).map { Dates.shared.display(date: $0) } ?? day
    }

    /// The list's currency, for its totals. `currency` is the editor's.
    private var listCurrency: String { rows.first?.currency ?? "" }

    private var digits: Int32 { Money.shared.fractionDigits(currency: listCurrency) }

    /// Pages read up front when browsing by category; see `load`.
    static let categoryPages = 20

    /// The rows to draw. By month, only the month being looked at's — whatever
    /// the server sent; see `TransactionBrowsing.inMonth`.
    var visibleRows: [SharedLogic.Transaction] { derived.visibleRows }

    var days: [ImportedRows.Day] { ImportedRows.shared.byDate(rows: visibleRows) }

    var totalOut: String? { derived.totalOut }

    var totalIn: String? { derived.totalIn }

    private func money(_ amount: String) -> String {
        Money.shared.format(amount: amount, currency: listCurrency, locale: locale)
    }

    func amountLabel(_ row: SharedLogic.Transaction) -> String {
        Money.shared.format(amount: row.amount, currency: row.currency, locale: locale)
    }

    func dateLabel(_ iso: String) -> String {
        Dates.shared.parse(iso: iso).map { Dates.shared.display(date: $0) } ?? iso
    }

    func titleOf(_ row: SharedLogic.Transaction) -> String { ImportedRows.shared.titleOf(row: row) }

    func categoryLabel(_ row: SharedLogic.Transaction) -> String {
        ImportedRows.shared.categoryLabel(
            row: row, categories: categories, unfiled: L.t(Strings.shared.import_extracted_uncategorised)
        ) ?? ""
    }

    func isFiled(_ row: SharedLogic.Transaction) -> Bool {
        ImportedRows.shared.isFiled(row: row)
    }

    func rowDescription(_ row: SharedLogic.Transaction) -> String {
        L.t(Strings.shared.import_extracted_row, titleOf(row), categoryLabel(row), amountLabel(row))
    }

    func monthLabel(_ month: Kotlinx_datetimeLocalDate) -> String {
        L.t(
            Strings.shared.dashboard_month_display,
            Dates.shared.monthShort(date: month, language: locale),
            String(month.year)
        )
    }

    /// A statement as a thing to pick: when, how many rows, and how many still
    /// want a person — which is what decides whether to open it.
    func statementLabel(_ statement: StatementImportSummary) -> String {
        let on = Dates.shared.parse(iso: String(statement.createdAt.prefix(10)))
            .map { Dates.shared.display(date: $0) } ?? String(statement.createdAt.prefix(10))
        if statement.needsReview > 0 {
            return L.t(
                Strings.shared.transactions_statement_waiting,
                on, String(statement.saved), String(statement.needsReview)
            )
        }
        return L.t(Strings.shared.transactions_statement_option, on, String(statement.saved))
    }

    var showsEmpty: Bool { !loading && !loadFailed && visibleRows.isEmpty }

    /// Names the slice, because "nothing here" alone leaves somebody unsure
    /// whether the filter or the data is at fault.
    var emptyMessage: String {
        if browsingByStatement && statements.isEmpty {
            return L.t(Strings.shared.transactions_no_statements)
        }
        if browsingByCategory { return L.t(Strings.shared.transactions_empty_category) }
        return L.t(
            browsingByStatement
                ? Strings.shared.transactions_empty_statement
                : Strings.shared.transactions_empty_month
        )
    }

    var canLoadMore: Bool { nextCursor != nil && !loadingMore && !loading }

    // MARK: - Editing a row (the same sheet and rules as the review queue)

    /// Read aloud for a row, which opens the editor when tapped.
    func editLabel(_ row: SharedLogic.Transaction) -> String {
        L.t(Strings.shared.transactions_edit_hint, titleOf(row))
    }

    /// Open the editor on `id`, filled in as the row stands.
    func edit(_ id: String) {
        guard let row = rows.first(where: { $0.id == id }) else { return }
        editing = row
        draft = ReviewQueue.shared.draftOf(row: row)
        editErrorKey = nil
        saving = false
        today = ManualEntry.shared.today()
    }

    func cancelEdit() {
        guard !saving else { return }
        editing = nil
        draft = Self.emptyDraft
        editErrorKey = nil
    }

    func setDate(_ date: Kotlinx_datetimeLocalDate) { change(occurredOn: .some(date)) }
    func setAmount(_ value: String) { change(amount: value) }
    func setDirection(_ direction: TransactionDirection) { change(direction: .some(direction)) }
    func setDescription(_ value: String) { change(description: value) }
    func chooseCategory(_ id: String) { change(categoryId: .some(id)) }

    private func change(
        occurredOn: Kotlinx_datetimeLocalDate?? = nil,
        amount: String? = nil,
        direction: TransactionDirection?? = nil,
        description: String? = nil,
        categoryId: String?? = nil
    ) {
        guard editing != nil else { return }
        draft = CorrectionDraft(
            occurredOn: occurredOn ?? draft.occurredOn,
            amount: amount ?? draft.amount,
            direction: direction ?? draft.direction,
            description: description ?? draft.description_,
            categoryId: categoryId ?? draft.categoryId
        )
        editErrorKey = nil
    }

    private var editBlock: CorrectionBlock? {
        editing.flatMap { ReviewQueue.shared.blockingReason(row: $0, draft: draft, today: today) }
    }

    var canSaveCorrection: Bool { editing != nil && !saving && editBlock == nil }
    /// "Nothing has changed yet" is not worth saying before they touch anything.
    var editNotice: CorrectionBlock? { editBlock == .nothingChanged ? nil : editBlock }
    var editCategoryName: String? { ReviewQueue.shared.categoryName(categories: categories, id: draft.categoryId) }
    var editTitleKey: String { Strings.shared.transactions_edit_title }
    var currency: String { editing?.currency ?? "" }

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
        !creatingCategory && !(newCategoryName ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    /**
     Send only what changed, through the same rule as the review queue. On
     success the ledger has moved, so it is announced: home re-reads its month
     and this list re-reads its slice — which also moves a row whose new date
     took it out of the month being looked at.
     */
    func saveCorrection() {
        guard let transactionsRepository, let row = editing, !saving,
              let patch = ReviewQueue.shared.correction(row: row, draft: draft, today: today)
        else { return }
        saving = true
        editErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await transactionsRepository.correct(id: row.id, patch: patch)
                self.saving = false
                self.editing = nil
                self.draft = Self.emptyDraft
                LedgerChanged.announce()
            } catch {
                // A duplicate is said in the sheet, with the row still open:
                // the person's draft is the thing they need to fix.
                self.saving = false
                self.editErrorKey = Self.messageKey(error)
            }
        }
    }

    /// What the delete question names the row by: title, amount, day.
    var deleteSummary: [String] {
        editing.map { ImportedRows.shared.summary(row: $0, locale: "en") } ?? []
    }

    /// Deletes the row being edited — the sheet asked first. Any row, not only
    /// one still waiting: a wrong row found after confirming it is just as
    /// wrong. On success the ledger has moved, so it is announced, and home and
    /// this list both re-read. Mirrors Android's `deleteEditing`.
    func deleteEditing() {
        guard let transactionsRepository, let row = editing, !saving, !deleting else { return }
        deleting = true
        editErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await transactionsRepository.delete(id: row.id)
                self.deleting = false
                self.editing = nil
                self.draft = Self.emptyDraft
                self.rows.removeAll { $0.id == row.id }
                LedgerChanged.announce()
            } catch {
                self.deleting = false
                self.editErrorKey = Self.messageKey(error)
            }
        }
    }

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

    /// Adds the category and files the row being edited into it.
    func createCategory() {
        guard let categoriesRepository, canCreateCategory else { return }
        let name = (newCategoryName ?? "").trimmingCharacters(in: .whitespaces)
        creatingCategory = true
        newCategoryErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let made = try await categoriesRepository.create(name: name)
                self.creatingCategory = false
                self.newCategoryName = nil
                self.categories.append(made)
                self.chooseCategory(made.id)
            } catch {
                let kotlin = (error as NSError).userInfo["KotlinException"]
                if let exists = kotlin as? ApiException.CategoryExists {
                    // Taken by one they can already use, so use it.
                    self.creatingCategory = false
                    self.newCategoryName = nil
                    if let id = exists.categoryId { self.chooseCategory(id) }
                } else {
                    self.creatingCategory = false
                    self.newCategoryErrorKey = Self.messageKey(error)
                }
            }
        }
    }
}

extension TransactionsViewModel: TransactionEditing {}

/// One heading of the list and the rows under it.
struct TransactionSection {
    let title: String
    /// A category's total; nil under a month heading.
    let total: String?
    /// The total is money in, drawn green.
    let totalIsIn: Bool
    let rows: [SharedLogic.Transaction]
}

