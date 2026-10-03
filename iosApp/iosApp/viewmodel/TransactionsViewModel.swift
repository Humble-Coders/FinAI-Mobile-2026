import Foundation
import SharedLogic

/**
 Everything the household has, one statement or one month at a time (#F3).

 Which filter each mode sends is decided by shared `TransactionBrowsing` and
 read the same way by Android. This model moves answers in and out.
 */
@MainActor
final class TransactionsViewModel: ObservableObject {

    @Published private(set) var mode: TransactionBrowsing.Mode = TransactionBrowsing.Mode.byMonth
    @Published private(set) var statements: [StatementImportSummary] = []
    @Published private(set) var months: [Kotlinx_datetimeLocalDate] = []
    @Published private(set) var statementId: String?
    @Published private(set) var month: Kotlinx_datetimeLocalDate = DashboardMonths.shared.current(
        timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
    )

    @Published private(set) var rows: [SharedLogic.Transaction] = []
    @Published private(set) var categories: [SharedLogic.Category] = []
    @Published private(set) var nextCursor: String?
    @Published private(set) var loading = true
    /// Changing slice with rows on screen. They stay up until the new ones
    /// arrive rather than the list emptying on every tap.
    @Published private(set) var refreshing = false
    @Published private(set) var loadingMore = false
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?

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
        loadSlices()
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
        generation += 1
    }

    /// Re-read when something changed the ledger, so this screen does not go
    /// stale behind a correction made in the review queue.
    private func listenForChanges() {
        guard listener == nil else { return }
        listener = Task { [weak self] in
            for await _ in LedgerChanged.events {
                guard let self, !Task.isCancelled else { return }
                self.load(refresh: true)
            }
        }
    }

    /// The statements and months on offer, then the first page of the default slice.
    private func loadSlices() {
        guard let importsRepository else { return }
        let categoriesRepository = self.categoriesRepository
        let started = generation
        Task { [weak self] in
            guard let self else { return }
            // The pickers steer the screen; the rows are the screen. Losing
            // them leaves browsing by month, which always offers the current.
            let imports = (try? await importsRepository.list().imports) ?? []
            let categories = (try? await categoriesRepository?.list()) ?? []
            guard started == self.generation else { return }
            let offered = TransactionBrowsing.shared.statementsFrom(imports: imports)
            self.statements = offered
            self.months = TransactionBrowsing.shared.monthsFrom(
                imports: imports,
                today: DashboardMonths.shared.current(
                    timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
                )
            )
            self.categories = categories ?? []
            self.statementId = offered.first?.id
            self.load()
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
                let page = try await transactionsRepository.list(
                    statementImportId: query.statementImportId,
                    month: query.month,
                    needsReview: nil,
                    cursor: nil
                )
                guard started == self.generation else { return }
                self.rows = page.rows
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

    private var currency: String { rows.first?.currency ?? "" }

    private var digits: Int32 { Money.shared.fractionDigits(currency: currency) }

    var days: [ImportedRows.Day] { ImportedRows.shared.byDate(rows: rows) }

    var totalOut: String? {
        ImportedRows.shared.totalOut(rows: rows, fractionDigits: digits).map(money)
    }

    var totalIn: String? {
        ImportedRows.shared.totalIn(rows: rows, fractionDigits: digits).map(money)
    }

    private func money(_ amount: String) -> String {
        Money.shared.format(amount: amount, currency: currency, locale: locale)
    }

    func amountLabel(_ row: SharedLogic.Transaction) -> String {
        Money.shared.format(amount: row.amount, currency: row.currency, locale: locale)
    }

    func dateLabel(_ iso: String) -> String {
        Dates.shared.parse(iso: iso).map { Dates.shared.display(date: $0) } ?? iso
    }

    func titleOf(_ row: SharedLogic.Transaction) -> String { ImportedRows.shared.titleOf(row: row) }

    func categoryLabel(_ row: SharedLogic.Transaction) -> String {
        ImportedRows.shared.categoryOf(row: row, categories: categories)?.name
            ?? L.t(Strings.shared.import_extracted_uncategorised)
    }

    func isFiled(_ row: SharedLogic.Transaction) -> Bool {
        ImportedRows.shared.categoryOf(row: row, categories: categories) != nil
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

    var showsEmpty: Bool { !loading && !loadFailed && rows.isEmpty }

    /// Names the slice, because "nothing here" alone leaves somebody unsure
    /// whether the filter or the data is at fault.
    var emptyMessage: String {
        if browsingByStatement && statements.isEmpty {
            return L.t(Strings.shared.transactions_no_statements)
        }
        return L.t(
            browsingByStatement
                ? Strings.shared.transactions_empty_statement
                : Strings.shared.transactions_empty_month
        )
    }

    var canLoadMore: Bool { nextCursor != nil && !loadingMore && !loading }
}
