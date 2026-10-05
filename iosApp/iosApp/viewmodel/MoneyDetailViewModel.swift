import Foundation
import SharedLogic

/// The two halves of the Expenses screen.
enum ExpensesTab { case transactions, obligations }

/**
 Drives one of the four money screens — Income, Expenses, Investments or Debts —
 opened from Home's cards. One model, four screens: what differs between them is
 decided by the shared `MoneyDetail`, so they cannot drift apart, and the same
 rules drive Android's. Mirrors Android's `MoneyDetailViewModel`.
 */
@MainActor
final class MoneyDetailViewModel: ObservableObject {
    @Published private(set) var kind: MoneyKind = .expenses
    @Published private(set) var month: Kotlinx_datetimeLocalDate = MoneyDetailViewModel.thisMonth
    @Published private(set) var locale = "en"
    @Published private(set) var loading = true
    @Published private(set) var refreshing = false
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?
    @Published private(set) var data = MoneyDetailViewModel.emptyDashboard
    @Published private(set) var rows: [SharedLogic.Transaction] = []
    @Published private(set) var nextCursor: String?
    @Published private(set) var loadingMore = false
    @Published private(set) var categories: [SharedLogic.Category] = []
    @Published private(set) var setup: FinancialSetup?
    @Published var tab: ExpensesTab = .transactions
    @Published private(set) var searching = false
    @Published var query = ""
    @Published private(set) var obligationDraft: CommitmentDraft?
    @Published private(set) var obligationTouched = false
    @Published private(set) var savingObligation = false
    @Published private(set) var obligationErrorKey: String?
    /// A one-off line after a save — "Added to your monthly obligations".
    @Published var noticeKey: String?

    private var owner: String?
    private var dashboardRepository: DashboardRepository?
    private var transactionsRepository: TransactionsRepository?
    private var categoriesRepository: CategoriesRepository?
    private var setupRepository: FinancialSetupRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private var generation = 0
    private var listener: Task<Void, Never>?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    private static var thisMonth: Kotlinx_datetimeLocalDate {
        DashboardMonths.shared.current(timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault())
    }

    private static let emptyDashboard = Dashboard(
        month: "", currency: "", net: "0", previousNet: nil,
        income: SharedLogic.Flow(actual: "0", expected: nil, previous: nil),
        expenses: SharedLogic.Flow(actual: "0", expected: nil, previous: nil),
        investments: Stock(balance: "0", moved: "0", previousMoved: nil, withdrawn: "0"),
        debts: Stock(balance: "0", moved: "0", previousMoved: nil, withdrawn: "0"),
        commitments: [], trend: [], daily: [], pendingReview: 0
    )

    // MARK: - Lifecycle

    func bind(userId: String, kind: MoneyKind) {
        guard !userId.isEmpty else { return }
        if owner != userId || self.kind != kind {
            owner = userId
            self.kind = kind
            month = Self.thisMonth
            rows = []
            nextCursor = nil
            loading = true
            tab = .transactions
            query = ""
            searching = false
            generation += 1
        }
        guard dashboardRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        dashboardRepository = KtorDashboardRepository(baseUrl: base, tokens: tokens, logging: logging)
        transactionsRepository = KtorTransactionsRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        setupRepository = KtorFinancialSetupRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        listenForChanges()
        load()
    }

    func unbind() {
        listener?.cancel()
        listener = nil
        dashboardRepository?.close()
        dashboardRepository = nil
        transactionsRepository?.close()
        transactionsRepository = nil
        categoriesRepository?.close()
        categoriesRepository = nil
        setupRepository?.close()
        setupRepository = nil
        capabilitiesRepository?.close()
        capabilitiesRepository = nil
        generation += 1
    }

    /// Any change to the ledger — this screen's own sheet, an import, a correction — re-reads the month.
    private func listenForChanges() {
        guard listener == nil else { return }
        listener = Task { [weak self] in
            for await _ in LedgerChanged.events {
                guard let self, !Task.isCancelled else { return }
                self.load(refresh: true)
            }
        }
    }

    // MARK: - Loading

    func load(refresh: Bool = false) {
        guard let dashboardRepository, let transactionsRepository else { return }
        let categoriesRepository = self.categoriesRepository
        let setupRepository = self.setupRepository
        let capabilitiesRepository = self.capabilitiesRepository
        generation += 1
        let started = generation
        let query = MoneyDetail.shared.query(kind: kind, month: month)
        loading = !refresh && rows.isEmpty
        refreshing = refresh
        loadFailed = false
        errorKey = nil
        Task { [weak self] in
            do {
                // Side by side; the figures and the rows are the screen, the
                // rest only lists beside them — losing one of those leaves the
                // screen readable rather than failed.
                async let dashboard = dashboardRepository.read(month: query.month)
                async let page = transactionsRepository.browse(
                    month: query.month,
                    direction: query.direction,
                    categorySlug: query.categorySlug,
                    cursor: nil
                )
                async let categories = try? categoriesRepository?.list()
                async let setup = try? setupRepository?.get()
                async let capabilities = try? capabilitiesRepository?.fetch()
                let loadedDashboard = try await dashboard
                let loadedPage = try await page
                let loadedCategories = await categories
                let loadedSetup = await setup
                let loadedLocale = await capabilities?.locale
                guard let self, started == self.generation else { return }
                self.data = loadedDashboard
                self.rows = loadedPage.rows
                self.nextCursor = loadedPage.nextCursor
                if let loadedCategories { self.categories = loadedCategories }
                if let loadedSetup { self.setup = loadedSetup }
                if let loadedLocale, !loadedLocale.isEmpty { self.locale = loadedLocale }
                self.loading = false
                self.refreshing = false
            } catch {
                guard let self, started == self.generation else { return }
                self.loading = false
                self.refreshing = false
                // A failed refresh keeps what was on screen; only a first load fails outright.
                self.loadFailed = self.rows.isEmpty && !refresh
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    func loadMore() {
        guard let transactionsRepository, let cursor = nextCursor, !loadingMore else { return }
        let started = generation
        let query = MoneyDetail.shared.query(kind: kind, month: month)
        loadingMore = true
        Task { [weak self] in
            do {
                let page = try await transactionsRepository.browse(
                    month: query.month, direction: query.direction, categorySlug: query.categorySlug, cursor: cursor
                )
                guard let self, started == self.generation else { return }
                self.rows += page.rows
                self.nextCursor = page.nextCursor
                self.loadingMore = false
            } catch {
                guard let self else { return }
                self.loadingMore = false
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    func show(month next: Kotlinx_datetimeLocalDate) {
        let first = DashboardMonths.shared.first(date: next)
        guard first != month else { return }
        month = first
        rows = []
        nextCursor = nil
        query = ""
        load(refresh: true)
    }

    func openSearch() { searching = true }

    func closeSearch() {
        searching = false
        query = ""
    }

    // MARK: - Adding an obligation

    func openObligation() {
        obligationDraft = CommitmentDraft(name: "", amount: "", dueDay: "")
        obligationTouched = false
        obligationErrorKey = nil
    }

    func setObligation(name: String? = nil, amount: String? = nil, dueDay: String? = nil) {
        guard let draft = obligationDraft else { return }
        obligationDraft = CommitmentDraft(
            name: name ?? draft.name,
            amount: amount ?? draft.amount,
            dueDay: dueDay.map { String($0.filter(\.isNumber).prefix(2)) } ?? draft.dueDay
        )
        obligationTouched = true
        obligationErrorKey = nil
    }

    func cancelObligation() {
        guard !savingObligation else { return }
        obligationDraft = nil
        obligationErrorKey = nil
    }

    func saveObligation() {
        guard let draft = obligationDraft else { return }
        guard canSaveObligation else {
            obligationTouched = true
            return
        }
        savingObligation = true
        obligationErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            let failed = await self.addObligation(draft)
            self.savingObligation = false
            if let failed {
                self.obligationErrorKey = failed
            } else {
                self.obligationDraft = nil
                self.load(refresh: true)
            }
        }
    }

    /// "Mark as obligation" on a saved entry: the entry is in either way, so a
    /// failure here says so rather than pretending the save failed.
    func markAsObligation(name: String, amount: String, dueDay: Int?) {
        Task { [weak self] in
            guard let self else { return }
            let failed = await self.addObligation(
                CommitmentDraft(name: name, amount: amount, dueDay: dueDay.map(String.init) ?? "")
            )
            self.noticeKey = failed == nil ? Strings.shared.money_obligation_added : Strings.shared.money_obligation_failed
            if failed == nil { self.load(refresh: true) }
        }
    }

    /// Nil when added; otherwise the message key saying why not.
    private func addObligation(_ draft: CommitmentDraft) async -> String? {
        guard let setupRepository else { return Strings.shared.error_unexpected }
        do {
            // Read fresh, then write the whole list back: the server keeps the
            // wizard's answers as one list, and a stale one would overwrite a
            // change made elsewhere.
            let current = try await setupRepository.get()
            guard let next = CommitmentEdit.shared.added(setup: current, draft: draft) else {
                return Strings.shared.commitment_add_limit
            }
            _ = try await setupRepository.save(setup: next)
            return nil
        } catch {
            return Self.messageKey(error)
        }
    }

    private static func messageKey(_ error: Error) -> String {
        ((error as NSError).userInfo["KotlinException"] as? ApiException)?.messageKey
            ?? Strings.shared.error_unexpected
    }

    // MARK: - Derived (the same rules as Android's UI state)

    private var currency: String { data.currency }
    private func money(_ amount: String) -> String { Money.shared.format(amount: amount, currency: currency, locale: locale) }

    var titleKey: String {
        switch kind {
        case .income: Strings.shared.money_income_title
        case .expenses: Strings.shared.money_expenses_title
        case .investments: Strings.shared.money_investments_title
        case .debts: Strings.shared.money_debts_title
        }
    }

    var headlineLabelKey: String {
        switch kind {
        case .income: Strings.shared.money_total_income
        case .expenses: Strings.shared.money_total_expenses
        case .investments: Strings.shared.money_invested_this_month
        case .debts: Strings.shared.money_total_outstanding
        }
    }

    var headline: String { money(MoneyDetail.shared.headline(kind: kind, data: data)) }

    func monthName(_ month: Kotlinx_datetimeLocalDate) -> String {
        L.t(Strings.shared.dashboard_month_display, Dates.shared.monthShort(date: month, language: locale), String(month.year))
    }

    var monthLabel: String { monthName(month) }

    /// This month and the eleven before it, newest first.
    var months: [Kotlinx_datetimeLocalDate] {
        var out = [Self.thisMonth]
        while out.count < 12, let last = out.last { out.append(DashboardMonths.shared.previous(month: last)) }
        return out
    }

    var change: MoneyDetail.Change? {
        MoneyDetail.shared.change(
            current: MoneyDetail.shared.headline(kind: kind, data: data),
            previous: MoneyDetail.shared.previous(kind: kind, data: data)
        )
    }

    var changeLabel: String? {
        change.map { L.t($0.rose ? Strings.shared.money_change_up : Strings.shared.money_change_down, String($0.percent)) }
    }

    var changeDescription: String? {
        change.map {
            if $0.percent == 0 { return L.t(Strings.shared.money_change_same) }
            return L.t($0.rose ? Strings.shared.money_change_rose : Strings.shared.money_change_fell, String($0.percent))
        }
    }

    /// More income or more invested is good news; more spending is not. Colour only — the words say which way.
    var changeIsGood: Bool {
        guard let change else { return true }
        return kind == .expenses ? !change.rose : change.rose
    }

    var paidThisMonth: String? {
        kind == .debts ? L.t(Strings.shared.money_paid_this_month, money(data.debts.moved)) : nil
    }

    // Trend

    var showsTrend: Bool { kind == .income || kind == .investments }
    var bars: [MoneyDetail.Bar] { MoneyDetail.shared.bars(kind: kind, trend: data.trend, month: month) }

    func barMonth(_ bar: MoneyDetail.Bar) -> String {
        Dates.shared.parse(iso: bar.month).map { Dates.shared.monthShort(date: $0, language: locale) } ?? ""
    }

    func barValue(_ bar: MoneyDetail.Bar) -> String? {
        bar.value.map { Money.shared.compact(amount: $0, currency: currency, locale: locale) }
    }

    var trendDescription: String {
        bars.map { bar in
            bar.value.map { L.t(Strings.shared.money_trend_month, barMonth(bar), money($0)) }
                ?? L.t(Strings.shared.money_trend_gap, barMonth(bar))
        }.joined(separator: "; ")
    }

    // Transactions

    var visibleRows: [SharedLogic.Transaction] {
        let mine = MoneyDetail.shared.rowsFor(kind: kind, rows: rows, month: month, categories: categories)
        let words = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !words.isEmpty else { return mine }
        return mine.filter { row in
            ImportedRows.shared.titleOf(row: row).lowercased().contains(words)
                || (ImportedRows.shared.categoryOf(row: row, categories: categories)?.name.lowercased().contains(words) ?? false)
        }
    }

    var days: [ImportedRows.Day] { ImportedRows.shared.byDate(rows: visibleRows) }

    func dayLabel(_ day: ImportedRows.Day) -> String {
        Dates.shared.parse(iso: day.date).map { Dates.shared.display(date: $0) } ?? day.date
    }

    func titleOf(_ row: SharedLogic.Transaction) -> String { ImportedRows.shared.titleOf(row: row) }

    func categoryLabel(_ row: SharedLogic.Transaction) -> String {
        ImportedRows.shared.categoryOf(row: row, categories: categories)?.name
            ?? L.t(Strings.shared.import_extracted_uncategorised)
    }

    func iconFor(_ row: SharedLogic.Transaction) -> CategoryIcon {
        CategoryIcons.shared.forSlug(slug: ImportedRows.shared.categoryOf(row: row, categories: categories)?.slug)
    }

    func isCredit(_ row: SharedLogic.Transaction) -> Bool { row.direction == .credit }

    func amountLabel(_ row: SharedLogic.Transaction) -> String {
        L.t(
            isCredit(row) ? Strings.shared.dashboard_recent_credit : Strings.shared.dashboard_recent_debit,
            Money.shared.format(amount: row.amount, currency: row.currency, locale: locale)
        )
    }

    var listTitleKey: String {
        switch kind {
        case .income: Strings.shared.money_income_transactions
        case .expenses: Strings.shared.money_expense_transactions
        case .investments: Strings.shared.money_investment_transactions
        case .debts: Strings.shared.money_debt_transactions
        }
    }

    var emptyMessage: String {
        let words = query.trimmingCharacters(in: .whitespaces)
        return words.isEmpty ? L.t(Strings.shared.money_no_transactions, monthLabel) : L.t(Strings.shared.money_no_matches, words)
    }

    // Obligations

    var commitments: [Commitment] { data.commitments }

    var metLabel: String {
        let met = MoneyDetail.shared.met(commitments: commitments)
        return L.t(Strings.shared.money_obligations_met, String(met.seen), String(met.total))
    }

    func dueLabel(_ dueDay: KotlinInt?) -> String {
        MoneyDetail.shared.dueLabelDate(dueDay: dueDay, month: month, locale: locale).map { L.t(Strings.shared.money_due, $0) }
            ?? L.t(Strings.shared.money_no_due_day)
    }

    func amount(_ raw: String) -> String { money(raw) }

    var obligationBlock: CommitmentBlock? {
        obligationDraft.flatMap {
            CommitmentEdit.shared.blockingReasonForNew(draft: $0, currency: currency, existing: Int32(commitments.count))
        }
    }

    var obligationNotice: CommitmentBlock? { obligationTouched ? obligationBlock : nil }
    var canSaveObligation: Bool { obligationDraft != nil && !savingObligation && obligationBlock == nil }
    var currencySymbol: String { Money.shared.symbol(currency: currency) }
    var amountPlaceholder: String {
        Money.shared.normalize(raw: "0", fractionDigits: Money.shared.fractionDigits(currency: currency)) ?? ""
    }

    // Debts

    var debts: [Debt] { setup?.debts ?? [] }

    func debtDetail(_ debt: Debt) -> String? {
        let parts = [
            debt.minimumPayment.flatMap { $0.isEmpty ? nil : L.t(Strings.shared.money_minimum_payment, money($0)) },
            debt.interestRatePercent.flatMap { $0.isEmpty ? nil : L.t(Strings.shared.money_interest_rate, $0) },
        ].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    var upcoming: [MoneyDetail.Upcoming] {
        MoneyDetail.shared.upcoming(commitments: commitments, debts: debts, month: month)
    }

    func upcomingDue(_ item: MoneyDetail.Upcoming) -> String {
        L.t(Strings.shared.money_due, "\(item.due.day) \(Dates.shared.monthShort(date: item.due, language: locale))")
    }

    // Investments

    var invested: String { money(data.investments.moved) }
    var withdrawn: String { money(data.investments.withdrawn) }
    var shares: [MoneyDetail.Share] { MoneyDetail.shared.shares(investments: setup?.investments ?? []) }
    func shareLabel(_ share: MoneyDetail.Share) -> String { L.t(Strings.shared.money_share, String(share.percent)) }
}
