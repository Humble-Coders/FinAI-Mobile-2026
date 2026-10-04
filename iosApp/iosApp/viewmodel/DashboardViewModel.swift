import Foundation
import SharedLogic

/**
 Drives the dashboard (PRD F3): one month at a time, and moving between them.

 Which month comes before this one, and every figure on the screen, are decided
 elsewhere — `DashboardMonths` and the server. This model moves answers in and
 out, and Android's `DashboardUiState` reads the same rules.

 **No property here adds an expectation to an actual.** Doing so is the double
 count the server refuses to make, and it would be just as wrong computed here.

 Nothing is kept across the app being killed. The screen is a read of the
 server with no draft in it, and re-reading is both cheap and more honest than
 restoring figures that may have changed.
 */
@MainActor
final class DashboardViewModel: ObservableObject {

    @Published private(set) var month: Kotlinx_datetimeLocalDate = DashboardMonths.shared.current(
        timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
    )
    @Published private(set) var data = Dashboard(
        month: "", currency: "", net: "0", previousNet: nil,
        income: SharedLogic.Flow(actual: "0", expected: nil),
        expenses: SharedLogic.Flow(actual: "0", expected: nil),
        investments: Stock(balance: "0", moved: "0"),
        debts: Stock(balance: "0", moved: "0"),
        commitments: [], trend: [], pendingReview: 0
    )
    @Published private(set) var locale = "en"
    @Published private(set) var loading = true
    /// Stepping between months. The figures stay up while the next ones
    /// arrive: blanking the screen on every step reads as the app restarting.
    @Published private(set) var refreshing = false
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?
    /// Whether figures are masked. Session-only and deliberately not saved: it
    /// is for the moment somebody is on a train, not a setting, and a dashboard
    /// that opens blank because of a tap days ago is a bug report.
    @Published private(set) var amountsHidden = false
    /// The newest few rows, whatever month is in view; see `loadRecent`.
    @Published private(set) var recent: [SharedLogic.Transaction] = []
    @Published private(set) var categories: [SharedLogic.Category] = []

    // MARK: Editing a commitment
    @Published private(set) var editingCommitment: Commitment?
    /// A new commitment being typed in; `editingCommitment` is nil meanwhile.
    @Published private(set) var addingCommitment = false
    /// Asking "Delete Rent?" before anything is sent.
    @Published var confirmingCommitmentDelete = false
    @Published private(set) var commitmentDraft = CommitmentDraft(name: "", amount: "")
    @Published private(set) var commitmentSaving = false
    @Published private(set) var commitmentErrorKey: String?

    private var dashboardRepository: DashboardRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private var transactionsRepository: TransactionsRepository?
    private var categoriesRepository: CategoriesRepository?
    private var setupRepository: FinancialSetupRepository?
    private var owner: String?
    /// The recent list's own counter: it does not follow the month, so a step
    /// back to August must not cancel a read of what happened most recently.
    private var recentGeneration = 0
    /// Rises on every bind and every month change, so a slow answer for a month
    /// the user has left cannot land on the one they are looking at.
    private var generation = 0

    /// One observer for the model's life; see `listenForChanges`.
    private var listener: Task<Void, Never>?

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
        guard dashboardRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        dashboardRepository = KtorDashboardRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        transactionsRepository = KtorTransactionsRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        setupRepository = KtorFinancialSetupRepository(baseUrl: base, tokens: tokens, logging: logging)
        listenForChanges()
        load()
        loadRecent()
    }

    func unbind() {
        listener?.cancel()
        listener = nil
        dashboardRepository?.close()
        dashboardRepository = nil
        capabilitiesRepository?.close()
        capabilitiesRepository = nil
        transactionsRepository?.close()
        transactionsRepository = nil
        categoriesRepository?.close()
        categoriesRepository = nil
        setupRepository?.close()
        setupRepository = nil
        generation += 1
        recentGeneration += 1
    }

    private func reset() {
        month = DashboardMonths.shared.current(
            timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
        )
        loading = true
        refreshing = false
        loadFailed = false
        errorKey = nil
        recent = []
        owner = nil
        generation += 1
    }

    /// - Parameter refresh: re-reading with something already on screen. A
    ///   failure then keeps the month it had, rather than blanking one that
    ///   was read successfully a moment ago.
    func load(refresh: Bool = false) {
        guard let dashboardRepository else { return }
        let capabilitiesRepository = self.capabilitiesRepository
        generation += 1
        let started = generation
        let asked = DashboardMonths.shared.wire(month: month)
        loading = !refresh
        refreshing = refresh
        loadFailed = false
        errorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let answer = try await dashboardRepository.read(month: asked)
                let capabilities = try? await capabilitiesRepository?.fetch()
                guard started == self.generation else { return }
                self.data = answer
                if let locale = capabilities?.locale, !locale.isEmpty { self.locale = locale }
                self.loading = false
                self.refreshing = false
                self.loadFailed = false
                self.errorKey = nil
            } catch {
                guard started == self.generation else { return }
                self.loading = false
                self.refreshing = false
                self.loadFailed = !refresh
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }

    /// As many as the design shows; the rest are one tap away under View all.
    static let recentCount: Int32 = 3

    /**
     The newest few rows, for the list under the figures.

     Fails quietly: the figures are the screen, and a list that could not load
     is simply not drawn rather than turning home into an error.
     */
    private func loadRecent() {
        guard let transactionsRepository else { return }
        let categoriesRepository = self.categoriesRepository
        recentGeneration += 1
        let started = recentGeneration
        Task { [weak self] in
            guard let self else { return }
            guard let rows = try? await transactionsRepository.recent(count: Self.recentCount) else { return }
            // A name beside each row is a nicety; the rows are the point.
            let categories = try? await categoriesRepository?.list()
            guard started == self.recentGeneration else { return }
            self.recent = rows
            if let categories { self.categories = categories }
        }
    }

    // MARK: - Editing a commitment

    /// Open the editor on the commitment at `index` in the month's list.
    func editCommitment(_ index: Int) {
        guard data.commitments.indices.contains(index) else { return }
        let commitment = data.commitments[index]
        editingCommitment = commitment
        addingCommitment = false
        confirmingCommitmentDelete = false
        commitmentDraft = CommitmentEdit.shared.draftOf(commitment: commitment)
        commitmentSaving = false
        commitmentErrorKey = nil
    }

    func setCommitmentName(_ name: String) {
        commitmentDraft = CommitmentDraft(name: name, amount: commitmentDraft.amount)
        commitmentErrorKey = nil
    }

    func setCommitmentAmount(_ amount: String) {
        commitmentDraft = CommitmentDraft(name: commitmentDraft.name, amount: amount)
        commitmentErrorKey = nil
    }

    /// Open the editor empty, to add one.
    func addCommitment() {
        addingCommitment = true
        editingCommitment = nil
        confirmingCommitmentDelete = false
        commitmentDraft = CommitmentDraft(name: "", amount: "")
        commitmentSaving = false
        commitmentErrorKey = nil
    }

    func cancelCommitment() {
        guard !commitmentSaving else { return }
        editingCommitment = nil
        addingCommitment = false
        confirmingCommitmentDelete = false
        commitmentErrorKey = nil
    }

    /// Ask before deleting: nothing is sent until the person says yes.
    func askDeleteCommitment() {
        guard editingCommitment != nil, !commitmentSaving else { return }
        confirmingCommitmentDelete = true
    }

    /// Delete the commitment being edited, once the person has said yes.
    func deleteCommitment() {
        guard let original = editingCommitment, !commitmentSaving else { return }
        confirmingCommitmentDelete = false
        writeSetup(goneKey: Strings.shared.commitment_edit_gone) {
            CommitmentEdit.shared.removed(setup: $0, original: original)
        }
    }

    /// The commitments section is shown once the month is read, empty or not:
    /// it is where one is added.
    var showsCommitments: Bool { !loading && !loadFailed }

    var showsCommitmentEditor: Bool { editingCommitment != nil || addingCommitment }

    var commitmentTitleKey: String {
        addingCommitment ? Strings.shared.commitment_add_title : Strings.shared.commitment_edit_title
    }

    /// "Delete Rent?" — naming it, so the wrong one is not deleted by a quick tap.
    var deleteCommitmentTitle: String {
        L.t(Strings.shared.commitment_delete_confirm_title, editingCommitment?.name ?? "")
    }

    private var commitmentBlock: CommitmentBlock? {
        if addingCommitment {
            return CommitmentEdit.shared.blockingReasonForNew(
                draft: commitmentDraft, currency: data.currency, existing: Int32(data.commitments.count)
            )
        }
        return editingCommitment.flatMap {
            CommitmentEdit.shared.blockingReason(original: $0, draft: commitmentDraft, currency: data.currency)
        }
    }

    var canSaveCommitment: Bool { showsCommitmentEditor && !commitmentSaving && commitmentBlock == nil }

    /// The notice under Save. Nothing before a touch: not "nothing changed" on
    /// an edit just opened, and not "give it a name" on an add with both fields
    /// still empty — the limit is the exception, said at once.
    var commitmentNotice: String? {
        if let commitmentErrorKey { return commitmentErrorKey }
        guard let block = commitmentBlock else { return nil }
        let untouched = addingCommitment
            && commitmentDraft.name.trimmingCharacters(in: .whitespaces).isEmpty
            && commitmentDraft.amount.trimmingCharacters(in: .whitespaces).isEmpty
        if block == .nothingChanged { return nil }
        if untouched && block != .tooMany { return nil }
        return block.messageKey
    }

    var commitmentCurrencySymbol: String { Money.shared.symbol(currency: data.currency) }

    var commitmentAmountPlaceholder: String {
        Money.shared.normalize(raw: "0", fractionDigits: Money.shared.fractionDigits(currency: data.currency)) ?? ""
    }

    /**
     Read the wizard's answers, change the one commitment, write them back. The
     server keeps commitments as one list replaced whole, with no ids. If the
     commitment is no longer in it — changed on another phone since this month
     was read — nothing is written over it: the month is re-read and the person
     told. Mirrors Android's `saveCommitment`.
     */
    func saveCommitment() {
        guard canSaveCommitment else { return }
        let draft = commitmentDraft
        if addingCommitment {
            // Nil when the list filled up on another phone since this month
            // was read: the same refusal the count check gives up front.
            writeSetup(goneKey: Strings.shared.commitment_add_limit) {
                CommitmentEdit.shared.added(setup: $0, draft: draft)
            }
        } else if let original = editingCommitment {
            writeSetup(goneKey: Strings.shared.commitment_edit_gone) {
                CommitmentEdit.shared.applied(setup: $0, original: original, draft: draft)
            }
        }
    }

    /**
     Read the wizard's answers, change them, and write them back; then re-read
     the month so the list shows what was saved. The server keeps commitments as
     one list replaced whole, with no ids. `change` returns nil when the answers
     moved on since this month was read — on another phone, say. Nothing is
     written over them then: the month is re-read and `goneKey` says why.
     Mirrors Android's `writeSetup`.
     */
    private func writeSetup(goneKey: String, change: @escaping (FinancialSetup) -> FinancialSetup?) {
        guard let setupRepository else { return }
        commitmentSaving = true
        commitmentErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let setup = try await setupRepository.get()
                guard let changed = change(setup) else {
                    self.commitmentSaving = false
                    self.commitmentErrorKey = goneKey
                    self.load(refresh: true)
                    return
                }
                _ = try await setupRepository.save(setup: changed)
                self.commitmentSaving = false
                self.editingCommitment = nil
                self.addingCommitment = false
                self.load(refresh: true)
            } catch {
                self.commitmentSaving = false
                self.commitmentErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Mask or unmask every figure on screen.
    func toggleAmounts() { amountsHidden.toggle() }

    func showPreviousMonth() {
        show(DashboardMonths.shared.previous(month: month))
    }

    /// Does nothing on the month that is running: it has not happened yet.
    func showNextMonth() {
        guard canGoForward else { return }
        show(DashboardMonths.shared.next(month: month))
    }

    /**
     Re-read whenever something changed the ledger.

     A refresh, not a load: the figures already on screen stay up while the new
     ones arrive, so coming back from an import does not flash an empty
     dashboard on the way to a full one.

     One observer per model. A second would re-read the month twice for every
     write, which is invisible on a fast connection and a doubled bill on a
     slow one.
     */
    private func listenForChanges() {
        guard listener == nil else { return }
        listener = Task { [weak self] in
            for await _ in LedgerChanged.events {
                guard let self, !Task.isCancelled else { return }
                self.load(refresh: true)
                self.loadRecent()
            }
        }
    }

    private func show(_ next: Kotlinx_datetimeLocalDate) {
        guard next != month else { return }
        month = next
        load(refresh: true)
    }

    // MARK: - Derived (the same rules as Android's UI state)

    var canGoBack: Bool { !loading }

    /// The label for the eye, which says what tapping it will do.
    var hideToggleLabel: String {
        L.t(amountsHidden ? Strings.shared.dashboard_show_amounts : Strings.shared.dashboard_hide_amounts)
    }

    /// Whether anything is waiting, which is what the bell's dot means.
    var hasPending: Bool { data.pendingReview > 0 }

    var notificationsLabel: String {
        L.t(hasPending ? Strings.shared.dashboard_notifications : Strings.shared.dashboard_notifications_none)
    }

    var canGoForward: Bool {
        !loading && DashboardMonths.shared.canGoForward(
            month: month,
            today: DashboardMonths.shared.current(
                timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
            )
        )
    }

    var monthLabel: String {
        L.t(Strings.shared.dashboard_month_display, Dates.shared.monthShort(date: month, language: locale), String(month.year))
    }

    private var digits: Int32 { data.fractionDigits }

    private func money(_ amount: String) -> String {
        amountsHidden
            ? L.t(Strings.shared.dashboard_hidden_amount)
            : Money.shared.format(amount: amount, currency: data.currency, locale: locale)
    }

    var net: String { money(data.net) }
    var netIsPositive: Bool { data.netIsPositive }

    /// An absolute difference, not a percentage: a rise from nothing has no
    /// percentage, and the amount is easier to read than one anyway. Nil when
    /// there is no month to compare against.
    var changeLabel: String? {
        guard let previous = data.previousNet,
              let gap = Money.shared.difference(a: data.net, b: previous, fractionDigits: digits)
        else { return nil }
        let sign = Money.shared.signOf(raw: gap, fractionDigits: digits)
        if sign == 0 { return L.t(Strings.shared.dashboard_change_same) }
        let magnitude = Money.shared.magnitudeOf(raw: gap, fractionDigits: digits) ?? "0"
        let key = sign > 0 ? Strings.shared.dashboard_change_up : Strings.shared.dashboard_change_down
        return L.t(key, money(magnitude))
    }

    var incomeAmount: String { money(data.income.actual) }
    var expensesAmount: String { money(data.expenses.actual) }
    var investmentsAmount: String { money(data.investments.balance) }
    var debtsAmount: String { money(data.debts.balance) }

    var incomeExpectation: String? { expectation(data.income.expected) }
    var expensesExpectation: String? { expectation(data.expenses.expected) }

    private func expectation(_ expected: String?) -> String? {
        guard let expected else { return nil }
        return L.t(Strings.shared.dashboard_of_expected, money(expected))
    }

    /// Only expenses: over on income is good news, and a screen should not scold.
    var expensesAreOver: Bool { data.expenses.isOverExpected }

    var investmentsMovement: String { movement(data.investments, Strings.shared.dashboard_set_aside) }
    var debtsMovement: String { movement(data.debts, Strings.shared.dashboard_paid_down) }

    private func movement(_ stock: Stock, _ key: String) -> String {
        stock.movedThisMonth
            ? L.t(key, money(stock.moved))
            : L.t(Strings.shared.dashboard_nothing_moved)
    }

    var commitmentRows: [CommitmentRow] {
        data.commitments.enumerated().map { index, commitment in
            CommitmentRow(
                id: index,
                name: commitment.name,
                expected: money(commitment.expected),
                wasSeen: commitment.wasSeen,
                detail: detail(for: commitment)
            )
        }
    }

    /// "Seen Aug 2, 2026" — never "Paid". A commitment settled in cash, from
    /// another account, or under a name the bank writes differently is
    /// indistinguishable from one never paid.
    private func detail(for commitment: Commitment) -> String {
        guard let match = commitment.match else {
            return L.t(Strings.shared.dashboard_commitment_not_seen)
        }
        if commitment.paidADifferentAmount {
            return L.t(Strings.shared.dashboard_commitment_differs, money(match.amount), money(commitment.expected))
        }
        let on = Dates.shared.parse(iso: match.occurredOn).map { Dates.shared.display(date: $0) }
            ?? match.occurredOn
        return L.t(Strings.shared.dashboard_commitment_seen, on)
    }

    var commitmentsSummary: String? {
        guard !data.commitments.isEmpty else { return nil }
        return L.t(Strings.shared.dashboard_commitments_summary, String(data.commitmentsSeenCount), String(data.commitments.count))
    }

    var pendingReviewLabel: String? {
        let pending = data.pendingReview
        if pending <= 0 { return nil }
        if pending == 1 { return L.t(Strings.shared.dashboard_pending_review_one) }
        return L.t(Strings.shared.dashboard_pending_review, String(pending))
    }

    /// Where each month sits on the chart — shared geometry, so both apps
    /// draw the same line. Nil until some month in the window holds anything.
    var chart: DashboardTrend.Chart? { DashboardTrend.shared.chart(trend: data.trend) }

    /// Each month in words, for VoiceOver: the line says nothing to somebody
    /// who cannot see it, and a gap must be heard as a gap.
    var trendDescriptions: [String] {
        data.trend.map { point in
            let label = Dates.shared.parse(iso: point.month)
                .map { Dates.shared.monthShort(date: $0, language: locale) } ?? ""
            return point.net.map { L.t(Strings.shared.dashboard_trend_month, label, money($0)) }
                ?? L.t(Strings.shared.dashboard_trend_no_data_month, label)
        }
    }

    /// What is written on the chart, one per point it draws: the month, and its
    /// figure rounded for a label ("$1.5k") — left off while amounts are hidden,
    /// and for a month with nothing recorded. Mirrors Android's `chartLabels`.
    var chartLabels: [ChartLabel] {
        (chart?.points ?? []).map { point in
            let net = data.trend.first { $0.month == point.month }?.net
            return ChartLabel(
                month: Dates.shared.parse(iso: point.month)
                    .map { Dates.shared.monthShort(date: $0, language: locale) } ?? "",
                value: amountsHidden ? nil : net.map {
                    Money.shared.compact(amount: $0, currency: data.currency, locale: locale)
                },
                isLoss: net.map { Money.shared.signOf(raw: $0, fractionDigits: digits) < 0 } ?? false
            )
        }
    }

    /// The recent list, worded. Signed in words — "+ $5.00" or "− $5.00" — so
    /// colour is never the only cue.
    var recentRows: [RecentRow] {
        recent.map { row in
            let isCredit = row.direction == .credit
            let amount = L.t(
                isCredit ? Strings.shared.dashboard_recent_credit : Strings.shared.dashboard_recent_debit,
                money(row.amount)
            )
            let category = ImportedRows.shared.categoryOf(row: row, categories: categories)
            let categoryName = category?.name ?? L.t(Strings.shared.import_extracted_uncategorised)
            let date = Dates.shared.parse(iso: row.occurredOn).map { Dates.shared.display(date: $0) }
                ?? row.occurredOn
            let title = ImportedRows.shared.titleOf(row: row)
            return RecentRow(
                id: row.id,
                title: title,
                date: date,
                amount: amount,
                isCredit: isCredit,
                category: categoryName,
                isFiled: category != nil,
                description: L.t(Strings.shared.dashboard_recent_row, title, date, amount, categoryName)
            )
        }
    }

    var showsEmptyState: Bool { !loading && !loadFailed && data.isEmpty }

}

/// One commitment as a row: its name, what was expected, and what we saw.
struct CommitmentRow: Identifiable {
    let id: Int
    let name: String
    let expected: String
    let wasSeen: Bool
    let detail: String
}

/// One row of the recent list, already worded.
struct RecentRow: Identifiable {
    let id: String
    let title: String
    let date: String
    let amount: String
    let isCredit: Bool
    let category: String
    /// False for a row nothing filed, which is drawn as needing attention.
    let isFiled: Bool
    /// The whole row as one sentence, read once by VoiceOver.
    let description: String
}

/// One point's writing on the chart: its month, and its rounded figure if shown.
struct ChartLabel {
    let month: String
    let value: String?
    /// A month in the red, whose figure is written below its point.
    let isLoss: Bool
}

