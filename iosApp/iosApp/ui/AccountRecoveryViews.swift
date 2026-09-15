import SwiftUI
import SharedLogic

/// Forgot password, first stage: which address gets the code.
struct ResetRequestView: View {
    @ObservedObject var model: OnboardingViewModel

    var body: some View {
        ScreenScaffold {
            Text(L.t(Strings.shared.reset_title)).font(.title2.weight(.semibold))
            Text(L.t(Strings.shared.reset_body))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
            FormField(
                placeholder: L.t(Strings.shared.welcome_email_hint),
                text: $model.email,
                keyboard: .emailAddress,
                content: .username,
                isError: model.errorKey != nil
            )
            .accessibilityLabel(L.t(Strings.shared.welcome_email_label))
            ErrorText(messageKey: model.errorKey)
            PrimaryButton(
                title: L.t(Strings.shared.reset_send),
                enabled: model.canRequestReset,
                busy: model.busy
            ) { model.requestResetCode() }
            Button(L.t(Strings.shared.action_cancel)) { model.cancelReset() }
                .foregroundColor(Brand.textMuted)
        }
    }
}

/// Forgot password, last stage. The user is already signed in by the code, so
/// cancelling here signs them out rather than letting them in without a password.
struct NewPasswordView: View {
    @ObservedObject var model: OnboardingViewModel

    var body: some View {
        ScreenScaffold {
            Text(L.t(Strings.shared.reset_new_password_title)).font(.title2.weight(.semibold))
            PasswordField(
                placeholder: L.t(Strings.shared.reset_new_password_label),
                text: $model.password,
                content: .newPassword,
                isError: model.errorKey != nil
            ) { model.saveNewPassword() }
            Text(L.t(Strings.shared.welcome_password_rule))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
            ErrorText(messageKey: model.errorKey)
            PrimaryButton(
                title: L.t(Strings.shared.reset_save),
                enabled: model.canSaveNewPassword,
                busy: model.busy
            ) { model.saveNewPassword() }
            Button(L.t(Strings.shared.action_cancel)) { model.cancelReset() }
                .foregroundColor(Brand.textMuted)
        }
    }
}

/// Signed in to the account that has the number: remove the empty account,
/// then add the sign-in method that made it.
///
/// Two deliberate steps rather than one. Linking needs a fresh ID token from the
/// provider's own sheet, and asking for it before the empty account is gone
/// would fail, because Supabase will not attach an identity another account holds.
struct LinkAccountView: View {
    @ObservedObject var model: OnboardingViewModel

    private var provider: SocialProvider? { model.pendingLink?.provider }

    private var providerName: String? {
        switch provider {
        case .google: return L.t(Strings.shared.provider_google)
        case .apple: return L.t(Strings.shared.provider_apple)
        default: return nil
        }
    }

    var body: some View {
        ScreenScaffold {
            Text(L.t(Strings.shared.link_title)).font(.title2.weight(.semibold))
            Text(providerName.map { L.t(Strings.shared.link_body_provider, $0) }
                 ?? L.t(Strings.shared.link_body_email))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
            ErrorText(messageKey: model.errorKey)

            if !model.orphanRemoved {
                PrimaryButton(title: L.t(Strings.shared.action_continue), busy: model.busy) {
                    model.removeOrphan()
                }
            } else if provider == .google {
                ProviderButton(
                    title: L.t(Strings.shared.link_add_provider, providerName ?? ""),
                    enabled: !model.busy
                ) { GoogleSignInLauncher.start(model: model) }
                ErrorText(messageKey: model.providerErrorKey)
            } else if provider == .apple {
                // Apple requires its own button; the model links rather than
                // signs in, because the empty account is already gone.
                AppleSignInButton(model: model)
                ErrorText(messageKey: model.providerErrorKey)
            }

            Button(L.t(Strings.shared.link_cancel)) { model.cancelLink() }
                .foregroundColor(Brand.textMuted)
                .disabled(model.busy)
        }
    }
}
