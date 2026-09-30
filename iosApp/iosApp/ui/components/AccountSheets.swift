import SwiftUI
import SharedLogic

// The account chooser and the "add an account" sheet, shared by manual entry
// (#30) and the statement import's account step (#31), so both ask the same
// question the same way.

/// What a screen that can add an account in place provides to the sheet.
@MainActor
protocol NewAccountHost: ObservableObject {
    var newAccount: NewAccountDraft? { get }
    var newAccountErrorKey: String? { get }
    var newAccountNotice: NewAccountBlock? { get }
    var canCreateAccount: Bool { get }
    var creatingAccount: Bool { get }
    func setNewAccountName(_ name: String)
    func chooseNewAccountKind(_ kind: AccountKind)
    func createAccount()
    func cancelNewAccount()
}

extension ManualEntryViewModel: NewAccountHost {}

/// The household's accounts to choose from, and a way to add one.
struct AccountListSheet: View {
    let accounts: [Account]
    let chosenId: String?
    let onChosen: (String) -> Void
    let onAdd: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if accounts.isEmpty {
                    Text(L.t(Strings.shared.manual_entry_accounts_empty)).foregroundColor(Brand.textMuted)
                }
                ForEach(accounts, id: \.id) { account in
                    ChoiceRow(
                        title: account.name,
                        detail: [account.kind.labelKey.map { L.t($0) }, account.currency.isEmpty ? nil : account.currency]
                            .compactMap { $0 }.joined(separator: " · "),
                        selected: account.id == chosenId
                    ) { onChosen(account.id) }
                }
                Section {
                    Button(L.t(Strings.shared.manual_entry_account_add), action: onAdd)
                }
            }
            .navigationTitle(L.t(Strings.shared.manual_entry_account_label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel), action: onCancel)
                }
            }
        }
    }
}

/// A row in a chooser: the whole row taps, with a checkmark on the chosen one.
struct ChoiceRow: View {
    let title: String
    let detail: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).lineLimit(1).foregroundColor(.primary)
                    if let detail, !detail.isEmpty {
                        Text(detail).font(.footnote).foregroundColor(Brand.textMuted).lineLimit(2)
                    }
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark").foregroundColor(.primary).accessibilityHidden(true)
                }
            }
            .tappableRow()
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Adding an account without leaving the screen that needed it: named, and of a kind the
/// person chose — the kind starts unanswered.
struct NewAccountSheet<Host: NewAccountHost>: View {
    @ObservedObject var model: Host

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(
                        L.t(Strings.shared.account_new_name_label),
                        text: Binding(get: { model.newAccount?.name ?? "" }, set: { model.setNewAccountName($0) }),
                        prompt: Text(L.t(Strings.shared.account_new_name_hint))
                    )
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .onSubmit { dismissKeyboard() }
                } header: {
                    Text(L.t(Strings.shared.account_new_name_label))
                }
                Section {
                    ForEach(AccountKind.companion.choosable, id: \.self) { kind in
                        ChoiceRow(
                            title: kind.labelKey.map { L.t($0) } ?? "",
                            detail: nil,
                            selected: model.newAccount?.kind == kind
                        ) { model.chooseNewAccountKind(kind) }
                    }
                } header: {
                    Text(L.t(Strings.shared.account_new_kind_label))
                }
                Section {
                    ErrorText(messageKey: model.newAccountErrorKey)
                    if model.newAccountErrorKey == nil { ErrorText(messageKey: model.newAccountNotice?.messageKey) }
                    GradientButton(
                        title: L.t(Strings.shared.account_new_create),
                        enabled: model.canCreateAccount,
                        busy: model.creatingAccount
                    ) {
                        dismissKeyboard()
                        model.createAccount()
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle(L.t(Strings.shared.account_new_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.account_new_cancel)) { model.cancelNewAccount() }
                        .disabled(model.creatingAccount)
                }
            }
        }
        .interactiveDismissDisabled(model.creatingAccount)
    }
}
