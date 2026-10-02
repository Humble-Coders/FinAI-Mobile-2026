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

    private var dashboardRepository: DashboardRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private var owner: String?
    /// Rises on every bind and every month change, so a slow answer for a month
    /// the user has left cannot land on the one they are looking at.
    private var generation = 0

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
        load()
    }

    func unbind() {
        dashboardRepository?.close()
        dashboardRepository = nil
        capabilitiesRepository?.close()
        capabilitiesRepository = nil
        generation += 1
    }

    private func reset() {
        month = DashboardMonths.shared.current(
            timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault()
        )
        loading = true
        refreshing = false
        loadFailed = false
        errorKey = nil
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

    /// Scaled by magnitude, so a heavy loss draws as tall as a heavy gain and
    /// the direction is carried by colour — a bad month must not look quiet.
    var bars: [Bar] {
        let magnitudes = data.trend.map { point in
            point.net.flatMap { Money.shared.magnitudeOf(raw: $0, fractionDigits: digits) }
        }
        let tallest = magnitudes.compactMap { $0 }.max {
            Money.shared.compare(a: $0, b: $1, fractionDigits: digits) < 0
        }
        return data.trend.enumerated().map { index, point in
            let label = Dates.shared.parse(iso: point.month)
                .map { Dates.shared.monthShort(date: $0, language: locale) } ?? ""
            return Bar(
                id: index,
                shortLabel: label,
                // Nil is a gap, not a zero: the view outlines it rather than
                // drawing a bar for a month nobody recorded.
                fraction: magnitudes[index].map { fraction(of: $0, against: tallest) },
                isNegative: point.net.map {
                    Money.shared.signOf(raw: $0, fractionDigits: digits) < 0
                } ?? false,
                description: point.net.map {
                    L.t(Strings.shared.dashboard_trend_month, label, money($0))
                } ?? L.t(Strings.shared.dashboard_trend_no_data_month, label)
            )
        }
    }

    private func fraction(of magnitude: String, against tallest: String?) -> Double {
        guard let tallest,
              let top = Double(tallest), top > 0,
              let value = Double(magnitude)
        else { return Self.minimumBar }
        return min(max(value / top, Self.minimumBar), 1)
    }

    /// Floor for a bar, so a small but real month is a mark and not an absence.
    private static let minimumBar = 0.06

    var showsEmptyState: Bool { !loading && !loadFailed && data.isEmpty }

    var showsTrend: Bool { data.trend.contains { $0.hasData } }
}

/// One commitment as a row: its name, what was expected, and what we saw.
struct CommitmentRow: Identifiable {
    let id: Int
    let name: String
    let expected: String
    let wasSeen: Bool
    let detail: String
}

/// One bar of the trend. `fraction` is nil for a month with no rows at all.
struct Bar: Identifiable {
    let id: Int
    let shortLabel: String
    let fraction: Double?
    let isNegative: Bool
    let description: String
}
