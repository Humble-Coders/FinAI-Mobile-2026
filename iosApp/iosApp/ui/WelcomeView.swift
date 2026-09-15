import SwiftUI
import SharedLogic

/// Signed out: create an account or sign in, with email and password, Google,
/// or Apple.
///
/// One screen with a mode rather than two screens. Google and Apple need no
/// such distinction, and a person who picks the wrong mode is one tap from the other.
struct WelcomeView: View {
    @ObservedObject var model: OnboardingViewModel

    var body: some View {
        ScreenScaffold {
            Wordmark()
            Text(L.t(Strings.shared.app_tagline))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)

            Text(L.t(model.creatingAccount
                     ? Strings.shared.welcome_create_title
                     : Strings.shared.welcome_sign_in_title))
                .font(.title2.weight(.semibold))
                .padding(.top, 8)

            FormField(
                placeholder: L.t(Strings.shared.welcome_email_hint),
                text: $model.email,
                keyboard: .emailAddress,
                content: .username,
                isError: model.errorKey != nil
            )
            .onChange(of: model.email) { model.clearFormError() }
            .accessibilityLabel(L.t(Strings.shared.welcome_email_label))

            PasswordField(
                placeholder: L.t(Strings.shared.welcome_password_label),
                text: $model.password,
                content: model.creatingAccount ? .newPassword : .password,
                isError: model.errorKey != nil
            ) { model.submitCredentials() }
            .onChange(of: model.password) { model.clearFormError() }

            if model.creatingAccount {
                Text(L.t(Strings.shared.welcome_password_rule))
                    .font(.footnote)
                    .foregroundColor(Brand.textMuted)
            } else {
                // Prominent on purpose: with email as a way in, a forgotten
                // password is the commonest reason someone cannot get back.
                HStack {
                    Spacer()
                    Button(L.t(Strings.shared.welcome_forgot_password)) { model.startReset() }
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Brand.green)
                }
            }

            ErrorText(messageKey: model.errorKey)

            PrimaryButton(
                title: L.t(model.creatingAccount
                           ? Strings.shared.welcome_create_action
                           : Strings.shared.welcome_sign_in_action),
                enabled: model.canSubmitCredentials,
                busy: model.busy
            ) { model.submitCredentials() }

            Button(L.t(model.creatingAccount
                       ? Strings.shared.welcome_have_account
                       : Strings.shared.welcome_need_account)) {
                model.welcomeMode = model.creatingAccount ? .signIn : .createAccount
            }
            .font(.subheadline)
            .foregroundColor(Brand.green)
            .frame(maxWidth: .infinity)

            OrDivider().padding(.vertical, 8)
            ProviderButton(title: L.t(Strings.shared.welcome_google), enabled: !model.busy) {
                GoogleSignInLauncher.start(model: model)
            }
            // Sign in with Apple is mandatory on iOS wherever another provider
            // is offered (App Store guideline 4.8).
            AppleSignInButton(model: model)
            // Under the buttons it belongs to, not under the form: a provider
            // failing says nothing about what the user typed.
            ErrorText(messageKey: model.providerErrorKey)
        }
    }
}
