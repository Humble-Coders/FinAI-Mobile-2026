import SwiftUI
import SharedLogic

/// The router: a state switch, not a navigation framework (kmp-arch-v2).
///
/// It renders whatever the **shared** rule says and decides nothing itself. The
/// only two things it owns are genuinely UI: whether a code has been sent (a
/// sub-state of the phone step), and whether the user tapped Change on consent
/// to revisit their region.
struct RootView: View {
    @StateObject private var model = OnboardingViewModel()
    @State private var changingRegion = false

    var body: some View {
        Group {
            if let problemKey = model.configurationProblemKey {
                NotConfiguredView(messageKey: problemKey)
            } else {
                content
            }
        }
        .onAppear { model.bind() }
        .onDisappear { model.unbind() }
        .sheet(isPresented: $changingRegion) {
            // Not an onboarding step: the server is not asking for it, the user
            // chose to correct it (PRD §4.6).
            RegionView(model: model) { region in
                model.setRegion(region)
                changingRegion = false
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.destination.screen {
        case .splash:
            // .task is cancelled when the splash goes away and restarted on
            // every entry, so the splash after a code verify gets an indicator
            // too - not just the one at launch.
            SplashView(slow: model.startIsSlow)
                .task { await model.watchForSlowStart() }
        case .welcome:
            phoneOrCode(showProviders: true)
        case .phone:
            // Signed in but no number yet: the Google or Apple route, mid-way.
            phoneOrCode(showProviders: false)
        case .region:
            RegionView(model: model) { model.setRegion($0) }
        case .consent:
            ConsentView(model: model) { changingRegion = true }
        case .financialSetup:
            // 2.4 replaces this with the wizard. Until then it still blocks
            // home, which is what the server requires.
            SetupPendingView()
        case .updateRequired:
            UpdateRequiredView()
        case .home:
            HomeView { model.signOut() }
        case .failed:
            FailedView(
                messageKey: failureKey,
                onRetry: { model.retry() },
                onSignOut: { model.signOut() }
            )
        default:
            SplashView(slow: model.startIsSlow)
                .task { await model.watchForSlowStart() }
        }
    }

    private var failureKey: String {
        (model.destination as? Destination.Failed)?.error.messageKey
            ?? Strings.shared.error_unexpected
    }

    @ViewBuilder
    private func phoneOrCode(showProviders: Bool) -> some View {
        if model.showCodeScreen {
            CodeView(model: model)
        } else {
            PhoneView(model: model, showProviders: showProviders)
        }
    }
}
