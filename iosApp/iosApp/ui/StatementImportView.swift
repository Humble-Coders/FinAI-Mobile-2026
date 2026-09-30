import PhotosUI
import SwiftUI
import SharedLogic
import UIKit
import UniformTypeIdentifiers

/// Importing a statement (#31): which account, which file, consent the first
/// time, the read, and how it ended.
///
/// The waits are the app's one coin loader, with a caption (RootView passes
/// `model.loaderCaption`); this view draws what sits underneath. Every
/// outcome's words come from the shared rules.
struct StatementImportView: View {
    @ObservedObject var model: StatementImportViewModel
    let userId: String
    let onClose: () -> Void
    let onTypeInstead: () -> Void

    /// The account, the file and whether a read was under way, kept for the
    /// scene so the app coming back reads the same file again.
    @SceneStorage("statement_import.state") private var stored = ""
    @State private var choosingAccount = false
    @State private var addAccountNext = false
    @State private var pickingFile = false
    @State private var photo: PhotosPickerItem?
    @State private var takingPhoto = false
    /// Held here only — never in scene storage — and cleared once used.
    @State private var password = ""

    var body: some View {
        ZStack {
            Brand.ground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    content
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .onAppear { model.bind(userId: userId, restoring: stored) }
        .onDisappear { model.unbind() }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: $choosingAccount, onDismiss: {
            guard addAccountNext else { return }
            addAccountNext = false
            model.openNewAccount()
        }) {
            AccountListSheet(
                accounts: model.accounts,
                chosenId: model.accountId,
                onChosen: { id in
                    model.chooseAccount(id)
                    choosingAccount = false
                },
                onAdd: {
                    addAccountNext = true
                    choosingAccount = false
                },
                onCancel: { choosingAccount = false }
            )
        }
        .sheet(isPresented: newAccountShown) { NewAccountSheet(model: model) }
        .fileImporter(isPresented: $pickingFile, allowedContentTypes: [.pdf, .image]) { result in
            if case .success(let url) = result, let copy = try? ImportFiles.adopt(url) {
                model.filePicked(copy)
            }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9),
                   let file = try? ImportFiles.write(jpeg) {
                    model.filePicked(file)
                }
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker(
                onTaken: { data in
                    takingPhoto = false
                    if let file = try? ImportFiles.write(data) { model.filePicked(file) }
                },
                onCancel: { takingPhoto = false }
            )
            .ignoresSafeArea()
        }
    }

    private var newAccountShown: Binding<Bool> {
        Binding(get: { model.newAccount != nil }, set: { if !$0 { model.cancelNewAccount() } })
    }

    private func close() {
        model.discard()
        stored = model.snapshot
        onClose()
    }

    private var header: some View {
        ZStack {
            Text(L.t(Strings.shared.import_title))
                .font(.headline)
                .lineLimit(1)
                .padding(.horizontal, 56)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Button {
                    if model.step == .chooseFile { model.backToAccount() } else if !model.working { close() }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.body.weight(.semibold))
                        .tappableArea()
                }
                .accessibilityLabel(L.t(Strings.shared.action_back))
                .foregroundColor(.primary)
                .disabled(model.working)
                Spacer()
            }
            .padding(.horizontal, 8)
        }
        .frame(height: 52)
        .background(Brand.ground)
    }

    @ViewBuilder
    private var content: some View {
        switch model.step {
        case .chooseAccount: accountStep
        case .chooseFile: fileStep
        case .consent: consentStep
        case .password: passwordStep
        case .done: doneStep
        case .failed: failedStep
        // The coin loader covers these; its caption says which.
        default: Color.clear.frame(height: 1)
        }
    }

    private func title(_ key: String) -> some View {
        Text(L.t(key))
            .font(.title2.weight(.semibold))
            .accessibilityAddTraits(.isHeader)
    }

    private var accountStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_account_title)
            Text(L.t(Strings.shared.import_account_hint)).foregroundColor(Brand.textMuted)
            if let key = model.accountsErrorKey {
                ErrorText(messageKey: key)
                Button(L.t(Strings.shared.import_try_again)) { model.loadAccounts() }
            } else {
                PickerField(
                    label: L.t(Strings.shared.manual_entry_account_label),
                    value: model.account?.name,
                    placeholder: L.t(Strings.shared.manual_entry_account_placeholder)
                ) { choosingAccount = true }
                // Not optional and not defaulted: Continue waits for a real choice.
                GradientButton(title: L.t(Strings.shared.action_continue), enabled: model.canContinueFromAccount) {
                    model.continueFromAccount()
                }
            }
        }
    }

    private var fileStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_file_title)
            // The true thing, said plainly: read here, never uploaded.
            Text(L.t(Strings.shared.import_on_device))
                .font(.subheadline)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
            GradientButton(title: L.t(Strings.shared.import_pick_file)) { pickingFile = true }
            PhotosPicker(selection: $photo, matching: .images) {
                Text(L.t(Strings.shared.import_pick_photo)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            if CameraPicker.isAvailable {
                Button { takingPhoto = true } label: {
                    Text(L.t(Strings.shared.import_take_photo)).font(.headline).tappableRow(minHeight: 52)
                }
                .buttonStyle(.bordered)
                .tint(.primary)
            }
            Button(model.account?.name ?? L.t(Strings.shared.manual_entry_account_placeholder)) { model.backToAccount() }
                .lineLimit(1)
                .foregroundColor(Brand.textMuted)
        }
    }

    private var consentStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_consent_title)
            ErrorText(messageKey: model.consentErrorKey)
            // The server's words, so they can change without an app release.
            if let policy = model.policy {
                Text(policy.body)
                    .font(.subheadline)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
                Toggle(L.t(Strings.shared.import_consent_agree), isOn: $model.consentTicked)
                    .toggleStyle(CheckboxStyle())
            }
            GradientButton(
                title: L.t(Strings.shared.import_consent_continue),
                enabled: model.canAgree,
                busy: model.consentBusy
            ) { model.agree() }
            Button(L.t(Strings.shared.import_consent_not_now)) { model.declineConsent() }
                .frame(maxWidth: .infinity)
        }
    }

    private var passwordStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L.t(model.passwordWrong ? Strings.shared.statement_password_wrong : Strings.shared.statement_password_prompt))
            SecureField(L.t(Strings.shared.import_password_label), text: $password)
                .textContentType(.password)
                .submitLabel(.go)
                .onSubmit(submitPassword)
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .fieldFrame(focused: false, isError: model.passwordWrong)
            GradientButton(title: L.t(Strings.shared.import_password_submit), enabled: !password.isEmpty) {
                submitPassword()
            }
            Button(L.t(Strings.shared.import_choose_another)) { model.chooseAnotherFile() }
                .frame(maxWidth: .infinity)
        }
    }

    private func submitPassword() {
        let value = password
        password = ""
        model.submitPassword(value)
    }

    private var doneStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_done_title)
            VStack(alignment: .leading, spacing: 8) {
                ForEach(model.summary, id: \.self) { Text($0) }
            }
            .onAppear { AccessibilityNotification.Announcement(model.summary.joined(separator: ". ")).post() }
            // The review screen (#32) is not built yet; say where the rows are.
            if model.needsReview > 0 {
                Text(L.t(Strings.shared.import_review_later)).foregroundColor(Brand.textMuted)
            }
            GradientButton(title: L.t(Strings.shared.import_done)) { close() }
        }
    }

    private var failedStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            title(Strings.shared.import_failed_title)
            if let message = model.problemMessage {
                Text(message)
                    .onAppear { AccessibilityNotification.Announcement(message).post() }
            }
            if model.offersRetry {
                GradientButton(title: L.t(Strings.shared.import_try_again)) { model.retry() }
            }
            if model.offersManualEntry {
                if model.offersRetry {
                    Button { typeInstead() } label: {
                        Text(L.t(Strings.shared.import_type_instead)).font(.headline).tappableRow(minHeight: 52)
                    }
                    .buttonStyle(.bordered)
                    .tint(.primary)
                } else {
                    GradientButton(title: L.t(Strings.shared.import_type_instead)) { typeInstead() }
                }
            }
            Button(L.t(Strings.shared.import_choose_another)) { model.chooseAnotherFile() }
                .frame(maxWidth: .infinity)
            if model.offersDiagnostics { diagnosticsOffer }
            if let thanks = model.diagnosticsThanks {
                Text(thanks).foregroundColor(Brand.textMuted)
            }
        }
    }

    private func typeInstead() {
        model.discard()
        stored = model.snapshot
        onTypeInstead()
    }

    /// Asked per import, unticked, and only here — never after a clean import.
    private var diagnosticsOffer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t(Strings.shared.import_diagnostics_title)).font(.headline)
            Text(L.t(Strings.shared.import_diagnostics_body)).font(.subheadline)
            Toggle(L.t(Strings.shared.import_diagnostics_agree), isOn: $model.diagnosticsTicked)
                .toggleStyle(CheckboxStyle())
            Button { model.sendDiagnostics() } label: {
                Text(L.t(Strings.shared.import_diagnostics_send)).font(.headline).tappableRow(minHeight: 48)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            .disabled(!model.canSendDiagnostics)
        }
        .padding(16)
        .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// A box nothing pre-ticks, with its label as the tap target.
private struct CheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(spacing: 12) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundColor(.primary)
                    .accessibilityHidden(true)
                configuration.label.foregroundColor(.primary)
                Spacer()
            }
            .tappableRow(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(configuration.isOn ? [.isSelected] : [])
    }
}
