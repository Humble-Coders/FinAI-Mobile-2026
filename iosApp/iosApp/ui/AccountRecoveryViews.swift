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
                .tappableRow()
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
                .tappableRow()
        }
    }
}
