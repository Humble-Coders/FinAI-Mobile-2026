import SharedLogic
import SwiftUI

/**
 The dashboard (PRD F3): one month of what happened, against what was expected
 of it. This is home — the first screen a signed-in, set-up person meets.

 Every figure arrives already decided, by the server and then by the view
 model. Nothing here computes money, which is what keeps the two apps from
 disagreeing about a number somebody is acting on.

 Laid out as the approved design: a green field holding the month, its four
 figures and the ways in, and a sheet rising over it with the most recent rows.
 The icons are SF Symbols, with Material's equivalents on Android.
 */
struct DashboardView: View {
    @ObservedObject var model: DashboardViewModel
    let userId: String
    /// Importing a statement (#31) — the main way in.
    let onImportStatement: () -> Void
    /// Typing a transaction in (#30) — the other.
    let onAddTransaction: () -> Void
    /// The review queue (#32).
    let onReview: () -> Void
    /// Everything the household has, as opposed to the queue, which is only
    /// what still needs a person.
    let onViewAll: () -> Void
    let onSignOut: () -> Void

    @Environment(\.colorScheme) private var scheme
    private var dark: Bool { scheme == .dark }

    /// How far the header has shrunk into the bar. Read only by the header's
    /// fade and the bar, never by this body, so scrolling redraws those two
    /// and not the whole screen; see `HeaderCollapse`.
    @State private var collapse = HeaderCollapse()

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                field
                sheet.padding(.top, -Field.sheetOverlap)
            }
            // The top of the page stays put: no pulling the header down.
            .background(TopBounceStopper().frame(width: 0, height: 0))
        }
        .coordinateSpace(name: Self.space)
        .scrollBounceBehavior(.basedOnSize)
        .overlay(alignment: .top) {
            CollapsingBar(collapse: collapse, dark: dark) { compactHeader }
        }
        // Green above, the sheet's ground below, so pulling past either end
        // shows the colour already there rather than a strip of the other.
        .background(
            VStack(spacing: 0) {
                Field.top(dark)
                Brand.ground
            }
            .ignoresSafeArea()
        )
        .onAppear { model.bind(userId: userId) }
        // Not on disappearing: switching tab fires that, and closing and
        // rebuilding this tab's clients on every switch was the lag coming
        // back to it. RootView unbinds the tabs when they are left for good.
        .sheet(isPresented: commitmentShown) { CommitmentEditor(model: model) }
    }

    private static let space = "home"

    /// How far the field's ground reaches above its top: past any status bar,
    /// and past a pull down from the top.
    private static let fieldBleed: CGFloat = 400

    private var commitmentShown: Binding<Bool> {
        Binding(get: { model.showsCommitmentEditor }, set: { if !$0 { model.cancelCommitment() } })
    }

    // MARK: - The field

    private var field: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                // Out over the first half of the stretch; the bar comes in
                // over the second, so the two greetings never show together.
                .modifier(HeaderFade(collapse: collapse))
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .named(Self.space)).minY
                } action: { (top: CGFloat) in
                    collapse.track(top: top)
                }
            hero.padding(.top, 26)
            if let chart = model.dailyChart {
                DailyChart(
                    chart: chart,
                    ticks: model.dailyTicks,
                    value: model.dailyMarkerValue,
                    isLoss: model.dailyMarkerIsLoss
                )
                .frame(height: 128)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(model.dailyDescription)
                .padding(.top, 14)
            }
            Group {
                if model.loadFailed {
                    loadFailed
                } else if !model.showsEmptyState {
                    figures
                }
            }
            .padding(.top, 22)
            actions.padding(.top, 26)
        }
        .frame(maxWidth: 560, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, Field.sheetOverlap + 28)
        .frame(maxWidth: .infinity)
        .background(
            ZStack {
                Field.gradient(dark)
                Waves().accessibilityHidden(true)
            }
            // The field runs on up under the status bar, as in the design, and
            // scrolls with the page. `ignoresSafeArea` does nothing inside a
            // scroll view, so the strip behind the clock showed the scroll
            // view's own flat green instead — a band with a seam under it that
            // the page then slid beneath. Grown upward instead; its contents
            // stay where they are.
            .padding(.top, -Self.fieldBleed)
        )
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 14) {
            // A silhouette, not an initial. We deliberately do not hold a
            // name: the requirements forbid collecting one, and a letter would
            // need it.
            ZStack {
                Circle().fill(Field.ink(0.94))
                FinAiIcon(symbol: "person.fill", tint: Brand.greenDeep, size: 26)
            }
            .frame(width: 52, height: 52)

            VStack(alignment: .leading, spacing: 2) {
                Text(L.t(Strings.shared.dashboard_greeting))
                    .font(.title2.weight(.bold))
                    .foregroundColor(Field.ink())
                Text(L.t(Strings.shared.dashboard_subtitle))
                    .font(.subheadline)
                    .foregroundColor(Field.ink(0.82))
            }
            Spacer(minLength: 0)

            bell(size: 48)
        }
    }

    /**
     The header once the page has scrolled: a slim bar pinned over the top, the
     greeting centred in it and the bell still in reach. It fades in once the
     big header has faded out. Solid rather than see-through, because figures
     pass under it. Mirrors Android's `CompactHeader`.
     */
    private var compactHeader: some View {
        ZStack {
            Text(L.t(Strings.shared.tab_home))
                .font(.headline.weight(.bold))
                .foregroundColor(Field.ink())
                .lineLimit(1)
                .padding(.horizontal, 56)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Spacer(minLength: 0)
                bell(size: 38)
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 52)
        .frame(maxWidth: .infinity)
    }

    /// The bell is the review queue, and its dot means something: rows are
    /// waiting. A badge that never changes teaches people to ignore it.
    private func bell(size: CGFloat) -> some View {
        Button(action: onReview) {
            ZStack(alignment: .topTrailing) {
                Circle().fill(Field.glass)
                Circle().stroke(Field.glassEdge, lineWidth: 1)
                FinAiIcon(symbol: "bell.fill", tint: Field.ink(), size: size * 0.42)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if model.hasPending {
                    Circle()
                        .fill(Brand.red)
                        .overlay(Circle().stroke(Field.ink(), lineWidth: 1.5))
                        .frame(width: 9, height: 9)
                        .offset(x: -size * 0.25, y: size * 0.23)
                }
            }
            .frame(width: size, height: size)
            // Never under 44pt to touch, however small it is drawn.
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(model.notificationsLabel)
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Text(L.t(Strings.shared.dashboard_net_label))
                    .font(.headline.weight(.regular))
                    .foregroundColor(Field.ink(0.9))
                Button(action: model.toggleAmounts) {
                    FinAiIcon(
                        symbol: model.amountsHidden ? "eye.slash.fill" : "eye.fill",
                        tint: Field.ink(0.85),
                        size: 18
                    )
                    .frame(width: 40, height: 40)
                }
                .accessibilityLabel(model.hideToggleLabel)
                Spacer(minLength: 0)
                monthPill
            }

            FittedAmount(text: model.net, sizes: FittedAmount.hero, color: Field.ink())
                .frame(maxWidth: .infinity, alignment: .leading)

            // Absent, not "+0%", when there is no month to compare against.
            if let change = model.changeLabel {
                changePill(change, rose: model.netIsPositive).padding(.top, 12)
            }
        }
    }

    private var monthPill: some View {
        HStack(spacing: 0) {
            step("chevron.left", L.t(Strings.shared.dashboard_previous_month), model.canGoBack) {
                model.showPreviousMonth()
            }
            Text(model.monthLabel).font(.subheadline.weight(.medium)).foregroundColor(Field.ink())
            // Hidden rather than disabled on the month that is running.
            if model.canGoForward {
                step("chevron.right", L.t(Strings.shared.dashboard_next_month), true) {
                    model.showNextMonth()
                }
            } else {
                Color.clear.frame(width: 14, height: 1)
            }
        }
        .padding(.horizontal, 2)
        .background(Capsule().fill(Field.glass))
        .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
    }

    private func step(
        _ symbol: String, _ label: String, _ enabled: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            FinAiIcon(symbol: symbol, tint: Field.ink(enabled ? 1 : 0.4), size: 14)
                .frame(width: 40, height: 40)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func changePill(_ label: String, rose: Bool) -> some View {
        HStack(spacing: 6) {
            FinAiIcon(symbol: rose ? "arrow.up" : "arrow.down", tint: Field.ink(), size: 12)
            Text(label).font(.footnote.weight(.medium)).foregroundColor(Field.ink())
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Capsule().fill(Field.glass))
        .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
    }

    // MARK: - The four cards

    private var figures: some View {
        EqualGrid(spacing: 12) {
            FigureCard(
                symbol: "wallet.bifold.fill", accent: .income, dark: dark,
                label: L.t(Strings.shared.dashboard_income),
                amount: model.incomeAmount, detail: model.incomeExpectation
            )
            FigureCard(
                symbol: "creditcard.fill", accent: .expenses, dark: dark,
                label: L.t(Strings.shared.dashboard_expenses),
                amount: model.expensesAmount, detail: model.expensesExpectation,
                detailIsWarning: model.expensesAreOver
            )
            FigureCard(
                symbol: "dollarsign.circle.fill", accent: .investments, dark: dark,
                label: L.t(Strings.shared.dashboard_investments),
                amount: model.investmentsAmount, detail: model.investmentsMovement
            )
            FigureCard(
                symbol: "doc.text.fill", accent: .debts, dark: dark,
                label: L.t(Strings.shared.dashboard_debts),
                amount: model.debtsAmount, detail: model.debtsMovement
            )
        }
    }

    // MARK: - The ways in

    private var actions: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Field.ink(0.94))
                    FinAiIcon(symbol: "bolt.fill", tint: Brand.greenDeep, size: 22)
                }
                .frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.t(model.showsEmptyState
                        ? Strings.shared.dashboard_empty_title
                        : Strings.shared.dashboard_quick_actions_title))
                        .font(.headline.weight(.bold))
                        .foregroundColor(Field.ink())
                        .accessibilityAddTraits(.isHeader)
                    if model.showsEmptyState {
                        Text(L.t(Strings.shared.dashboard_empty_body))
                            .font(.subheadline)
                            .foregroundColor(Field.ink(0.85))
                    }
                }
                Spacer(minLength: 0)
            }

            VStack(spacing: 10) {
                ActionRow(
                    symbol: "doc.text.fill",
                    tile: Brand.green.opacity(0.16),
                    tint: dark ? Brand.green : Brand.greenDeep,
                    label: L.t(Strings.shared.import_entry),
                    surface: cardSurface,
                    action: onImportStatement
                )
                ActionRow(
                    symbol: "plus",
                    tile: .primary,
                    tint: Color(uiColor: .systemBackground),
                    label: L.t(Strings.shared.manual_entry_title),
                    surface: cardSurface,
                    action: onAddTransaction
                )
                ActionRow(
                    symbol: "list.bullet",
                    tile: .clear,
                    tint: .primary,
                    label: L.t(Strings.shared.review_entry),
                    surface: cardSurface,
                    action: onReview
                )
            }
            .padding(.top, 16)
        }
    }

    private var cardSurface: Color { dark ? Brand.surface : .white }

    private var loadFailed: some View {
        VStack(spacing: 8) {
            ErrorText(messageKey: model.errorKey)
            Button(action: { model.load() }) {
                Text(L.t(Strings.shared.dashboard_retry)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Brand.ground))
    }

    // MARK: - The sheet

    /// Rising over the field with the newest rows, and the month's commitments.
    private var sheet: some View {
        let rows = model.recentRows
        // Shown once there is anything at all: its header carries View all,
        // which is the way into every transaction.
        let showsRecent = !rows.isEmpty || !model.showsEmptyState
        return VStack(alignment: .leading, spacing: 0) {
            if showsRecent { recent(rows) }
            // Shown once the month is read, even with none: it is where one is added.
            if model.showsCommitments {
                commitments.padding(.top, showsRecent ? 28 : 0)
            }
            Button(L.t(Strings.shared.action_sign_out), action: onSignOut)
                .foregroundColor(Brand.textMuted)
                .tappableArea()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 12)
        }
        .frame(maxWidth: 560, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 30, topTrailingRadius: 30, style: .continuous)
                .fill(Brand.ground)
        )
    }

    private func recent(_ rows: [RecentRow]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .fill(Accent.income.icon.opacity(dark ? 0.2 : 0.12))
                    FinAiIcon(symbol: "list.bullet.rectangle.fill", tint: Accent.income.label(dark), size: 17)
                }
                .frame(width: 36, height: 36)
                .accessibilityHidden(true)
                Text(L.t(Strings.shared.dashboard_recent_title))
                    .font(.title3.weight(.bold))
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                // A pill rather than bare words, so it reads as the way in that it is.
                Button(action: onViewAll) {
                    HStack(spacing: 2) {
                        Text(L.t(Strings.shared.dashboard_recent_view_all)).font(.subheadline.weight(.semibold))
                        FinAiIcon(symbol: "chevron.right", tint: Accent.income.label(dark), size: 11)
                    }
                    .foregroundColor(Accent.income.label(dark))
                    .padding(.leading, 12)
                    .padding(.trailing, 9)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Accent.income.icon.opacity(dark ? 0.18 : 0.1)))
                    .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
            ForEach(rows) { row in recentLine(row) }
        }
    }

    /**
     One row as a soft card: the category's picture on a tile in its colour,
     with a small arrow on the tile's corner for the direction — into the
     account or out of it — beside the amount that says it again in words.
     */
    private func recentLine(_ row: RecentRow) -> some View {
        HStack(spacing: 12) {
            recentTile(row)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(.headline.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                Text(row.category)
                    .font(.subheadline)
                    .foregroundColor(row.isFiled ? Brand.textMuted : Accent.debts.label(dark))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(row.amount)
                    .font(.headline.weight(.bold))
                    .lineLimit(1)
                    // Green for money in; money out stays plain, and the sign
                    // says it in words, so colour is never the only cue.
                    .foregroundColor(row.isCredit ? Accent.income.label(dark) : .primary)
                Text(row.date).font(.caption).foregroundColor(Brand.textMuted).lineLimit(1)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(minHeight: 72)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous).fill(cardSurface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(dark ? Color.white.opacity(0.06) : Color(red: 0xE8 / 255, green: 0xF1 / 255, blue: 0xEC / 255), lineWidth: 1)
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.description)
    }

    private func recentTile(_ row: RecentRow) -> some View {
        let tint = row.icon.tint(dark)
        let direction = row.isCredit ? Accent.income.label(dark) : Accent.expenses.label(dark)
        return ZStack(alignment: .bottomTrailing) {
            ZStack {
                RoundedRectangle(cornerRadius: 15, style: .continuous).fill(tint.opacity(dark ? 0.2 : 0.12))
                FinAiIcon(symbol: row.icon.systemName, tint: tint, size: 21)
            }
            .frame(width: 46, height: 46)
            .frame(width: 48, height: 48, alignment: .topLeading)
            ZStack {
                Circle().fill(cardSurface)
                Circle().fill(direction).padding(2)
                Image(systemName: row.isCredit ? "arrow.down.left" : "arrow.up.right")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(.white)
            }
            .frame(width: 20, height: 20)
        }
        .accessibilityHidden(true)
    }

    private var commitments: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(L.t(Strings.shared.dashboard_commitments_title))
                    .font(.title3.weight(.bold))
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                Button(action: model.addCommitment) {
                    HStack(spacing: 4) {
                        FinAiIcon(symbol: "plus", tint: Brand.textMuted, size: 13)
                        Text(L.t(Strings.shared.commitments_add)).font(.subheadline.weight(.medium))
                    }
                    .foregroundColor(Brand.textMuted)
                    .frame(minHeight: 44)
                }
                .accessibilityLabel(L.t(Strings.shared.commitments_add_hint))
            }
            if let summary = model.commitmentsSummary {
                Text(summary).font(.subheadline).foregroundColor(Brand.textMuted).padding(.top, 2)
            }
            if model.commitmentRows.isEmpty {
                Text(L.t(Strings.shared.commitments_none))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .padding(.top, 4)
            }
            ForEach(Array(model.commitmentRows.enumerated()), id: \.element.id) { index, row in
                Button { model.editCommitment(row.id) } label: {
                    HStack(spacing: 14) {
                        ZStack {
                            Circle().fill(row.wasSeen ? Brand.green.opacity(0.18) : Color.gray.opacity(0.18))
                            if row.wasSeen {
                                FinAiIcon(symbol: "checkmark", tint: Brand.greenDeep, size: 13)
                            }
                        }
                        .frame(width: 28, height: 28)
                        // Decorative: `detail` already says this in words.
                        .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name).font(.headline.weight(.regular)).foregroundColor(.primary)
                            // Not red when unseen: we do not know it is unpaid,
                            // only that we did not find it.
                            Text(row.detail).font(.subheadline).foregroundColor(Brand.textMuted)
                        }
                        Spacer(minLength: 8)
                        Text(row.expected).font(.headline.weight(.regular)).foregroundColor(Brand.textMuted)
                        FinAiIcon(symbol: "chevron.right", tint: Brand.textMuted, size: 12)
                    }
                    .padding(.vertical, 8)
                    .frame(minHeight: 60)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(L.t(Strings.shared.commitment_edit_hint, row.name))
                if index != model.commitmentRows.count - 1 { Divider().overlay(Brand.border) }
            }
        }
    }
}

// MARK: - The field's colours

/**
 The green the top half sits on. Darker than the brand green on purpose: white
 text on `#22C55E` is about 2.3:1, unreadable for the small print here. These
 keep every white line at 4.5:1 or better. Mirrors Android's `Field`.
 */
enum Field {
    static let sheetOverlap: CGFloat = 28

    private static let lightTop = Color(red: 0x0F / 255, green: 0x5A / 255, blue: 0x30 / 255)
    private static let lightBottom = Color(red: 0x18 / 255, green: 0x80 / 255, blue: 0x4A / 255)
    private static let darkTop = Color(red: 0x0A / 255, green: 0x3A / 255, blue: 0x20 / 255)
    private static let darkBottom = Color(red: 0x07 / 255, green: 0x17 / 255, blue: 0x0E / 255)

    static func top(_ dark: Bool) -> Color { dark ? darkTop : lightTop }

    static func gradient(_ dark: Bool) -> LinearGradient {
        LinearGradient(
            colors: dark ? [darkTop, darkBottom] : [lightTop, lightBottom],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    /// White at a strength, for everything written on the field.
    static func ink(_ alpha: Double = 1) -> Color { Color.white.opacity(alpha) }

    /// Frosted glass: the pills and the bell sit on this.
    static let glass = Color.white.opacity(0.14)
    static let glassEdge = Color.white.opacity(0.22)
}

/// The soft hills across the field, with a glow behind the chart. Decorative.
struct Waves: View {
    var body: some View {
        GeometryReader { geometry in
            let w = geometry.size.width
            let h = geometry.size.height
            ZStack {
                RadialGradient(
                    colors: [Color(red: 0x34 / 255, green: 0xD2 / 255, blue: 0x7F / 255).opacity(0.32), .clear],
                    center: UnitPoint(x: 0.86, y: 0.2),
                    startRadius: 0,
                    endRadius: w * 0.75
                )
                hill(w, h, base: 0.30, lift: 0.06, phase: 0, alpha: 0.035)
                hill(w, h, base: 0.58, lift: 0.07, phase: 0.06, alpha: 0.04)
                hill(w, h, base: 0.80, lift: 0.05, phase: -0.04, alpha: 0.05)
            }
        }
    }

    private func hill(_ w: CGFloat, _ h: CGFloat, base: CGFloat, lift: CGFloat, phase: CGFloat, alpha: Double) -> some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: h))
            path.addLine(to: CGPoint(x: 0, y: h * base))
            path.addCurve(
                to: CGPoint(x: w * 0.62, y: h * (base - lift * 0.35)),
                control1: CGPoint(x: w * (0.18 + phase), y: h * (base - lift)),
                control2: CGPoint(x: w * (0.42 + phase), y: h * (base + lift * 0.6))
            )
            path.addCurve(
                to: CGPoint(x: w, y: h * (base - lift * 0.5)),
                control1: CGPoint(x: w * 0.78, y: h * (base - lift * 0.9)),
                control2: CGPoint(x: w * 0.9, y: h * (base + lift * 0.2))
            )
            path.addLine(to: CGPoint(x: w, y: h))
            path.closeSubpath()
        }
        .fill(Color.white.opacity(alpha))
    }
}

// MARK: - The chart

/**
 The month day by day: the running balance of everything in minus everything
 out, from the 1st. The geometry is shared (`DashboardTrend.daily`). The axis
 is the whole calendar month, so a month still running stops part-way with the
 rest of it ahead; the dashed line is zero, which the scale always includes,
 so a stretch below it reads as behind for the month. The latest day is
 marked, with its figure on a pill. Mirrors Android's `DailyChart`.
 */
private struct DailyChart: View {
    let chart: DashboardTrend.DailyChart
    let ticks: [String]
    let value: String?
    let isLoss: Bool

    private let side: CGFloat = 18
    /// Room above the line for the latest day's figure.
    private let top: CGFloat = 26
    /// Room below for the days.
    private let bottom: CGFloat = 22

    var body: some View {
        GeometryReader { geometry in
            canvas(geometry.size)
        }
    }

    private func canvas(_ size: CGSize) -> some View {
        let pts: [CGPoint] = chart.days.map { at($0, size) }
        let zero: CGFloat = y(chart.zero, size)
        let dot: CGPoint = pts[Int(chart.markerIndex)]
        return ZStack(alignment: .topLeading) {
            rules(size)
            Path { path in
                path.move(to: CGPoint(x: side, y: zero))
                path.addLine(to: CGPoint(x: size.width - side, y: zero))
            }
            .stroke(Field.ink(0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            if pts.count > 1 { line(pts, zero: zero, size) }
            marker(dot, size)
            if let value { pill(value, dot: dot, size) }
            ForEach(tickPlacements(size), id: \.index) { placed in
                Text(ticks[placed.index])
                    .font(.caption2.weight(placed.index == 0 || placed.index == ticks.count - 1 ? .medium : .regular))
                    .foregroundColor(Field.ink(0.75))
                    .fixedSize()
                    .position(placed.centre)
            }
        }
    }

    private func x(_ fraction: Double, _ size: CGSize) -> CGFloat {
        side + CGFloat(fraction) * (size.width - side * 2)
    }

    private func y(_ fraction: Double, _ size: CGSize) -> CGFloat {
        let low = size.height - bottom
        return low - CGFloat(fraction) * (low - top)
    }

    private func at(_ day: DashboardTrend.Day, _ size: CGSize) -> CGPoint {
        CGPoint(x: x(day.x, size), y: y(day.y, size))
    }

    /// A faint rule at each named day, so the weeks can be read off.
    private func rules(_ size: CGSize) -> some View {
        Path { path in
            for tick in chart.ticks {
                let at = x(tick.x, size)
                path.move(to: CGPoint(x: at, y: top))
                path.addLine(to: CGPoint(x: at, y: size.height - bottom))
            }
        }
        .stroke(Field.ink(0.07), lineWidth: 1)
    }

    @ViewBuilder
    private func line(_ pts: [CGPoint], zero: CGFloat, _ size: CGSize) -> some View {
        let line = Path { path in
            path.move(to: pts[0])
            // Control points level with each end, so the curve never
            // overshoots a day above or below its value.
            for (a, b) in zip(pts, pts.dropFirst()) {
                let mid = (a.x + b.x) / 2
                path.addCurve(to: b, control1: CGPoint(x: mid, y: a.y), control2: CGPoint(x: mid, y: b.y))
            }
        }
        let fill = Path { path in
            path.addPath(line)
            path.addLine(to: CGPoint(x: pts[pts.count - 1].x, y: zero))
            path.addLine(to: CGPoint(x: pts[0].x, y: zero))
            path.closeSubpath()
        }
        let height = max(size.height, 1)
        fill.fill(
            LinearGradient(
                colors: [Field.ink(0.24), Field.ink(0.02)],
                startPoint: UnitPoint(x: 0.5, y: top / height),
                endPoint: UnitPoint(x: 0.5, y: (size.height - bottom) / height)
            )
        )
        line.stroke(Field.ink(0.95), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
    }

    @ViewBuilder
    private func marker(_ dot: CGPoint, _ size: CGSize) -> some View {
        Path { path in
            path.move(to: dot)
            path.addLine(to: CGPoint(x: dot.x, y: size.height - bottom))
        }
        .stroke(Field.ink(0.5), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
        Circle().fill(Field.ink(0.3)).frame(width: 18, height: 18).position(dot)
        Circle().fill(Field.ink()).frame(width: 10, height: 10).position(dot)
    }

    /// Below a month behind, where the line dipped to — unless there is no
    /// room there, when it goes above rather than over the dot.
    private func pill(_ value: String, dot: CGPoint, _ size: CGSize) -> some View {
        let width = Self.width(value) + 14
        let height: CGFloat = 20
        let x = min(max(dot.x, width / 2), size.width - width / 2)
        let above = dot.y - 12 - height / 2
        let below = dot.y + 12 + height / 2
        let fitsBelow = below + height / 2 <= size.height - bottom
        let y = (isLoss && fitsBelow) || (above < height / 2 && fitsBelow) ? below : max(above, height / 2)
        return Text(value)
            .font(.caption2.weight(.bold))
            .foregroundColor(Field.ink())
            .fixedSize()
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Capsule().fill(Field.glass))
            .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
            .position(x: x, y: y)
    }

    private struct Placed {
        let index: Int
        let centre: CGPoint
    }

    /// The days that fit along the bottom; the month's two ends first, so they
    /// are never the ones left off.
    private func tickPlacements(_ size: CGSize) -> [Placed] {
        let count = min(ticks.count, chart.ticks.count)
        guard count > 0 else { return [] }
        var order = [0, count - 1]
        order += (1 ..< max(count - 1, 1)).filter { $0 != count - 1 }
        var taken: [ClosedRange<CGFloat>] = []
        var out: [Placed] = []
        for index in order where !out.contains(where: { $0.index == index }) {
            let width = Self.width(ticks[index])
            let centre = min(max(x(chart.ticks[index].x, size), width / 2), size.width - width / 2)
            let span = (centre - width / 2 - 4)...(centre + width / 2 + 4)
            if taken.contains(where: { $0.overlaps(span) }) { continue }
            taken.append(span)
            out.append(Placed(index: index, centre: CGPoint(x: centre, y: size.height - 9)))
        }
        return out
    }

    /// A label's width in the Dynamic Type font it is drawn in.
    private static func width(_ text: String) -> CGFloat {
        let base = UIFont.preferredFont(forTextStyle: .caption2)
        let font = UIFont.systemFont(ofSize: base.pointSize, weight: .bold)
        return ceil((text as NSString).size(withAttributes: [.font: font]).width)
    }
}

/**
 A figure at the largest of `sizes` that fits on one line, or — when even the
 smallest does not — wrapped at that smallest size.

 Never clipped. `minimumScaleFactor` stops at its floor and truncates whatever
 is left, and an amount with its last digits missing is a different amount. A
 wrapped figure is ugly; a truncated one is wrong. Sizes scale with Dynamic
 Type. Mirrors Android's `FittedAmount`.
 */
private struct FittedAmount: View {
    let text: String
    let sizes: [CGFloat]
    var color: Color = .primary
    /// Cut short at the smallest size rather than wrapping: inside a card,
    /// one line is the rule.
    var oneLine = false
    /// Called when even the smallest size will not fit on one line.
    var onOverflow: (() -> Void)?

    static let hero: [CGFloat] = [38, 34, 30, 27, 24, 21, 18]
    static let beside: [CGFloat] = [20, 19, 18, 17, 16, 15, 14]
    static let stacked: [CGFloat] = [22, 20, 18, 16, 14, 12, 10]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            ForEach(sizes, id: \.self) { size in
                line(size).lineLimit(1).fixedSize(horizontal: true, vertical: false)
            }
            if let onOverflow {
                Color.clear.frame(height: 1).onAppear(perform: onOverflow)
            } else if oneLine {
                line(sizes.last ?? 14).lineLimit(1).truncationMode(.tail)
            } else {
                line(sizes.last ?? 14).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func line(_ size: CGFloat) -> some View {
        Text(text)
            .font(.system(size: UIFontMetrics.default.scaledValue(for: size), weight: .bold))
            .foregroundColor(color)
    }
}

// MARK: - Cards

/// One card's colours. Labels use the deeper tone of each accent on a white
/// card — the bright ones read at under 3:1 there, amber at 1.7:1 — and the
/// bright tone on a dark card. Mirrors Android's `Accent`.
private enum Accent {
    case income, expenses, investments, debts

    var icon: Color {
        switch self {
        case .income: Brand.green
        case .expenses: Brand.red
        case .investments: Brand.blue
        case .debts: Brand.amber
        }
    }

    func label(_ dark: Bool) -> Color {
        switch self {
        case .income: dark ? Brand.green : Color(red: 0x15 / 255, green: 0x80 / 255, blue: 0x3D / 255)
        case .expenses: dark ? Color(red: 0xF8 / 255, green: 0x71 / 255, blue: 0x71 / 255)
            : Color(red: 0xDC / 255, green: 0x26 / 255, blue: 0x26 / 255)
        case .investments: dark ? Color(red: 0x60 / 255, green: 0xA5 / 255, blue: 0xFA / 255)
            : Color(red: 0x25 / 255, green: 0x63 / 255, blue: 0xEB / 255)
        case .debts: dark ? Brand.amber : Color(red: 0xB4 / 255, green: 0x53 / 255, blue: 0x09 / 255)
        }
    }
}

/**
 Two columns of cells that are all the same size: the tallest card's height is
 every card's height, so the four read as one set however their text wraps —
 including at the largest Dynamic Type sizes, where a fixed height would clip.
 */
private struct EqualGrid: Layout {
    var spacing: CGFloat

    private func cell(_ proposal: ProposedViewSize, _ subviews: Subviews) -> CGSize {
        let width = ((proposal.width ?? 360) - spacing) / 2
        let height = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }.max() ?? 0
        return CGSize(width: width, height: height)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let size = cell(proposal, subviews)
        let rows = CGFloat((subviews.count + 1) / 2)
        return CGSize(
            width: proposal.width ?? size.width * 2 + spacing,
            height: rows * size.height + max(rows - 1, 0) * spacing
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let size = cell(ProposedViewSize(width: bounds.width, height: nil), subviews)
        for (index, subview) in subviews.enumerated() {
            let column = CGFloat(index % 2)
            let row = CGFloat(index / 2)
            subview.place(
                at: CGPoint(
                    x: bounds.minX + column * (size.width + spacing),
                    y: bounds.minY + row * (size.height + spacing)
                ),
                proposal: ProposedViewSize(size)
            )
        }
    }
}

/// One of the four cards: a tinted icon tile, a label, a figure, and what it is
/// measured against.
///
/// The design sets the figure beside the icon, which leaves it about 80pt.
/// When it cannot sit there at a readable size — a large Dynamic Type size, or
/// a seven-figure balance — it moves to its own full-width line rather than
/// shrinking past reading or losing digits. Mirrors Android's `FigureCard`.
private struct FigureCard: View {
    let symbol: String
    let accent: Accent
    let dark: Bool
    let label: String
    let amount: String
    var detail: String?
    var detailIsWarning = false

    @Environment(\.dynamicTypeSize) private var typeSize
    /// Set when the figure would not fit beside the icon even at its smallest.
    @State private var tooLongBeside = false

    private var stacked: Bool { typeSize >= .xxxLarge || tooLongBeside }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(accent.icon.opacity(0.16))
                    FinAiIcon(symbol: symbol, tint: accent.icon, size: 20)
                }
                .frame(width: 42, height: 42)
                if !stacked {
                    VStack(alignment: .leading, spacing: 2) {
                        labelText
                        FittedAmount(text: amount, sizes: FittedAmount.beside) { tooLongBeside = true }
                    }
                }
                Spacer(minLength: 0)
                FinAiIcon(symbol: "chevron.right", tint: Brand.textMuted.opacity(0.7), size: 13)
                    .padding(.top, 3)
            }
            if stacked {
                labelText.padding(.top, 10)
                FittedAmount(text: amount, sizes: FittedAmount.stacked, oneLine: true).padding(.top, 2)
            }
            if let detail {
                // One line, as the four cards are: smaller first, then cut
                // short. A wrapped second line would make them uneven again.
                Text(detail)
                    .font(.caption.weight(detailIsWarning ? .semibold : .regular))
                    .foregroundColor(detailIsWarning ? accent.label(dark) : Brand.textMuted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .truncationMode(.tail)
                    .padding(.top, 10)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(dark ? Brand.surface : .white)
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(accent.icon.opacity(dark ? 0.10 : 0.05))
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.white.opacity(dark ? 0.06 : 0.7), lineWidth: 1)
        )
    }

    /// "Investments" is the longest; it shrinks a little rather than losing its end.
    private var labelText: some View {
        Text(label)
            .font(.subheadline.weight(.semibold))
            .foregroundColor(accent.label(dark))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }
}

private struct ActionRow: View {
    let symbol: String
    let tile: Color
    let tint: Color
    let label: String
    let surface: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).fill(tile)
                    FinAiIcon(symbol: symbol, tint: tint, size: 18)
                }
                .frame(width: 36, height: 36)
                Text(label).font(.headline.weight(.medium)).foregroundColor(.primary)
                Spacer(minLength: 0)
                FinAiIcon(symbol: "arrow.right", tint: Brand.textMuted, size: 16)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(surface))
        }
        .buttonStyle(.plain)
    }
}

/// Adding or editing a commitment: its name and monthly amount, which is all one has.
private struct CommitmentEditor: View {
    @ObservedObject var model: DashboardViewModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    WizardField(
                        label: L.t(Strings.shared.commitment_edit_name),
                        placeholder: "",
                        autocapitalization: .sentences,
                        isError: false,
                        submitLabel: .next,
                        onSubmit: {},
                        text: Binding(get: { model.commitmentDraft.name }, set: { model.setCommitmentName($0) })
                    )
                    AmountField(
                        label: L.t(Strings.shared.commitment_edit_amount),
                        symbol: model.commitmentCurrencySymbol,
                        placeholder: model.commitmentAmountPlaceholder,
                        isError: false,
                        large: false,
                        text: Binding(get: { model.commitmentDraft.amount }, set: { model.setCommitmentAmount($0) })
                    )
                }
                Section {
                    ErrorText(messageKey: model.commitmentNotice)
                    GradientButton(
                        title: L.t(Strings.shared.commitment_edit_save),
                        enabled: model.canSaveCommitment,
                        busy: model.commitmentSaving
                    ) {
                        dismissKeyboard()
                        model.saveCommitment()
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                // Only for one that exists. Asked again before anything is sent.
                if model.editingCommitment != nil {
                    Section {
                        Button(L.t(Strings.shared.commitment_delete), role: .destructive) {
                            model.askDeleteCommitment()
                        }
                        .disabled(model.commitmentSaving)
                        .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
            }
            .navigationTitle(L.t(model.commitmentTitleKey))
            .alert(model.deleteCommitmentTitle, isPresented: $model.confirmingCommitmentDelete) {
                Button(L.t(Strings.shared.commitment_delete_confirm), role: .destructive) { model.deleteCommitment() }
                Button(L.t(Strings.shared.commitment_edit_cancel), role: .cancel) {}
            } message: {
                Text(L.t(Strings.shared.commitment_delete_confirm_body))
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.commitment_edit_cancel)) { model.cancelCommitment() }
                        .disabled(model.commitmentSaving)
                }
                // Number pads have no return key.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { dismissKeyboard() }
                }
            }
        }
        .interactiveDismissDisabled(model.commitmentSaving)
    }
}


/// The faint pictures on the field, as in the design: a tilted card in the top
/// corner, a rising bar chart with its arrow down the right side, and soft
/// rounds lower down. White at a few percent, so they read as texture and never
/// compete with a figure. Fixed to the screen, not the scroll; decorative.
/// Mirrors Android's `FieldVectors`.
struct FieldVectors: View {
    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height
            func ink(_ alpha: Double) -> GraphicsContext.Shading { .color(.white.opacity(alpha)) }

            // A card, tilted, half off the top right.
            var card = context
            let centre = CGPoint(x: w * 0.78, y: 110)
            card.translateBy(x: centre.x, y: centre.y)
            card.rotate(by: .degrees(-16))
            let rect = CGRect(x: -105, y: -67, width: 210, height: 134)
            let shape = Path(roundedRect: rect, cornerRadius: 20)
            card.fill(shape, with: ink(0.08))
            card.stroke(shape, with: ink(0.13), lineWidth: 1.5)
            card.fill(Path(CGRect(x: rect.minX, y: rect.minY + 30, width: rect.width, height: 22)), with: ink(0.07))
            card.fill(
                Path(roundedRect: CGRect(x: rect.minX + 22, y: rect.minY + 68, width: 34, height: 26), cornerRadius: 6),
                with: ink(0.10)
            )

            // A bar chart down the right side, rising, with its arrow.
            let base = h * 0.46
            for (index, height) in [34.0, 56.0, 82.0, 112.0].enumerated() {
                let x = w - 118 + CGFloat(index) * 26
                context.fill(
                    Path(roundedRect: CGRect(x: x, y: base - height, width: 18, height: height), cornerRadius: 5),
                    with: ink(0.06 + Double(index) * 0.01)
                )
            }
            var arrow = Path()
            arrow.move(to: CGPoint(x: w - 130, y: base - 40))
            arrow.addLine(to: CGPoint(x: w - 92, y: base - 76))
            arrow.addLine(to: CGPoint(x: w - 70, y: base - 64))
            arrow.addLine(to: CGPoint(x: w - 30, y: base - 128))
            arrow.move(to: CGPoint(x: w - 30, y: base - 128))
            arrow.addLine(to: CGPoint(x: w - 46, y: base - 122))
            arrow.move(to: CGPoint(x: w - 30, y: base - 128))
            arrow.addLine(to: CGPoint(x: w - 33, y: base - 111))
            context.stroke(arrow, with: ink(0.12), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))

            // Soft rounds lower down.
            context.fill(Path(ellipseIn: CGRect(x: -170, y: h * 0.78 - 150, width: 300, height: 300)), with: ink(0.04))
            context.fill(Path(ellipseIn: CGRect(x: w * 0.92 - 110, y: h * 0.9 - 110, width: 220, height: 220)), with: ink(0.035))
            context.fill(Path(ellipseIn: CGRect(x: w * 0.18 - 7, y: h * 0.36 - 7, width: 14, height: 14)), with: ink(0.05))
            context.fill(Path(ellipseIn: CGRect(x: w * 0.62 - 4, y: h * 0.62 - 4, width: 8, height: 8)), with: ink(0.05))
        }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

// MARK: - Collapsing header

/**
 How far Home's header has shrunk into the bar, 0 to 1 — observed by the
 header's fade and the bar alone.

 Kept out of `DashboardView`'s state on purpose. As `@State` there, every point
 scrolled re-ran the whole screen's body — chart, cards, rows — sixty times a
 second, which is the lag scrolling home had. Here only the two views that read
 `progress` redraw, and only while it changes: it is clamped and stepped, so
 past the header's own height scrolling changes nothing at all.
 */
@Observable
final class HeaderCollapse {
    /// The scroll over which the header shrinks into the bar: about its own height.
    static let distance: CGFloat = 64

    private(set) var progress: CGFloat = 0

    /// Where the header sat before any scrolling: the first position seen.
    @ObservationIgnored private var restingTop: CGFloat?

    func track(top: CGFloat) {
        let rest = restingTop ?? top
        if restingTop == nil { restingTop = top }
        let raw = min(max((rest - top) / Self.distance, 0), 1)
        // Thirty-two steps: smooth to the eye, and no update for a change too
        // small to see.
        let stepped = (raw * 32).rounded() / 32
        if stepped != progress { progress = stepped }
    }
}

/// The big header, fading out over the first half of the collapse.
private struct HeaderFade: ViewModifier {
    let collapse: HeaderCollapse

    func body(content: Content) -> some View {
        content.opacity(Double(1 - min(collapse.progress * 2, 1)))
    }
}

/**
 The slim bar the header shrinks into, fading in over the second half. Solid,
 because figures pass under it, and reaching up under the status bar. Mirrors
 Android's `CompactHeader`.
 */
private struct CollapsingBar<Content: View>: View {
    let collapse: HeaderCollapse
    let dark: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        let opacity = Double(min(max((collapse.progress - 0.5) * 2, 0), 1))
        content()
            .background(
                Field.top(dark)
                    .shadow(color: .black.opacity(0.18 * opacity), radius: 6, y: 2)
                    .ignoresSafeArea(edges: .top)
            )
            .opacity(opacity)
            // A bar at nothing opacity must not catch a tap meant for the header.
            .allowsHitTesting(opacity > 0)
            .accessibilityHidden(opacity == 0)
    }
}

