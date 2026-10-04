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

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                field
                sheet.padding(.top, -Field.sheetOverlap)
            }
        }
        .scrollBounceBehavior(.basedOnSize)
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
        .onDisappear { model.unbind() }
        .sheet(isPresented: commitmentShown) { CommitmentEditor(model: model) }
    }

    private var commitmentShown: Binding<Bool> {
        Binding(get: { model.showsCommitmentEditor }, set: { if !$0 { model.cancelCommitment() } })
    }

    // MARK: - The field

    private var field: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            hero.padding(.top, 26)
            if let chart = model.chart {
                TrendChart(chart: chart, labels: model.chartLabels)
                    .frame(height: 172)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(
                        L.t(Strings.shared.dashboard_trend_label) + ": "
                            + model.trendDescriptions.joined(separator: "; ")
                    )
                    .padding(.top, 18)
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
            // The field runs under the status bar, as in the design; its
            // contents do not.
            .ignoresSafeArea(edges: .top)
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

            // The bell is the review queue, and its dot means something: rows
            // are waiting. A badge that never changes teaches people to ignore
            // it.
            Button(action: onReview) {
                ZStack(alignment: .topTrailing) {
                    Circle().fill(Field.glass)
                    Circle().stroke(Field.glassEdge, lineWidth: 1)
                    FinAiIcon(symbol: "bell.fill", tint: Field.ink(), size: 20)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    if model.hasPending {
                        Circle()
                            .fill(Brand.red)
                            .overlay(Circle().stroke(Field.ink(), lineWidth: 1.5))
                            .frame(width: 9, height: 9)
                            .offset(x: -12, y: 11)
                    }
                }
                .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.notificationsLabel)
        }
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
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(L.t(Strings.shared.dashboard_recent_title))
                    .font(.title3.weight(.bold))
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                Button(action: onViewAll) {
                    HStack(spacing: 2) {
                        Text(L.t(Strings.shared.dashboard_recent_view_all)).font(.subheadline.weight(.medium))
                        FinAiIcon(symbol: "chevron.right", tint: Brand.textMuted, size: 12)
                    }
                    .foregroundColor(Brand.textMuted)
                    .frame(minHeight: 44)
                }
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                recentLine(row)
                if index != rows.count - 1 { Divider().overlay(Brand.border) }
            }
        }
    }

    private func recentLine(_ row: RecentRow) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(.headline.weight(.regular)).lineLimit(1).truncationMode(.tail)
                Text(row.date).font(.subheadline).foregroundColor(Brand.textMuted)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(row.amount)
                    .font(.headline.weight(.semibold))
                    // Green for money in; money out stays plain, and the sign
                    // says it in words, so colour is never the only cue.
                    .foregroundColor(row.isCredit ? Accent.income.label(dark) : .primary)
                Text(row.category)
                    .font(.subheadline)
                    .foregroundColor(row.isFiled ? Brand.textMuted : Accent.debts.label(dark))
                    .lineLimit(1)
                    .frame(maxWidth: 160, alignment: .trailing)
            }
        }
        .padding(.vertical, 10)
        .frame(minHeight: 64)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.description)
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
private enum Field {
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
private struct Waves: View {
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
 Net by month, as a line with every figure written on it. The geometry is
 shared (`DashboardTrend`): a gap is never drawn through, so no line invents a
 value for a month nobody recorded; a loss sits below a gain; and when the
 months cross zero a dashed line marks it, so a month in the red is visible as
 one. Each recorded month carries its rounded figure, each month its name; the
 month in view is the right-hand end, marked, with its figure on a pill.

 Labels that would collide are left off — the month in view's never — rather
 than drawn over one another. Every figure is in the accessibility label too.
 Mirrors Android's `TrendChart`.
 */
private struct TrendChart: View {
    let chart: DashboardTrend.Chart
    let labels: [ChartLabel]

    private let side: CGFloat = 22
    /// Room above the line for the figures written over it.
    private let top: CGFloat = 34
    /// Room below for the month names.
    private let bottom: CGFloat = 30

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            ZStack(alignment: .topLeading) {
                if let zero = chart.zero?.doubleValue {
                    Path { path in
                        path.move(to: CGPoint(x: side, y: y(zero, size)))
                        path.addLine(to: CGPoint(x: size.width - side, y: y(zero, size)))
                    }
                    .stroke(Field.ink(0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                }
                ForEach(Array(chart.segments.enumerated()), id: \.offset) { _, run in
                    segment(run.map { $0.intValue }, size)
                }
                if let index = chart.markerIndex?.intValue {
                    marker(at(chart.points[index], size), size)
                }
                ForEach(placements(size), id: \.key) { placed in
                    placed.view.position(placed.centre)
                }
            }
        }
    }

    private func y(_ fraction: Double, _ size: CGSize) -> CGFloat {
        let low = size.height - bottom
        return low - CGFloat(fraction) * (low - top)
    }

    private func at(_ point: DashboardTrend.Point, _ size: CGSize) -> CGPoint {
        CGPoint(
            x: side + CGFloat(point.x) * (size.width - side * 2),
            y: y(point.y?.doubleValue ?? 0, size)
        )
    }

    @ViewBuilder
    private func segment(_ run: [Int], _ size: CGSize) -> some View {
        let pts = run.map { at(chart.points[$0], size) }
        let floor = size.height - bottom
        if pts.count > 1 {
            let line = Path { path in
                path.move(to: pts[0])
                // Control points level with each end, so the curve never
                // overshoots a month above or below its value.
                for (a, b) in zip(pts, pts.dropFirst()) {
                    let mid = (a.x + b.x) / 2
                    path.addCurve(to: b, control1: CGPoint(x: mid, y: a.y), control2: CGPoint(x: mid, y: b.y))
                }
            }
            let fill = Path { path in
                path.addPath(line)
                path.addLine(to: CGPoint(x: pts[pts.count - 1].x, y: floor))
                path.addLine(to: CGPoint(x: pts[0].x, y: floor))
                path.closeSubpath()
            }
            fill.fill(
                LinearGradient(
                    colors: [Field.ink(0.20), Field.ink(0)],
                    startPoint: UnitPoint(x: 0.5, y: top / max(size.height, 1)),
                    endPoint: UnitPoint(x: 0.5, y: floor / max(size.height, 1))
                )
            )
            line.stroke(Field.ink(0.95), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        }
        ForEach(Array(pts.enumerated()), id: \.offset) { _, point in
            Circle().fill(Field.ink(0.95)).frame(width: 7, height: 7).position(point)
        }
    }

    @ViewBuilder
    private func marker(_ dot: CGPoint, _ size: CGSize) -> some View {
        Path { path in
            path.move(to: dot)
            path.addLine(to: CGPoint(x: dot.x, y: size.height - bottom))
        }
        .stroke(Field.ink(0.5), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
        Circle().fill(Field.ink(0.3)).frame(width: 20, height: 20).position(dot)
        Circle().fill(Field.ink()).frame(width: 11, height: 11).position(dot)
    }

    private struct Placed {
        let key: String
        let centre: CGPoint
        let view: AnyView
    }

    /// Every label that fits, centred where it belongs; the month in view first.
    private func placements(_ size: CGSize) -> [Placed] {
        let marker = chart.markerIndex?.intValue
        let order = (marker.map { [$0] } ?? []) + labels.indices.filter { $0 != marker }
        let gap: CGFloat = 4
        var out: [Placed] = []

        var takenValues: [ClosedRange<CGFloat>] = []
        for index in order {
            guard let value = labels[index].value, chart.points[index].y != nil else { continue }
            let marked = index == marker
            let width = Self.width(value, bold: marked) + (marked ? 14 : 0)
            let height: CGFloat = marked ? 20 : 16
            let dot = at(chart.points[index], size)
            let x = min(max(dot.x, width / 2), size.width - width / 2)
            let span = (x - width / 2 - gap)...(x + width / 2 + gap)
            if takenValues.contains(where: { $0.overlaps(span) }) { continue }
            takenValues.append(span)
            // Below a month in the red, where the line dipped to, rather than
            // across the line it is a label for.
            let y = labels[index].isLoss
                ? min(dot.y + 10 + height / 2, size.height - bottom - height / 2)
                : max(dot.y - 10 - height / 2, height / 2)
            out.append(Placed(key: "v\(index)", centre: CGPoint(x: x, y: y), view: AnyView(valueLabel(value, marked: marked))))
        }

        var takenMonths: [ClosedRange<CGFloat>] = []
        for index in order {
            let month = labels[index].month
            let width = Self.width(month, bold: index == marker)
            let x = min(max(at(chart.points[index], size).x, width / 2), size.width - width / 2)
            let span = (x - width / 2 - gap)...(x + width / 2 + gap)
            if takenMonths.contains(where: { $0.overlaps(span) }) { continue }
            takenMonths.append(span)
            let recorded = chart.points[index].y != nil
            out.append(Placed(
                key: "m\(index)",
                centre: CGPoint(x: x, y: size.height - 9),
                view: AnyView(
                    Text(month)
                        .font(.caption2.weight(index == marker ? .bold : .regular))
                        // A month with nothing recorded is named, but quietly.
                        .foregroundColor(Field.ink(recorded ? 0.8 : 0.45))
                        .fixedSize()
                )
            ))
        }
        return out
    }

    @ViewBuilder
    private func valueLabel(_ value: String, marked: Bool) -> some View {
        let text = Text(value)
            .font(.caption2.weight(marked ? .bold : .medium))
            .foregroundColor(Field.ink(marked ? 1 : 0.85))
            .fixedSize()
        if marked {
            text
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(Capsule().fill(Field.glass))
                .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
        } else {
            text
        }
    }

    /// A label's width in the Dynamic Type font it is drawn in.
    private static func width(_ text: String, bold: Bool) -> CGFloat {
        let base = UIFont.preferredFont(forTextStyle: .caption2)
        let font = UIFont.systemFont(ofSize: base.pointSize, weight: bold ? .bold : .medium)
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

