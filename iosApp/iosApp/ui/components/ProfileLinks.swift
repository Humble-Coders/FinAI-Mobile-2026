import SwiftUI
import SharedLogic

/*
 The ways back to Profile from the screens that used to ask for consent or
 carry a disclaimer themselves (PM decision, 2026-11-06: consents and
 disclaimers live in Profile, and nowhere else asks in full).

 Opening Profile is an environment value rather than a closure threaded
 through every screen: the notices sit deep — in a sheet of a tab, in a cover
 over the plan — and RootView is the only thing that knows how to get there.
 Nil wherever Profile cannot be reached (setup, before Home exists), and the
 notices then leave their button out.
 */

private struct OpenProfileKey: EnvironmentKey {
    static let defaultValue: (() -> Void)? = nil
}

extension EnvironmentValues {
    /// Opens Profile over the tab bar; back returns to where it was opened.
    var openProfile: (() -> Void)? {
        get { self[OpenProfileKey.self] }
        set { self[OpenProfileKey.self] = newValue }
    }
}

/**
 What a screen shows where it used to ask for AI consent: AI is off, turn it
 on in Profile, and the way there. `onRetry` is the screen's own way to try
 again once the person is back. A view inside a sheet or cover passes
 `beforeOpen` to close it first, or it would stay up over Profile.
 */
struct AiOffNotice: View {
    var onRetry: (() -> Void)?
    var beforeOpen: (() -> Void)?

    @Environment(\.openProfile) private var openProfile
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "sparkles")
                    .foregroundColor(Mint.greenText(dark))
                    .accessibilityHidden(true)
                Text(L.t(Strings.shared.ai_off_notice))
                    .font(.subheadline)
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 16) {
                if let openProfile {
                    Button {
                        beforeOpen?()
                        openProfile()
                    } label: {
                        Text(L.t(Strings.shared.ai_off_action))
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Mint.greenText(dark))
                            .tappableArea()
                    }
                    .buttonStyle(.plain)
                }
                if let onRetry {
                    Button(action: onRetry) {
                        Text(L.t(Strings.shared.action_retry))
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.primary)
                            .tappableArea()
                    }
                    .buttonStyle(.plain)
                }
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Mint.greenText(dark).opacity(dark ? 0.18 : 0.08)))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Mint.greenText(dark).opacity(0.3), lineWidth: 1))
        .onAppear { AccessibilityNotification.Announcement(L.t(Strings.shared.ai_off_notice)).post() }
    }
}

/**
 The one small line a screen keeps where its disclaimer used to be: "Guidance,
 not advice · Details in Profile", which opens Profile. `beforeOpen` as for
 `AiOffNotice`. Without a way to Profile it is plain text, still said.
 */
struct DisclaimerLink: View {
    var tint: Color = .secondary
    var beforeOpen: (() -> Void)?

    @Environment(\.openProfile) private var openProfile

    var body: some View {
        if let openProfile {
            Button {
                beforeOpen?()
                openProfile()
            } label: {
                Text(L.t(Strings.shared.disclaimer_link))
                    .font(.caption)
                    .underline()
                    .foregroundColor(tint)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            Text(L.t(Strings.shared.disclaimer_link))
                .font(.caption)
                .foregroundColor(tint)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// The person's monogram in a circle: Home's header button and Profile's top.
struct ProfileMonogram: View {
    let initials: String
    let size: CGFloat
    var fill: Color
    var ink: Color
    var edge: Color = .clear

    var body: some View {
        ZStack {
            Circle().fill(fill)
            Circle().stroke(edge, lineWidth: 1)
            Text(initials)
                .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                .foregroundColor(ink)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
