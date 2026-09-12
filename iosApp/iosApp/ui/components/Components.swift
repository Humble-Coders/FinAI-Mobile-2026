import SwiftUI
import SharedLogic

/// The palette from the approved splash design, mirrored from Android's
/// `FinAiPalette` so the two apps cannot drift.
enum Brand {
    static let green = Color(red: 0x22 / 255, green: 0xC5 / 255, blue: 0x5E / 255)
    static let onGreen = Color(red: 0x05 / 255, green: 0x2E / 255, blue: 0x16 / 255)

    static let ground = Color("Ground")
    static let surface = Color("Surface")
    static let border = Color("BorderColor")
    static let textMuted = Color("TextMuted")
}

/// Every screen's frame: the brand ground edge to edge, content inside the safe
/// area, and a width cap so a form does not stretch across an iPad.
struct ScreenScaffold<Content: View>: View {
    var alignment: HorizontalAlignment = .leading
    var centred = false
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            Brand.ground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: alignment, spacing: 16) {
                    if centred { Spacer(minLength: 0) }
                    content()
                    if centred { Spacer(minLength: 0) }
                }
                .frame(maxWidth: 480, alignment: alignment == .center ? .center : .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.vertical, 24)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}

/// `FinAI`, with the AI in the accent — the wordmark from the splash design.
struct Wordmark: View {
    var body: some View {
        (Text("Fin").foregroundColor(.primary) + Text("AI").foregroundColor(Brand.green).bold())
            .font(.largeTitle.weight(.bold))
    }
}

/// The one accented control on a screen. Green carries the primary action and
/// nothing else, so a screen never shows two.
struct PrimaryButton: View {
    let title: String
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: { if !busy { action() } }) {
            ZStack {
                if busy {
                    ProgressView().tint(Brand.onGreen)
                } else {
                    Text(title).font(.headline)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(.plain)
        .background(enabled && !busy ? Brand.green : Brand.green.opacity(0.4))
        .foregroundColor(Brand.onGreen)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .disabled(!enabled || busy)
    }
}

/// A provider route. Outlined, never accented: the phone route is primary.
struct ProviderButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(.plain)
        .foregroundColor(.primary)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Brand.border, lineWidth: 1))
        .disabled(!enabled)
    }
}

/// `──── or ────`, between the phone route and the provider routes.
struct OrDivider: View {
    var body: some View {
        HStack(spacing: 12) {
            Rectangle().fill(Brand.border).frame(height: 1)
            Text(L.t(Strings.shared.welcome_or))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
            Rectangle().fill(Brand.border).frame(height: 1)
        }
    }
}

/// An inline failure, under the control that caused it.
struct ErrorText: View {
    let messageKey: String?

    var body: some View {
        if let messageKey {
            Text(L.t(messageKey))
                .font(.footnote)
                .foregroundColor(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
