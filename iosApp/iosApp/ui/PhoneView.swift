import SwiftUI
import SharedLogic

/// The phone step: once per account, after whichever sign-in created it
/// (PRD §4.6). The number is the key that stops one person becoming two
/// households, and it sets the region. It is never a way to sign in.
///
/// A number another account already has is refused under the field, and the
/// user types a different one. Nothing offers to sign in to or link with that
/// account (manager decision, 2026-09-15).
struct PhoneView: View {
    @ObservedObject var model: OnboardingViewModel

    @State private var pickerOpen = false

    var body: some View {
        CardScreen(busy: model.busy) {
            VStack(spacing: 16) {
            Text(L.t(Strings.shared.phone_link_title))
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(L.t(Strings.shared.phone_link_body))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)

            HStack(spacing: 8) {
                Button { pickerOpen = true } label: {
                    HStack(spacing: 6) {
                        Text(flagEmoji(region: model.dialCode.region))
                        Text(model.dialCode.display)
                    }
                    .font(.title3)
                    .foregroundColor(.primary)
                    .frame(minHeight: 52)
                    .padding(.horizontal, 12)
                    .background(Brand.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.t(Strings.shared.welcome_dial_code_label))

                TextField(L.t(Strings.shared.welcome_phone_hint), text: $model.phoneDigits)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .font(.title3)
                    .frame(minHeight: 52)
                    .padding(.horizontal, 14)
                    .background(Brand.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .accessibilityLabel(L.t(Strings.shared.welcome_phone_label))
            }

            ErrorText(messageKey: model.errorKey)

            PrimaryButton(
                title: L.t(Strings.shared.action_continue),
                // No spinner here: the coin on the card's edge is the indicator.
                enabled: model.canSendCode && !model.busy
            ) { model.sendCode() }

            Text(L.t(Strings.shared.welcome_code_notice))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)

            // The way out. Without it someone who signed in with the wrong
            // account is held here with no route back to the welcome screen.
            Button(L.t(Strings.shared.action_sign_out)) { model.signOut() }
                .foregroundColor(Brand.green)
                .tappableRow()
                .disabled(model.busy)
            }
            .padding(.top, 8)
        }
        // A dropdown anchored to the field rather than a sheet over the whole
        // screen: it is one small choice, and the number stays in view.
        .popover(isPresented: $pickerOpen) {
            DialCodePicker { picked in
                model.chooseDialCode(picked)
                pickerOpen = false
            }
            .frame(width: 320, height: 380)
            .presentationCompactAdaptation(.popover)
        }
    }
}

/// Picks a dialling prefix and nothing else: the country chosen here is never
/// sent as the user's region (manager decision, 2026-09-11).
private struct DialCodePicker: View {
    let onPick: (DialCode) -> Void
    @State private var query = ""

    private var entries: [DialCode] {
        let all = DialCodes.shared.all.sorted { countryName($0.region) < countryName($1.region) }
        let needle = query.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "+", with: "")
            .lowercased()
        guard !needle.isEmpty else { return all }
        return all.filter {
            countryName($0.region).lowercased().contains(needle) || $0.code.hasPrefix(needle)
        }
    }

    var body: some View {
        NavigationStack {
            List(entries, id: \.region) { entry in
                Button { onPick(entry) } label: {
                    HStack(spacing: 8) {
                        Text(flagEmoji(region: entry.region))
                        Text(countryName(entry.region)).foregroundColor(.primary)
                        Spacer()
                        Text(entry.display).foregroundColor(Brand.textMuted)
                    }
                }
            }
            .listStyle(.plain)
            .searchable(text: $query)
            .navigationTitle(L.t(Strings.shared.welcome_dial_code_label))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
