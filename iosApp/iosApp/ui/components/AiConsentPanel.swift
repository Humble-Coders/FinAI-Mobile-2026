import SwiftUI
import SharedLogic

/**
 Consent to AI processing, wherever something needs it first: a statement
 import (#31), or a question typed to the coach (#54). Android's `AiConsentPanel`.

 The policy's words are the server's, so they can change without an app
 release, and agreeing records exactly the version shown. Nothing is
 pre-ticked: agreeing is a deliberate act.
 */
struct AiConsentPanel: View {

    let title: String
    let policy: AiPolicy?
    @Binding var ticked: Bool
    let busy: Bool
    let errorKey: String?
    let onAgree: () -> Void
    let onNotNow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            ErrorText(messageKey: errorKey)
            if let policy {
                Text(policy.body)
                    .font(.subheadline)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
                Toggle(L.t(Strings.shared.import_consent_agree), isOn: $ticked)
                    .toggleStyle(CheckboxStyle())
            }
            GradientButton(
                title: L.t(Strings.shared.import_consent_continue),
                enabled: policy != nil && ticked && !busy,
                busy: busy,
                action: onAgree
            )
            Button(L.t(Strings.shared.import_consent_not_now), action: onNotNow)
                .frame(maxWidth: .infinity)
        }
    }
}
