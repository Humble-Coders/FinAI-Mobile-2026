import SwiftUI
import SharedLogic

/// The router: a state switch, not a navigation framework (kmp-arch-v2).
///
/// It renders whatever the **shared** rule says and decides nothing itself. What
/// it checks first are sub-states the server knows nothing about: a password
/// reset, a sent code, and the region override reached from consent.
struct RootView: View {
    @StateObject private var model = OnboardingViewModel()
    /// The wizard owns a repository and a draft nothing else needs, so it has
    /// its own model, built and closed with the screen.
    @StateObject private var setupModel = SetupViewModel()
    /// One coin loader for the whole app: centred, everything behind it blurred,
    /// for at least two seconds.
    @StateObject private var loader = AppLoader()
    @State private var changingRegion = false

    var body: some View {
        LoaderHost(active: loaderActive, loader: loader) {
        Group {
            if let problemKey = model.configurationProblemKey {
                NotConfiguredView(messageKey: problemKey)
            } else if !model.introFinished {
                // The launch intro plays in full before anything routes, so a
                // signed-in user's fast start still sees it. It hands over to
                // the static splash if routing is still deciding.
                SplashView(slow: false, animate: true) { model.finishIntro() }
            } else if model.showLoadingCard {
                // Between steps, not at launch: the coin carries the wait.
                LoadingCard()
            } else {
                content
            }
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
            .environment(\.appLoader, loader)
        }
    }

    /// Signing in or up, the hand-over between steps, and the wizard's first
    /// load. Continue inside the wizard is not here: it never waits on the coin.
    private var loaderActive: Bool {
        model.busy || model.showLoadingCard || (showingSetup && setupModel.loading)
    }

    private var showingSetup: Bool {
        model.configurationProblemKey == nil
            && model.introFinished
            && !model.showLoadingCard
            && model.reset == nil
            && model.destination.screen == .financialSetup
    }

    @ViewBuilder
    private var content: some View {
        // Ahead of the router: once the reset code verifies, the user is signed
        // in, and must choose the new password before going anywhere.
        switch model.reset {
        case .request:
            ResetRequestView(model: model)
        case .code:
            CodeView(
                model: model,
                sentTo: model.email,
                editKey: Strings.shared.code_wrong_email,
                onVerify: { model.verifyResetCode() },
                onResend: { model.requestResetCode() },
                onEdit: { model.startReset() }
            )
        case .newPassword:
            NewPasswordView(model: model)
        default:
            routed
        }
    }

    @ViewBuilder
    private var routed: some View {
        switch model.destination.screen {
        case .splash:
            // .task is cancelled when the splash goes away and restarted on
            // every entry, so the splash after a code verify gets an indicator
            // too - not just the one at launch.
            SplashView(slow: model.startIsSlow)
                .task { await model.watchForSlowStart() }
        case .welcome:
            if let sentTo = model.emailCodeFor {
                CodeView(
                    model: model,
                    sentTo: sentTo,
                    editKey: Strings.shared.code_wrong_email,
                    hintKey: Strings.shared.email_code_hint,
                    onVerify: { model.verifyEmailCode() },
                    onResend: { model.resendEmailCode() },
                    onEdit: { model.editEmail() }
                )
            } else {
                WelcomeView(model: model)
            }
        case .phone:
            // Signed in, and the account has not verified a number yet.
            phoneOrCode
        case .region:
            RegionView(model: model) { model.setRegion($0) }
        case .consent:
            ConsentView(model: model) { changingRegion = true }
        case .financialSetup:
            SetupView(model: setupModel) { model.loadMe() }
                .onAppear { setupModel.bind() }
                .onDisappear { setupModel.unbind() }
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
    private var phoneOrCode: some View {
        if model.codeSent {
            CodeView(
                model: model,
                sentTo: model.e164,
                editKey: Strings.shared.code_wrong_number,
                onVerify: { model.verifyCode() },
                onResend: { model.sendCode() },
                onEdit: { model.editNumber() }
            )
        } else {
            PhoneView(model: model)
        }
    }
}
