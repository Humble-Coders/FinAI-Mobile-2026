import SwiftUI
import SharedLogic

/// The in-app splash.
///
/// Logo, wordmark and tagline are centred together as one group, so they sit in
/// the middle third of the screen. The launch screen shows only the ground
/// colour (Info.plist -> `UILaunchScreen`): its image is always centred on the
/// screen, and would jump up to meet this group. The slow-start notice sits in
/// the bottom third, so appearing never moves the group.
///
/// With `animate`, the launch intro plays once (shared `SplashIntro`) and then
/// calls `onIntroFinished`. Every later splash shows the finished wordmark at
/// once, and so does the launch splash with Reduce Motion on.
struct SplashView: View {
    let slow: Bool
    var animate = false
    var onIntroFinished: () -> Void = {}

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var frame: WordmarkFrame
    @State private var taglineShown: Bool

    /// LogoMark.imageset is drawn for this size.
    private static let logoSize: CGFloat = 120

    init(slow: Bool, animate: Bool = false, onIntroFinished: @escaping () -> Void = {}) {
        self.slow = slow
        self.animate = animate
        self.onIntroFinished = onIntroFinished
        _frame = State(initialValue: animate ? SplashIntro.shared.frames.first! : SplashIntro.shared.finalFrame)
        _taglineShown = State(initialValue: !animate)
    }

    var body: some View {
        ZStack {
            Brand.ground.ignoresSafeArea()

            VStack(spacing: 0) {
                Image("LogoMark")
                    .resizable()
                    .scaledToFit()
                    .frame(width: Self.logoSize, height: Self.logoSize)
                    .accessibilityHidden(true)
                Wordmark(frame: frame, font: .system(size: 40, weight: .bold))
                    // Read once as the name, never letter by letter.
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L.t(Strings.shared.app_name))
                    .padding(.top, 24)
                Text(L.t(Strings.shared.app_tagline))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .multilineTextAlignment(.center)
                    // Faded rather than added, so nothing above it moves.
                    .opacity(taglineShown ? 1 : 0)
                    .padding(.top, 8)
            }
            .padding(.horizontal, 24)

            if slow {
                VStack(spacing: 12) {
                    ProgressView()
                    Text(L.t(Strings.shared.splash_slow))
                        .font(.footnote)
                        .foregroundColor(Brand.textMuted)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 48)
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .task { await playIntro() }
    }

    private func playIntro() async {
        guard animate else { return }
        let fade = Double(SplashIntro.shared.TAGLINE_FADE_MS) / 1000
        if reduceMotion {
            frame = SplashIntro.shared.finalFrame
            taglineShown = true
        } else {
            for next in SplashIntro.shared.frames {
                frame = next
                try? await Task.sleep(nanoseconds: UInt64(next.holdMs) * 1_000_000)
                if Task.isCancelled { return }
            }
            withAnimation(.easeIn(duration: fade)) { taglineShown = true }
            try? await Task.sleep(nanoseconds: UInt64(fade * 1_000_000_000))
            if Task.isCancelled { return }
        }
        onIntroFinished()
    }
}

/// A centred message with an optional action — the shape every status screen
/// below shares.
private struct MessageView: View {
    let title: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?
    var secondaryTitle: String?
    var secondaryAction: (() -> Void)?

    var body: some View {
        ScreenScaffold(alignment: .center, centred: true) {
            Text(title)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(message)
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
            if let actionTitle, let action {
                PrimaryButton(title: actionTitle) { action() }.padding(.top, 16)
            }
            if let secondaryTitle, let secondaryAction {
                Button(secondaryTitle, action: secondaryAction)
                    .foregroundColor(Brand.green)
                    .tappableRow()
            }
        }
    }
}

/// The API named an onboarding step this build has no screen for. A real
/// destination, never a silent pass: an unknown step means something IS
/// outstanding.
struct UpdateRequiredView: View {
    var body: some View {
        MessageView(
            title: L.t(Strings.shared.update_required_title),
            message: L.t(Strings.shared.update_required_body)
        )
    }
}

/// `/me` could not be loaded.
///
/// Retry is the main action, but it cannot be the only one: while the server is
/// down every retry fails, and a signed-in caller who cannot load `/me` has no
/// other screen to be on. Without a way out they are simply stuck, which is what
/// the ticket's "never stuck" rule is about. Signing out returns them to the
/// welcome screen, which always works because it needs nothing from the API.
struct FailedView: View {
    let messageKey: String
    let onRetry: () -> Void
    let onSignOut: () -> Void

    var body: some View {
        MessageView(
            title: L.t(Strings.shared.error_title),
            message: L.t(messageKey),
            actionTitle: L.t(Strings.shared.action_retry),
            action: onRetry,
            secondaryTitle: L.t(Strings.shared.action_sign_out),
            secondaryAction: onSignOut
        )
    }
}

/// This build has no backend configured — see the README's setup step.
struct NotConfiguredView: View {
    let messageKey: String

    var body: some View {
        MessageView(title: L.t(Strings.shared.error_title), message: L.t(messageKey))
    }
}

/// The `financial_setup` step, until 2.4 builds the wizard behind it. A
/// deliberate seam: the step must still block home, because the server refuses
/// everything else until the figures exist (Finance-backend#29).
/// Sign out is the only way off it until then.
struct SetupPendingView: View {
    let onSignOut: () -> Void

    var body: some View {
        MessageView(
            title: L.t(Strings.shared.setup_pending_title),
            message: L.t(Strings.shared.setup_pending_body),
            secondaryTitle: L.t(Strings.shared.action_sign_out),
            secondaryAction: onSignOut
        )
    }
}

/// A placeholder: reaching it is what this ticket proves. The dashboard is M4.
struct HomeView: View {
    let onSignOut: () -> Void

    var body: some View {
        ScreenScaffold(alignment: .center, centred: true) {
            Text(L.t(Strings.shared.home_title))
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(L.t(Strings.shared.home_body))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
            Button(L.t(Strings.shared.action_sign_out), action: onSignOut)
                .foregroundColor(Brand.green)
                .padding(.top, 16)
        }
    }
}
