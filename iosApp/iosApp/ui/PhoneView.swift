import SwiftUI
import SharedLogic

/// Phone entry — the primary signup route, and the step every other route ends
/// at (PRD §4.6).
struct PhoneView: View {
    @ObservedObject var model: OnboardingViewModel
    /// False once the user is signed in with a provider and is only attaching a
    /// number: offering the providers again there would be a loop.
    let showProviders: Bool

    @State private var pickerOpen = false

    var body: some View {
        ScreenScaffold {
            if showProviders {
                Wordmark()
                Text(L.t(Strings.shared.app_tagline))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
            } else {
                Text(L.t(Strings.shared.phone_link_title)).font(.title2.weight(.semibold))
                Text(L.t(Strings.shared.phone_link_body))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
            }

            HStack(spacing: 8) {
                Button { pickerOpen = true } label: {
                    Text(model.dialCode.display)
                        .font(.title3)
                        .foregroundColor(.primary)
                        .frame(minHeight: 52)
                        .padding(.horizontal, 14)
                        .background(Brand.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
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
                enabled: model.canSendCode,
                busy: model.busy
            ) { model.sendCode() }

            Text(L.t(Strings.shared.welcome_code_notice))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)

            if showProviders {
                OrDivider().padding(.vertical, 8)
                ProviderButton(title: L.t(Strings.shared.welcome_google), enabled: !model.busy) {}
                // Sign in with Apple is mandatory on iOS wherever another
                // provider is offered (App Store guideline 4.8).
                ProviderButton(title: L.t(Strings.shared.welcome_apple), enabled: !model.busy) {}
            }
        }
        .sheet(isPresented: $pickerOpen) {
            DialCodePicker { picked in
                model.dialCode = picked
                pickerOpen = false
            }
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
                    HStack {
                        Text(countryName(entry.region)).foregroundColor(.primary)
                        Spacer()
                        Text(entry.display).foregroundColor(Brand.textMuted)
                    }
                }
            }
            .searchable(text: $query)
            .navigationTitle(L.t(Strings.shared.welcome_dial_code_label))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
