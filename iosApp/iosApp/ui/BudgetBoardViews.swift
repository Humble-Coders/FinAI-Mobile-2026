import SwiftUI
import SharedLogic

// The Budget screen's cards, in the language of the reference design: white
// cards on the green field, a soft glow in each card's corner, tinted icon
// tiles, and the figures large. Every figure and label comes from the shared
// `BudgetBoard`; these only draw them. Android's `BudgetBoardViews.kt`.

/// The board's five category colours, in slice order; Android's `BoardPalette`.
enum BoardPalette {
    private static let light: [Color] = [
        Color(red: 0.231, green: 0.510, blue: 0.965), Color(red: 0.976, green: 0.451, blue: 0.086),
        Color(red: 0.659, green: 0.333, blue: 0.969), Color(red: 0.133, green: 0.773, blue: 0.369),
        Color(red: 0.024, green: 0.714, blue: 0.831),
    ]
    private static let dark: [Color] = [
        Color(red: 0.376, green: 0.647, blue: 0.980), Color(red: 0.984, green: 0.573, blue: 0.235),
        Color(red: 0.753, green: 0.518, blue: 0.988), Color(red: 0.290, green: 0.871, blue: 0.502),
        Color(red: 0.133, green: 0.827, blue: 0.933),
    ]

    static func color(_ index: Int32, dark isDark: Bool) -> Color {
        let palette = isDark ? dark : light
        return palette[max(0, min(Int(index), palette.count - 1))]
    }

    static let violet = Color(red: 0.486, green: 0.227, blue: 0.929)
    static let violetSoft = Color(red: 0.655, green: 0.545, blue: 0.980)
    static let orange = Color(red: 0.976, green: 0.451, blue: 0.086)
    static let green = Color(red: 0.133, green: 0.773, blue: 0.369)
    static let teal = Color(red: 0.078, green: 0.722, blue: 0.651)
}

/// A white card with a soft glow of `glow` in its top-right corner.
struct BoardCard<Content: View>: View {
    var glow: Color = BoardPalette.green
    var background: AnyShapeStyle?
    @ViewBuilder var content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        VStack(alignment: .leading, spacing: 14) { content() }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                ZStack(alignment: .topTrailing) {
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(background ?? AnyShapeStyle(Mint.panel(dark)))
                    // The corner glow: a soft circle, mostly off the card.
                    Circle()
                        .fill(RadialGradient(colors: [glow.opacity(dark ? 0.16 : 0.12), .clear], center: .center, startRadius: 0, endRadius: 150))
                        .frame(width: 300, height: 300)
                        .offset(x: 126, y: -138)
                        .allowsHitTesting(false)
                }
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            }
            .overlay(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(dark ? Color.white.opacity(0.06) : Color.white.opacity(0.7), lineWidth: 1)
            )
            .shadow(color: .black.opacity(dark ? 0 : 0.10), radius: 12, x: 0, y: 6)
    }
}

/// A tinted rounded-square tile, or a filled gradient one, with an icon in it.
struct BoardTile: View {
    let symbol: String
    let accent: Color
    var size: CGFloat = 40
    var iconSize: CGFloat = 18
    var filled: LinearGradient?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            if let filled {
                RoundedRectangle(cornerRadius: size * 0.32, style: .continuous).fill(filled)
                FinAiIcon(symbol: symbol, tint: .white, size: iconSize)
            } else {
                RoundedRectangle(cornerRadius: size * 0.32, style: .continuous).fill(Mint.tile(accent, scheme == .dark))
                FinAiIcon(symbol: symbol, tint: accent, size: iconSize)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A card's heading: tile, title and subtitle, and anything on the right.
struct BoardHeader<Trailing: View>: View {
    let symbol: String
    let accent: Color
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            BoardTile(symbol: symbol, accent: accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundColor(.secondary)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
    }
}

extension BoardHeader where Trailing == EmptyView {
    init(symbol: String, accent: Color, title: String, subtitle: String? = nil) {
        self.init(symbol: symbol, accent: accent, title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// "In progress" with a softly pulsing dot, or "Final" with a still one.
struct BoardStatusPill: View {
    let text: String
    let live: Bool
    @State private var pulsing = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(live ? BoardPalette.green : Color.secondary)
                .frame(width: 7, height: 7)
                .opacity(live && pulsing ? 0.35 : 1)
                .animation(live ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true) : nil, value: pulsing)
            Text(text).font(.caption.weight(.medium)).foregroundColor(Mint.greenText(scheme == .dark))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Capsule().fill(Mint.tile(BoardPalette.green, scheme == .dark)))
        .overlay(Capsule().stroke(BoardPalette.green.opacity(0.35), lineWidth: 1))
        .onAppear { pulsing = true }
    }
}

// MARK: - This month so far

struct SoFarCard: View {
    let board: BoardSoFar
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        BoardCard {
            BoardHeader(symbol: "chart.line.uptrend.xyaxis", accent: Mint.greenText(dark), title: board.title) {
                BoardStatusPill(text: board.badge, live: board.running)
            }
            // The panel is exactly as tall as the three figures beside it.
            HStack(alignment: .center, spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    figure(board.income, symbol: "arrow.down", accent: BoardPalette.green)
                    Spacer(minLength: 10)
                    figure(board.spent, symbol: "arrow.up", accent: BoardPalette.orange)
                    Spacer(minLength: 10)
                    figure(board.saved, symbol: "banknote", accent: BoardPalette.violet)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                leftPanel(dark)
                    .frame(maxHeight: .infinity)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func figure(_ figure: BoardFigure, symbol: String, accent: Color) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.caption2.weight(.medium))
                .foregroundColor(accent)
                .frame(width: 26, height: 26)
                .background(Circle().fill(accent.opacity(0.14)))
            Text(figure.label).font(.subheadline).lineLimit(1)
            Spacer(minLength: 4)
            Text(figure.amount).font(.subheadline.weight(.medium)).lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(figure.label), \(figure.amount)")
    }

    /// What is left to spend: the card's bottom line, on a mint panel as tall as the figures beside it.
    private func leftPanel(_ dark: Bool) -> some View {
        VStack(spacing: 3) {
            Text(board.leftLabel.uppercased())
                .font(.caption2.weight(.medium))
                .tracking(1)
                .foregroundColor(Mint.greenText(dark))
                .multilineTextAlignment(.center)
            Text(board.left)
                .font(.headline.weight(.semibold))
                .foregroundColor(board.leftNegative ? .red : .primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(board.leftCaption)
                .font(.caption2)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(width: 132)
        .frame(maxHeight: .infinity)
        .background {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: [BoardPalette.green.opacity(dark ? 0.18 : 0.10), BoardPalette.green.opacity(dark ? 0.06 : 0.03)], startPoint: .top, endPoint: .bottom))
                // A faint coin in the corner — decoration, never a figure.
                Circle().fill(BoardPalette.green.opacity(0.10)).frame(width: 52, height: 52).offset(x: 20, y: -18)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(BoardPalette.green.opacity(0.25), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(board.leftLabel), \(board.left). \(board.leftCaption)")
    }
}

// MARK: - Expected this month

/// What is still to come, and on the right — the same height as the figures —
/// the ring for how much of income the month lands on, with "Left at month
/// end" under it. The commitments follow, each tappable to tick off by hand.
struct ForecastCard: View {
    let forecast: BoardForecast
    let commitments: [OverviewCommitment]
    let onTick: (String) -> Void
    @Environment(\.colorScheme) private var scheme
    @State private var fill: CGFloat = 0

    var body: some View {
        let dark = scheme == .dark
        BoardCard(glow: BoardPalette.teal) {
            BoardHeader(symbol: "chart.bar.fill", accent: Mint.greenText(dark), title: forecast.title, subtitle: forecast.subtitle)
            HStack(alignment: .center, spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(forecast.rows.enumerated()), id: \.offset) { index, row in
                        if index > 0 { Spacer(minLength: 8) }
                        signed(row, dark: dark)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                VStack(spacing: 8) {
                    ring
                    Spacer(minLength: 0)
                    leftAtEnd(dark)
                }
                .frame(width: 136)
                .frame(maxHeight: .infinity)
            }
            .fixedSize(horizontal: false, vertical: true)
            if !commitments.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text(L.t(Strings.shared.budget_commitments_title))
                        .font(.subheadline.weight(.medium))
                        .padding(.top, 8)
                        .padding(.bottom, 2)
                    ForEach(Array(commitments.enumerated()), id: \.offset) { _, item in CommitmentLine(item: item, onTick: onTick) }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 18).fill(Mint.card(dark)))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Mint.edge(dark), lineWidth: 1))
            }
        }
        .onAppear { withAnimation(.easeOut(duration: 0.9)) { fill = CGFloat(forecast.fraction) } }
        .onChange(of: forecast.fraction) { _, new in withAnimation(.easeOut(duration: 0.6)) { fill = CGFloat(new) } }
    }

    private func signed(_ row: BoardFigure, dark: Bool) -> some View {
        let incoming = row.sign > 0
        let accent = incoming ? BoardPalette.green : BoardPalette.orange
        let sign = incoming ? "+" : "−"
        return HStack(spacing: 8) {
            Image(systemName: incoming ? "plus" : "minus")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(accent)
                .frame(width: 20, height: 20)
                .background(Circle().fill(accent.opacity(0.14)))
            Text(row.label).font(.caption)
            Spacer(minLength: 4)
            Text("\(sign)\(row.amount)")
                .font(.caption.weight(.medium))
                .foregroundColor(incoming ? Mint.greenText(dark) : .primary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(row.label), \(sign)\(row.amount)")
    }

    /// "Left at month end", under the ring: the amount and whether it is a surplus.
    private func leftAtEnd(_ dark: Bool) -> some View {
        let tone: Color = forecast.surplus ? BoardPalette.green : .red
        return VStack(spacing: 3) {
            Text(forecast.leftLabel.uppercased())
                .font(.caption2.weight(.medium))
                .tracking(0.6)
                .foregroundColor(forecast.surplus ? Mint.greenText(dark) : .red)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(forecast.left).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(forecast.statusLabel)
                .font(.caption2.weight(.medium))
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Capsule().fill(tone))
        }
        .padding(8)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 14).fill(tone.opacity(0.09)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(tone.opacity(0.3), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(forecast.leftLabel), \(forecast.left), \(forecast.statusLabel)")
    }

    /// How much of income the month lands on, as a ring that fills when it first shows.
    private var ring: some View {
        let tone: Color = forecast.surplus ? BoardPalette.green : .red
        return ZStack {
            Circle().stroke(Color(.tertiarySystemFill), lineWidth: 9)
            Circle()
                .trim(from: 0, to: fill)
                .stroke(
                    AngularGradient(colors: [tone.opacity(0.55), tone, tone.opacity(0.55)], center: .center),
                    style: StrokeStyle(lineWidth: 9, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text("\(forecast.percentLeft)%").font(.headline.weight(.semibold))
                Text(forecast.gaugeLabel)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 68)
            }
        }
        .frame(width: 96, height: 96)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(forecast.percentLeft)% \(forecast.gaugeLabel)")
    }
}

/// A commitment: a ticked box when its payment was found, an empty one when
/// not — and the words, always. Tapping it ticks it off, or changes how.
struct CommitmentLine: View {
    let item: OverviewCommitment
    var onTick: ((String) -> Void)?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button {
            if let id = item.id { onTick?(id) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: item.seen ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundColor(item.seen ? Mint.greenText(scheme == .dark) : .secondary)
                Text(item.name).font(.subheadline).foregroundColor(.primary).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 0) {
                    Text(item.amount).font(.subheadline.weight(.medium)).foregroundColor(.primary)
                    Text(item.status).font(.caption2).foregroundColor(.secondary)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(item.id == nil || onTick == nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.description_)
        .accessibilityHint(L.t(Strings.shared.tick_a11y_open, item.name))
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - The savings target

/// The savings target, apart from the categories: what is set aside each
/// month, how much of it this month has, and a way to set or change it.
struct SavingsCard: View {
    let target: String?
    let progress: String?
    let fraction: Double
    let room: String?
    let locale: String
    let onOpen: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: onOpen) {
            BoardCard(glow: BoardPalette.violet) {
                BoardHeader(
                    symbol: "banknote",
                    accent: BoardPalette.violet,
                    title: BudgetBoard.shared.savingsTitle(locale: locale),
                    subtitle: BudgetBoard.shared.savingsSubtitle(locale: locale)
                ) {
                    if let target {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(target).font(.subheadline.weight(.semibold)).foregroundColor(.primary)
                            Image(systemName: "pencil").font(.caption2).foregroundColor(Mint.greenText(scheme == .dark))
                        }
                    }
                }
                if target != nil {
                    CategoryBar(fraction: fraction, color: BoardPalette.violet)
                    if let progress { Text(progress).font(.caption).foregroundColor(.secondary) }
                } else {
                    HStack(spacing: 10) {
                        Image(systemName: "plus").foregroundColor(BoardPalette.violet)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L.t(Strings.shared.budget_set_target)).font(.subheadline.weight(.medium)).foregroundColor(BoardPalette.violet)
                            if let room { Text(room).font(.caption).foregroundColor(.secondary) }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(BoardPalette.violet.opacity(0.35), lineWidth: 1))
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - The AI

/// "Smart Financial AI": the AI's headline about the month, a tap from the whole check.
struct InsightCard: View {
    let headline: String?
    let detail: String?
    let onOpen: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        Button(action: onOpen) {
            BoardCard(
                glow: BoardPalette.violetSoft,
                background: AnyShapeStyle(LinearGradient(
                    colors: dark
                        ? [Color(red: 0.137, green: 0.106, blue: 0.227), Color(red: 0.110, green: 0.110, blue: 0.133)]
                        : [Color(red: 0.953, green: 0.933, blue: 1.0), Color(red: 0.980, green: 0.973, blue: 1.0)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ))
            ) {
                HStack(alignment: .top, spacing: 14) {
                    BoardTile(
                        symbol: "sparkles",
                        accent: BoardPalette.violet,
                        size: 44,
                        iconSize: 20,
                        filled: LinearGradient(colors: [BoardPalette.violetSoft, BoardPalette.violet], startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            Text(L.t(Strings.shared.board_ai_title)).font(.subheadline.weight(.semibold))
                            HStack(spacing: 3) {
                                Image(systemName: "sparkles").font(.system(size: 8, weight: .semibold))
                                Text(L.t(Strings.shared.board_ai_badge).uppercased()).font(.system(size: 10, weight: .semibold))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: 6).fill(BoardPalette.violet))
                        }
                        Text(headline ?? L.t(Strings.shared.board_ai_prompt))
                            .font(.subheadline)
                            .foregroundColor(.primary)
                            .multilineTextAlignment(.leading)
                        if let detail {
                            Text(detail).font(.caption).foregroundColor(.secondary).multilineTextAlignment(.leading)
                        }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.subheadline.weight(.medium)).foregroundColor(.secondary)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityHint(L.t(Strings.shared.board_ai_open))
    }
}

// MARK: - Categories

/// The categories card's heading and its donut with the legend beside it.
struct DonutHeader: View {
    let donut: BoardDonut
    @Environment(\.colorScheme) private var scheme
    @State private var sweep: CGFloat = 0

    var body: some View {
        let dark = scheme == .dark
        VStack(alignment: .leading, spacing: 14) {
            BoardHeader(symbol: "chart.pie.fill", accent: BoardPalette.orange, title: donut.title) {
                VStack(alignment: .trailing, spacing: 1) {
                    Text(donut.totalsLabel).font(.caption2).foregroundColor(.secondary)
                    Text(donut.totals).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
                }
            }
            HStack(spacing: 18) {
                ring(dark)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(donut.slices.enumerated()), id: \.offset) { _, slice in
                        HStack(spacing: 8) {
                            Circle().fill(BoardPalette.color(slice.colorIndex, dark: dark)).frame(width: 9, height: 9)
                            Text(slice.label).font(.caption).lineLimit(1).truncationMode(.tail)
                            Spacer(minLength: 4)
                            Text("\(slice.percent)%").font(.caption.weight(.medium))
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(slice.label), \(slice.percent)%")
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .onAppear { withAnimation(.easeOut(duration: 0.9)) { sweep = 1 } }
    }

    /// One arc per slice with a small gap between, sweeping in when it first shows.
    private func ring(_ dark: Bool) -> some View {
        let gap: CGFloat = donut.slices.count > 1 ? 0.008 : 0
        var running: CGFloat = 0
        let arcs: [(CGFloat, CGFloat, Color)] = donut.slices.map { slice in
            let from = running
            running += CGFloat(slice.fraction)
            return (from, running, BoardPalette.color(slice.colorIndex, dark: dark))
        }
        return ZStack {
            ForEach(Array(arcs.enumerated()), id: \.offset) { _, arc in
                Circle()
                    .trim(from: min(arc.0 * sweep + gap, arc.1 * sweep), to: max(arc.1 * sweep - gap, arc.0 * sweep))
                    .stroke(arc.2, style: StrokeStyle(lineWidth: 16, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
            }
            VStack(spacing: 0) {
                Text(donut.centre).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.6)
                Text(donut.centreCaption).font(.caption2).foregroundColor(.secondary)
            }
            .padding(.horizontal, 18)
        }
        .frame(width: 116, height: 116)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(donut.centre) \(donut.centreCaption)")
    }
}

/// A category row's bar, in its slice's colour — or red once it is over.
struct CategoryBar: View {
    let fraction: Double
    let color: Color
    @State private var fill: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color(.tertiarySystemFill))
                Capsule()
                    .fill(LinearGradient(colors: [color.opacity(0.7), color], startPoint: .leading, endPoint: .trailing))
                    .frame(width: geo.size.width * fill)
            }
        }
        .frame(height: 6)
        .onAppear { withAnimation(.easeOut(duration: 0.9).delay(0.15)) { fill = CGFloat(min(max(fraction, 0), 1)) } }
        .onChange(of: fraction) { _, new in withAnimation(.easeOut(duration: 0.5)) { fill = CGFloat(min(max(new, 0), 1)) } }
        .accessibilityHidden(true)
    }
}

// MARK: - Next steps

struct NextStepsCard: View {
    let title: String
    let subtitle: String
    let steps: [NextStep]
    let onStep: (NextStep) -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        BoardCard {
            BoardHeader(symbol: "checkmark.circle", accent: Mint.greenText(dark), title: title, subtitle: subtitle)
            Divider().overlay(Mint.edge(dark))
            ForEach(Array(steps.enumerated()), id: \.offset) { _, step in row(step, dark: dark) }
        }
    }

    private func row(_ step: NextStep, dark: Bool) -> some View {
        let (symbol, accent) = Self.look(step, dark: dark)
        return Button { onStep(step) } label: {
            HStack(spacing: 12) {
                BoardTile(
                    symbol: symbol,
                    accent: accent,
                    size: 44,
                    iconSize: 18,
                    filled: step.emphasized ? LinearGradient(colors: [BoardPalette.green, Color(red: 0.082, green: 0.502, blue: 0.239)], startPoint: .topLeading, endPoint: .bottomTrailing) : nil
                )
                VStack(alignment: .leading, spacing: 4) {
                    Text(step.title).font(.subheadline.weight(.medium)).foregroundColor(.primary).multilineTextAlignment(.leading)
                    Text(step.detail)
                        .font(.caption)
                        .foregroundColor(step.emphasized ? Mint.greenText(dark) : .secondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.medium))
                    .foregroundColor(step.emphasized ? Mint.greenText(dark) : .secondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .frame(minHeight: 72)
            .background(RoundedRectangle(cornerRadius: 18).fill(step.emphasized ? BoardPalette.green.opacity(dark ? 0.14 : 0.08) : Mint.card(dark)))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(step.emphasized ? BoardPalette.green.opacity(0.45) : Mint.edge(dark), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private static func look(_ step: NextStep, dark: Bool) -> (String, Color) {
        switch step.action.name {
        case "INCOME": return ("plus", BoardPalette.green)
        case "COMMITMENTS": return ("list.clipboard", BoardPalette.orange)
        default: return ("target", BoardPalette.green)
        }
    }
}
