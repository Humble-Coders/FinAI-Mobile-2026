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
    }

    // MARK: - The field

    private var field: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            hero.padding(.top, 26)
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

            // Beside the line when there is one, so the two do not cross.
            FractionOfWidth(fraction: model.chart == nil ? 1 : 0.6) {
                FittedAmount(text: model.net, sizes: FittedAmount.hero, color: Field.ink())
            }

            Spacer(minLength: 14)
            // Absent, not "+0%", when there is no month to compare against.
            if let change = model.changeLabel {
                changePill(change, rose: model.netIsPositive)
            }
        }
        // Tall enough to give the line room between the pills — but only when
        // there is a line, or an empty month opens with a hole in it.
        .frame(minHeight: model.chart == nil ? 0 : 156, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Behind the words, across the right of the hero and out to the
        // screen's edge, as in the design.
        .background {
            if let chart = model.chart {
                TrendChart(chart: chart)
                    .padding(.trailing, -20)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(
                        L.t(Strings.shared.dashboard_trend_label) + ": "
                            + model.trendDescriptions.joined(separator: "; ")
                    )
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
            if !model.commitmentRows.isEmpty {
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
            Text(L.t(Strings.shared.dashboard_commitments_title))
                .font(.title3.weight(.bold))
                .accessibilityAddTraits(.isHeader)
            if let summary = model.commitmentsSummary {
                Text(summary).font(.subheadline).foregroundColor(Brand.textMuted).padding(.top, 2)
            }
            ForEach(Array(model.commitmentRows.enumerated()), id: \.element.id) { index, row in
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
                        Text(row.name).font(.headline.weight(.regular))
                        // Not red when unseen: we do not know it is unpaid,
                        // only that we did not find it.
                        Text(row.detail).font(.subheadline).foregroundColor(Brand.textMuted)
                    }
                    Spacer(minLength: 8)
                    Text(row.expected).font(.headline.weight(.regular)).foregroundColor(Brand.textMuted)
                }
                .padding(.vertical, 8)
                .frame(minHeight: 60)
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
 The months as a soft line, with the month in view marked. The geometry is
 shared (`DashboardTrend`): a gap is never drawn through, so no line invents a
 value for a month nobody recorded, and a loss sits below a gain.

 It occupies the right of the hero, fading in from the left so the words in
 front stay readable, below the month pill and above the change pill. The
 marker is the month in view — always the right-hand end — whose figure is the
 large one beside it, so it carries no caption. Mirrors Android's `TrendChart`.
 */
private struct TrendChart: View {
    let chart: DashboardTrend.Chart

    /// Where the line begins, as a fraction of the width.
    private let start: CGFloat = 0.38

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            ZStack(alignment: .topLeading) {
                ForEach(Array(chart.segments.enumerated()), id: \.offset) { _, run in
                    segment(run.map { $0.intValue }, in: size)
                }
                if let index = chart.markerIndex?.intValue {
                    marker(at: point(chart.points[index], in: size), in: size)
                }
            }
        }
        .mask(
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .clear, location: start),
                    .init(color: .black, location: start + 0.22),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        )
    }

    private func point(_ p: DashboardTrend.Point, in size: CGSize) -> CGPoint {
        let left = size.width * start + 24
        let right = size.width - 28
        let top: CGFloat = 58
        let bottom = size.height - 52
        return CGPoint(
            x: left + CGFloat(p.x) * (right - left),
            y: bottom - CGFloat(p.y?.doubleValue ?? 0) * (bottom - top)
        )
    }

    @ViewBuilder
    private func segment(_ run: [Int], in size: CGSize) -> some View {
        let pts = run.map { point(chart.points[$0], in: size) }
        if pts.count == 1 {
            Circle().fill(Field.ink(0.9)).frame(width: 6, height: 6).position(pts[0])
        } else {
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
                path.addLine(to: CGPoint(x: pts[pts.count - 1].x, y: size.height))
                path.addLine(to: CGPoint(x: pts[0].x, y: size.height))
                path.closeSubpath()
            }
            // Fades well before the bottom, so where a gap ends a run the fill
            // does not stand as a hard-edged column.
            fill.fill(
                LinearGradient(
                    colors: [Field.ink(0.18), Field.ink(0)],
                    startPoint: UnitPoint(x: 0.5, y: 58 / max(size.height, 1)),
                    endPoint: UnitPoint(x: 0.5, y: (58 + (size.height - 58) * 0.7) / max(size.height, 1))
                )
            )
            line.stroke(Field.ink(0.95), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        }
    }

    @ViewBuilder
    private func marker(at dot: CGPoint, in size: CGSize) -> some View {
        Path { path in
            path.move(to: dot)
            path.addLine(to: CGPoint(x: dot.x, y: size.height))
        }
        .stroke(Field.ink(0.6), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
        Circle().fill(Field.ink(0.3)).frame(width: 20, height: 20).position(dot)
        Circle().fill(Field.ink()).frame(width: 11, height: 11).position(dot)
    }
}

/// Gives its content `fraction` of the width it is offered, at the leading edge.
private struct FractionOfWidth: Layout {
    var fraction: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        let child = subviews.first?.sizeThatFits(ProposedViewSize(width: width * fraction, height: nil)) ?? .zero
        return CGSize(width: width, height: child.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(
            at: bounds.origin,
            proposal: ProposedViewSize(width: bounds.width * fraction, height: nil)
        )
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
                FittedAmount(text: amount, sizes: FittedAmount.stacked).padding(.top, 2)
            }
            if let detail {
                Text(detail)
                    .font(.caption.weight(detailIsWarning ? .semibold : .regular))
                    .foregroundColor(detailIsWarning ? accent.label(dark) : Brand.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
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
