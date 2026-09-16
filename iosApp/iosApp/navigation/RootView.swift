import SwiftUI
import SharedLogic

/// The router: a state switch, not a navigation framework (kmp-arch-v2).
///
/// It renders whatever the **shared** rule says and decides nothing itself. What
/// it checks first are sub-states the server knows nothing about: a password
/// reset, a sent code, and the region override reached from consent.
struct RootView: View {
    @StateObject private var model = OnboardingViewModel()
    @State private var changingRegion = false

    var body: some View {
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
        .overlay {
            // One loader for the whole app: mounted here, it survives every
            // step change instead of being rebuilt with each screen.
            LoadingCoin(visible: model.busy || model.showLoadingCard)
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
            // 2.4 replaces this with the wizard. Until then it still blocks
            // home, which is what the server requires.
            SetupPendingView { model.signOut() }
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
