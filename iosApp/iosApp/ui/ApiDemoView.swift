import SwiftUI
import SharedLogic

/// Throwaway demo (ticket #6), replaced by real screens in M2. Every label
/// comes from the shared i18n registry — no Swift literals a user can read.
struct ApiDemoView: View {
    @StateObject private var model = ApiDemoViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(t(Strings.shared.demo_title)).font(.title)

                if let problemKey = model.configurationProblemKey {
                    Text(t(problemKey)).foregroundStyle(.red)
                } else {
                    Text(t(sessionLabel)).font(.subheadline)

                    switch model.session {
                    case .signedIn:
                        signedInActions
                    case .signedOut:
                        signInForm
                    default:
                        EmptyView()
                    }

                    if let errorKey = model.errorKey {
                        Text(t(errorKey)).foregroundStyle(.red)
                    }
                    if let afterExpire = model.tokenLifeAfterExpire {
                        evidenceRow(Strings.shared.demo_token_after_expire, afterExpire)
                    }
                    if let afterLoad = model.tokenLifeAfterLoad {
                        evidenceRow(Strings.shared.demo_token_after_load, afterLoad)
                    }
                    if let capabilities = model.capabilities {
                        CapabilitiesSummary(capabilities: capabilities, t: t)
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .disabled(model.busy)
        .onAppear { model.bind() }
        .onDisappear { model.unbind() }
    }

    private var signInForm: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(t(Strings.shared.demo_phone_label), text: $model.phone)
                .keyboardType(.phonePad)
                .textFieldStyle(.roundedBorder)
            Button(t(Strings.shared.demo_send_code)) { model.sendCode() }
            if model.codeSent {
                TextField(t(Strings.shared.demo_code_label), text: $model.code)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                Button(t(Strings.shared.demo_verify_code)) { model.verifyCode() }
            }
        }
    }

    private var signedInActions: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(t(Strings.shared.demo_fetch)) { model.loadCapabilities() }
            Button(t(Strings.shared.demo_expire_token)) { model.expireTokenThenLoad() }
            Button(t(Strings.shared.demo_sign_out)) { model.signOut() }
        }
    }

    private var sessionLabel: String {
        switch model.session {
        case .loading: return Strings.shared.demo_session_loading
        case .signedIn: return Strings.shared.demo_signed_in
        case .signedOut: return Strings.shared.demo_signed_out
        case .refreshFailed: return Strings.shared.demo_refresh_failed
        }
    }

    private func evidenceRow(_ key: String, _ seconds: Int64) -> some View {
        HStack {
            Text(t(key))
            Spacer()
            Text(String(seconds))
        }
    }

    private func t(_ key: String) -> String {
        LocalizationRegistry.shared.get(key: key, language: "en")
    }
}

/// Renders the capabilities payload as the API returned it. Values are data; only labels are localized.
private struct CapabilitiesSummary: View {
    let capabilities: Capabilities
    let t: (String) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            row(t(Strings.shared.demo_region), capabilities.region ?? t(Strings.shared.demo_value_none))
            row(t(Strings.shared.demo_currency), capabilities.currency)
            row(t(Strings.shared.demo_locale), capabilities.locale)
            Text(t(Strings.shared.demo_onboarding)).fontWeight(.medium)
            if capabilities.onboardingRequired.isEmpty {
                Text(t(Strings.shared.demo_value_none))
            } else {
                ForEach(capabilities.onboardingRequired.map { $0.wire }, id: \.self) { step in Text(step) }
            }
            Text(t(Strings.shared.demo_features)).font(.headline).padding(.top, 8)
            ForEach(capabilities.features.keys.sorted(), id: \.self) { key in
                row(key, featureStatus(key))
            }
        }
    }

    private func featureStatus(_ key: String) -> String {
        guard let reason = capabilities.blockingReason(featureKey: key) else { return t(Strings.shared.demo_on) }
        return t(reason.messageKey)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).fontWeight(.medium)
            Spacer()
            Text(value)
        }
    }
}
