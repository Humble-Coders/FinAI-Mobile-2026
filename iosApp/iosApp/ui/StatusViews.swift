import SwiftUI
import SharedLogic

/// The in-app splash, continuing the launch screen.
///
/// No timer: it lasts exactly as long as the session restore and the first
/// `/me`. If that turns out to be slow it says so, because a motionless logo is
/// indistinguishable from a hang.
struct SplashView: View {
    let slow: Bool

    var body: some View {
        ScreenScaffold(alignment: .center, centred: true) {
            Wordmark()
            Text(L.t(Strings.shared.app_tagline))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
            if slow {
                ProgressView().padding(.top, 24)
                Text(L.t(Strings.shared.splash_slow))
                    .font(.footnote)
                    .foregroundColor(Brand.textMuted)
                    .multilineTextAlignment(.center)
            }
        }
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
struct SetupPendingView: View {
    var body: some View {
        MessageView(
            title: L.t(Strings.shared.setup_pending_title),
            message: L.t(Strings.shared.setup_pending_body)
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
