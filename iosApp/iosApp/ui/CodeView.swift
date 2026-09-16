import SwiftUI
import SharedLogic

/// Six digits, and the way back to a mistyped address or number.
///
/// One view for every code — SMS for the phone step, email for signup and a
/// password reset — so they cannot drift apart.
struct CodeView: View {
    @ObservedObject var model: OnboardingViewModel
    /// The number or address, shown so a typo is noticed.
    let sentTo: String
    /// The label for going back to fix `sentTo`.
    let editKey: String
    var hintKey: String?
    let onVerify: () -> Void
    let onResend: () -> Void
    let onEdit: () -> Void

    @FocusState private var focused: Bool

    var body: some View {
        CardScreen {
            VStack(alignment: .leading, spacing: 16) {
            Text(L.t(Strings.shared.code_title)).font(.title2.weight(.semibold))
            Text(L.t(Strings.shared.code_sent_to, sentTo))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
            if let hintKey {
                Text(L.t(hintKey))
                    .font(.footnote)
                    .foregroundColor(Brand.textMuted)
            }

            cells
                .contentShape(Rectangle())
                .onTapGesture { focused = true }

            ErrorText(messageKey: model.errorKey)

            PrimaryButton(
                title: L.t(Strings.shared.code_verify),
                enabled: model.canVerify,
                busy: model.busy
            ) { onVerify() }

            if model.canResend {
                Button(L.t(Strings.shared.code_resend)) { onResend() }
                    .foregroundColor(Brand.green)
                    .tappableRow()
            } else {
                Text(L.t(Strings.shared.code_resend_in, model.resendCountdown))
                    .font(.footnote)
                    .foregroundColor(Brand.textMuted)
            }

            Button(L.t(editKey)) { onEdit() }
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
                .tappableRow()
            }
            .padding(.top, 8)
        }
        .onAppear { focused = true }
    }

    /// Six boxes over ONE real field. Six separate fields would break paste and
    /// fight the keyboard's one-tap SMS suggestion.
    private var cells: some View {
        ZStack {
            TextField("", text: $model.code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($focused)
                .opacity(0.001)
                .accessibilityLabel(L.t(Strings.shared.code_title))

            HStack(spacing: 8) {
                ForEach(0 ..< OnboardingViewModel.codeLength, id: \.self) { index in
                    let characters = Array(model.code)
                    let active = index == characters.count
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Brand.surface)
                        .aspectRatio(1, contentMode: .fit)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10).stroke(
                                model.errorKey != nil ? .red : (active ? Brand.green : Brand.border),
                                lineWidth: active || model.errorKey != nil ? 2 : 1
                            )
                        )
                        .overlay(
                            Text(index < characters.count ? String(characters[index]) : "")
                                .font(.title.weight(.medium))
                        )
                }
            }
            .allowsHitTesting(false)
        }
    }
}
