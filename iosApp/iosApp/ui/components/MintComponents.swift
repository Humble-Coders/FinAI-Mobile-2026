import SharedLogic
import SwiftUI

/*
 The light, minty frame the import and manual-entry screens share, from the
 approved design: a pale green field with soft hills and one faint picture in
 the corner, a white panel holding the form, and tinted icon tiles. Mirrors
 Android's `MintScreen.kt`.

 Purely the look. Every one of these is a container or a row; what goes in
 them, and what tapping them does, stays with each screen.
 */

/// The field's colours, light and dark.
enum Mint {
    static func ground(_ dark: Bool) -> LinearGradient {
        LinearGradient(
            colors: dark
                ? [Color(red: 0x0C / 255, green: 0x1E / 255, blue: 0x14 / 255), Color(red: 0x0A / 255, green: 0x0A / 255, blue: 0x0A / 255)]
                : [Color(red: 0xE6 / 255, green: 0xF6 / 255, blue: 0xEC / 255), Color(red: 0xF5 / 255, green: 0xFB / 255, blue: 0xF7 / 255)],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    static func hill(_ dark: Bool) -> Color { dark ? .white.opacity(0.03) : Brand.green.opacity(0.07) }

    /// The panel the form sits on. White on light; a lifted grey on dark.
    static func panel(_ dark: Bool) -> Color { dark ? Brand.surface : .white }

    /// A card inside the panel, a shade off it.
    static func card(_ dark: Bool) -> Color {
        dark ? Color(red: 0x24 / 255, green: 0x24 / 255, blue: 0x27 / 255) : Color(red: 0xFB / 255, green: 0xFD / 255, blue: 0xFC / 255)
    }

    static func edge(_ dark: Bool) -> Color {
        dark ? .white.opacity(0.06) : Color(red: 0xE3 / 255, green: 0xEF / 255, blue: 0xE8 / 255)
    }

    /// Green text: deep on light, bright on dark, where the deep one cannot be read.
    static func greenText(_ dark: Bool) -> Color { dark ? Brand.green : Brand.greenDeep }

    static func tile(_ accent: Color, _ dark: Bool) -> Color { accent.opacity(dark ? 0.22 : 0.14) }

    /// The colours a list of accounts cycles through, so neighbouring rows differ.
    static let accountTints: [Color] = [
        Brand.green, Brand.blue, Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255), Brand.red,
    ]

    static let orange = Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255)
}

/// The pale field: gradient, two soft hills, a sun, and `decoration` drawn
/// large and faint in the top corner. Decorative only.
struct MintBackdrop: View {
    let decoration: String?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        GeometryReader { geometry in
            let w = geometry.size.width
            let h: CGFloat = 360
            ZStack(alignment: .topLeading) {
                Mint.ground(dark)
                ridge(w, h, base: 0.55, lift: 0.12).fill(Mint.hill(dark))
                ridge(w, h, base: 0.75, lift: 0.08).fill(Mint.hill(dark).opacity(1.4))
                RadialGradient(
                    colors: [Color(red: 0xFD / 255, green: 0xE6 / 255, blue: 0x8A / 255).opacity(0.55), .clear],
                    center: .center, startRadius: 0, endRadius: 46
                )
                .frame(width: 92, height: 92)
                .position(x: w * 0.78, y: h * 0.22)
                if let decoration {
                    Image(systemName: decoration)
                        .font(.system(size: 76, weight: .regular))
                        .foregroundColor(Brand.green.opacity(0.16))
                        .rotationEffect(.degrees(-10))
                        .position(x: w - 64, y: 178)
                }
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private func ridge(_ w: CGFloat, _ h: CGFloat, base: CGFloat, lift: CGFloat) -> Path {
        Path { path in
            path.move(to: CGPoint(x: 0, y: h))
            path.addLine(to: CGPoint(x: 0, y: h * base))
            path.addCurve(
                to: CGPoint(x: w, y: h * (base - lift * 0.6)),
                control1: CGPoint(x: w * 0.3, y: h * (base - lift)),
                control2: CGPoint(x: w * 0.6, y: h * (base + lift))
            )
            path.addLine(to: CGPoint(x: w, y: h))
            path.closeSubpath()
        }
    }
}

/// Back, the title, and — when given — the dashes that say which step this is,
/// read as "Step 2 of 3".
struct MintHeader: View {
    let title: String
    var step: Int?
    var steps = 3
    var backDisabled = false
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Text(title)
                    .font(.headline)
                    .lineLimit(1)
                    .padding(.horizontal, 56)
                    .accessibilityAddTraits(.isHeader)
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.body.weight(.semibold))
                            .tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.action_back))
                    .foregroundColor(.primary)
                    .disabled(backDisabled)
                    Spacer()
                }
                .padding(.horizontal, 8)
            }
            .frame(height: 52)
            if let step {
                HStack(spacing: 8) {
                    ForEach(1...steps, id: \.self) { index in
                        Capsule()
                            .fill(index <= step ? Brand.green : Brand.border)
                            .frame(width: index == step ? 40 : 32, height: 4)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.t(Strings.shared.import_step, String(step), String(steps)))
            }
        }
    }
}

/// A screen's large title and the line under it, as the design sets them.
struct MintTitle: View {
    let title: String
    let subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.title2.weight(.bold))
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).font(.subheadline).foregroundColor(Brand.textMuted)
            }
        }
        .padding(.trailing, 72)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The white panel the form sits on.
struct MintPanel<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        VStack(alignment: .leading, spacing: 12) { content() }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Mint.panel(dark)))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Mint.edge(dark), lineWidth: 1))
    }
}

/// A rounded tile holding an icon, tinted from `accent`.
struct IconTile: View {
    let symbol: String
    let accent: Color
    var size: CGFloat = 48
    var iconSize: CGFloat = 22
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Mint.tile(accent, scheme == .dark))
            FinAiIcon(symbol: symbol, tint: accent, size: iconSize)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// What a choice card draws; used as a button's label, so it can sit inside a
/// `PhotosPicker` as well as a `Button`. `selected` non-nil draws a radio mark,
/// `trailing` replaces the chevron, `tinted` washes the card in its accent.
struct ChoiceCardLabel<Trailing: View>: View {
    let symbol: String
    let accent: Color
    let title: String
    var detail: String?
    var selected: Bool?
    var tinted = false
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        let chosen = selected == true
        HStack(spacing: 14) {
            IconTile(symbol: symbol, accent: accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).foregroundColor(.primary).lineLimit(1)
                if let detail {
                    Text(detail).font(.subheadline).foregroundColor(Brand.textMuted).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if Trailing.self != EmptyView.self {
                trailing()
            } else if let selected {
                RadioMark(chosen: selected)
            } else {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Brand.textMuted)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(chosen || tinted ? accent.opacity(dark ? 0.16 : 0.08) : Mint.card(dark))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(chosen ? Brand.green.opacity(0.6) : Mint.edge(dark), lineWidth: chosen ? 1.5 : 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

extension ChoiceCardLabel where Trailing == EmptyView {
    init(symbol: String, accent: Color, title: String, detail: String? = nil, selected: Bool? = nil, tinted: Bool = false) {
        self.init(symbol: symbol, accent: accent, title: title, detail: detail, selected: selected, tinted: tinted) {
            EmptyView()
        }
    }
}

/// A filled green tick when chosen, an empty ring when not. Drawing only: the
/// row's `isSelected` trait says which.
struct RadioMark: View {
    let chosen: Bool

    var body: some View {
        ZStack {
            if chosen {
                Circle().fill(Brand.green)
                Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundColor(.white)
            } else {
                Circle().stroke(Brand.textMuted.opacity(0.6), lineWidth: 1.5)
            }
        }
        .frame(width: 24, height: 24)
        .accessibilityHidden(true)
    }
}
