import SwiftUI
import SharedLogic

/// One transaction typed in by hand (#30).
///
/// Nothing that decides where the money goes or which period it lands in is
/// filled in for the user: the account, date, amount and direction all start
/// empty, with a one-tap Today beside the date. Save stays disabled until the
/// shared rule is satisfied, and the notice under it says why.
///
/// `fromUnreadable`: opened because a statement could not be read (#31), so
/// the screen says why the person is typing.
struct ManualEntryView: View {
    @ObservedObject var model: ManualEntryViewModel
    let userId: String
    var fromUnreadable = false
    let onClose: () -> Void

    /// The draft as the system keeps it for this scene, so the app coming back
    /// after iOS reclaimed it reopens the entry being typed.
    @SceneStorage("manual_entry.draft") private var stored = ""
    @State private var choosingAccount = false
    /// "Add an account" was tapped in the account list. The new-account sheet
    /// opens once that list has finished closing: SwiftUI will not present a
    /// sheet while another is still going away, and would drop the request.
    @State private var addAccountNext = false
    @State private var choosingDate = false
    @State private var choosingCategory = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Brand.ground.ignoresSafeArea()
            if model.loading {
                // The app's coin loader covers the first load; underneath, ground.
                Color.clear
            } else if model.loadFailed {
                loadFailed
            } else {
                form
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .toolbar {
            // Number pads have no return key, so without this there is no way
            // off the keyboard but tapping the little that stays visible.
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(L.t(Strings.shared.action_done)) { dismissKeyboard() }
            }
        }
        .onAppear { model.bind(userId: userId, restoring: stored) }
        // Back to the front after a while away — possibly on another day.
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.refreshToday() } }
        .onDisappear { model.unbind() }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        // Spoken, not just shown: focus stays on Save, so without this a
        // VoiceOver user hears nothing — and the line may be the only place
        // they learn the entry is waiting for a category or a second look.
        // (A trait like `.updatesFrequently` does not speak anything.) Each
        // save follows an edit, which clears the line, so every outcome is a
        // change and is announced.
        .onChange(of: model.saved) { _, saved in
            if let saved {
                AccessibilityNotification.Announcement(L.t(saved.messageKey)).post()
            }
        }
        .sheet(isPresented: $choosingAccount, onDismiss: {
            guard addAccountNext else { return }
            addAccountNext = false
            model.openNewAccount()
        }) { accountSheet }
        .sheet(isPresented: newAccountShown) { NewAccountSheet(model: model) }
        .sheet(isPresented: $choosingDate) {
            DateSheet(chosen: model.draft.occurredOn, today: model.today) { date in
                model.chooseDate(date)
                choosingDate = false
            } onCancel: {
                choosingDate = false
            }
        }
        .sheet(isPresented: $choosingCategory) { categorySheet }
        .alert(
            L.t(Strings.shared.manual_entry_duplicate_title),
            isPresented: duplicateShown,
            actions: {
                Button(L.t(Strings.shared.manual_entry_duplicate_keep)) { model.keepDuplicate() }
                Button(L.t(Strings.shared.manual_entry_duplicate_cancel), role: .cancel) { model.dismissDuplicate() }
            },
            message: { Text(model.duplicateMessage ?? "") }
        )
    }

    private var header: some View {
        ZStack {
            Text(L.t(Strings.shared.manual_entry_title))
                .font(.headline)
                .lineLimit(1)
                .padding(.horizontal, 56)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Button {
                    close()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.body.weight(.semibold))
                        .tappableArea()
                }
                .accessibilityLabel(L.t(Strings.shared.action_back))
                .foregroundColor(.primary)
                Spacer()
            }
            .padding(.horizontal, 8)
        }
        .frame(height: 52)
        .background(Brand.ground)
    }

    private func close() {
        model.discard()
        stored = model.snapshot
        onClose()
    }

    private var loadFailed: some View {
        VStack(spacing: 16) {
            Text(L.t(Strings.shared.manual_entry_load_failed))
                .font(.body)
                .multilineTextAlignment(.center)
            // The specific cause, when there is one beyond "no connection".
            if let key = model.errorKey, key != Strings.shared.error_network {
                ErrorText(messageKey: key)
            }
            // No button when trying again cannot help: one that does nothing reads as broken.
            if model.canRetry {
                GradientButton(title: L.t(Strings.shared.manual_entry_retry)) { model.load() }
            }
        }
        .frame(maxWidth: 480)
        .padding(24)
    }

    private var form: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if fromUnreadable {
                        Text(L.t(Strings.shared.manual_entry_from_unreadable))
                            .font(.subheadline)
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
                    }

                    PickerField(
                        label: L.t(Strings.shared.manual_entry_account_label),
                        value: model.account?.name,
                        placeholder: L.t(Strings.shared.manual_entry_account_placeholder),
                        isError: model.notice == .noAccount
                    ) { choosingAccount = true }

                    HStack(alignment: .bottom, spacing: 12) {
                        PickerField(
                            label: L.t(Strings.shared.manual_entry_date_label),
                            value: model.dateLabel,
                            placeholder: L.t(Strings.shared.manual_entry_date_placeholder),
                            isError: model.notice == .noDate || model.notice == .futureDate
                        ) {
                            // The calendar's last pickable day is today as of
                            // now, not as of the last edit.
                            model.refreshToday()
                            choosingDate = true
                        }
                        Button(L.t(Strings.shared.manual_entry_date_today)) { model.chooseToday() }
                            .buttonStyle(.bordered)
                            .tint(model.isToday ? .primary : .secondary)
                            .frame(minHeight: 56)
                            .accessibilityAddTraits(model.isToday ? .isSelected : [])
                    }

                    AmountField(
                        label: L.t(Strings.shared.manual_entry_amount_label),
                        symbol: model.symbol,
                        placeholder: model.amountPlaceholder,
                        isError: [.noAmount, .amountNotMoney, .amountZero].contains { $0 == model.notice },
                        text: Binding(get: { model.draft.amount }, set: { model.setAmount($0) })
                    )

                    VStack(alignment: .leading, spacing: 8) {
                        FieldLabel(text: L.t(Strings.shared.manual_entry_direction_label))
                        // Neither is preselected: "money out" is the likelier
                        // answer, which is exactly why guessing it would go
                        // unnoticed when it is wrong.
                        Picker(L.t(Strings.shared.manual_entry_direction_label), selection: directionBinding) {
                            Text(L.t(Strings.shared.manual_entry_direction_out)).tag(TransactionDirection?.some(.debit))
                            Text(L.t(Strings.shared.manual_entry_direction_in)).tag(TransactionDirection?.some(.credit))
                        }
                        .pickerStyle(.segmented)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(model.notice == .noDirection ? Color.red : .clear, lineWidth: 1)
                        )
                    }

                    WizardField(
                        label: L.t(Strings.shared.manual_entry_description_label),
                        placeholder: L.t(Strings.shared.manual_entry_description_hint),
                        autocapitalization: .sentences,
                        isError: model.notice == .noDescription || model.notice == .descriptionTooLong,
                        submitLabel: .done,
                        onSubmit: { dismissKeyboard() },
                        text: Binding(get: { model.draft.description_ }, set: { model.setDescription($0) })
                    )

                    VStack(alignment: .leading, spacing: 8) {
                        PickerField(
                            label: L.t(Strings.shared.manual_entry_category_label),
                            value: model.categoryName ?? L.t(Strings.shared.manual_entry_category_none),
                            placeholder: L.t(Strings.shared.manual_entry_category_none)
                        ) { choosingCategory = true }
                        // Said out loud, so an empty category reads as a choice
                        // made, not a field forgotten.
                        if model.draft.categoryId == nil {
                            Text(L.t(Strings.shared.manual_entry_category_auto))
                                .font(.footnote)
                                .foregroundColor(Brand.textMuted)
                        }
                    }
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)

            VStack(spacing: 8) {
                ErrorText(messageKey: model.errorKey)
                // The same rule the button reads, said out loud.
                if model.errorKey == nil { ErrorText(messageKey: model.notice?.messageKey) }
                if let saved = model.saved {
                    Text(L.t(saved.messageKey))
                        .font(.subheadline.weight(.medium))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                GradientButton(
                    title: L.t(Strings.shared.manual_entry_save),
                    enabled: model.canSave,
                    busy: model.saving
                ) {
                    dismissKeyboard()
                    model.save()
                }
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
    }

    private var directionBinding: Binding<TransactionDirection?> {
        Binding(get: { model.draft.direction }, set: { if let chosen = $0 { model.chooseDirection(chosen) } })
    }

    private var newAccountShown: Binding<Bool> {
        Binding(get: { model.newAccount != nil }, set: { if !$0 { model.cancelNewAccount() } })
    }

    private var duplicateShown: Binding<Bool> {
        Binding(get: { model.duplicate != nil }, set: { if !$0 { model.dismissDuplicate() } })
    }

    private var accountSheet: some View {
        NavigationStack {
            List {
                if model.accounts.isEmpty {
                    Text(L.t(Strings.shared.manual_entry_accounts_empty)).foregroundColor(Brand.textMuted)
                }
                ForEach(model.accounts, id: \.id) { account in
                    ChoiceRow(
                        title: account.name,
                        detail: [account.kind.labelKey.map { L.t($0) }, account.currency.isEmpty ? nil : account.currency]
                            .compactMap { $0 }.joined(separator: " · "),
                        selected: account.id == model.draft.accountId
                    ) {
                        model.chooseAccount(account.id)
                        choosingAccount = false
                    }
                }
                Section {
                    Button(L.t(Strings.shared.manual_entry_account_add)) {
                        addAccountNext = true
                        choosingAccount = false
                    }
                }
            }
            .navigationTitle(L.t(Strings.shared.manual_entry_account_label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { choosingAccount = false }
                }
            }
        }
    }

    private var categorySheet: some View {
        NavigationStack {
            List {
                ChoiceRow(
                    title: L.t(Strings.shared.manual_entry_category_none),
                    detail: L.t(Strings.shared.manual_entry_category_auto),
                    selected: model.draft.categoryId == nil
                ) {
                    model.chooseCategory(nil)
                    choosingCategory = false
                }
                Section {
                    ForEach(model.categories, id: \.id) { category in
                        ChoiceRow(title: category.name, detail: nil, selected: category.id == model.draft.categoryId) {
                            model.chooseCategory(category.id)
                            choosingCategory = false
                        }
                    }
                }
            }
            .navigationTitle(L.t(Strings.shared.manual_entry_category_label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { choosingCategory = false }
                }
            }
        }
    }
}

/// A row in a chooser: the whole row taps, with a checkmark on the chosen one.
private struct ChoiceRow: View {
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

/// The calendar. Days after today cannot be picked — the limit the shared rule
/// holds Save to. Nothing is taken until Done.
private struct DateSheet: View {
    let chosen: Kotlinx_datetimeLocalDate?
    let today: Kotlinx_datetimeLocalDate
    let onChosen: (Kotlinx_datetimeLocalDate) -> Void
    let onCancel: () -> Void
    @State private var picked = Date()

    var body: some View {
        NavigationStack {
            DatePicker(
                L.t(Strings.shared.manual_entry_date_label),
                selection: $picked,
                in: ...DateSheet.date(today),
                displayedComponents: .date
            )
            .datePickerStyle(.graphical)
            .tint(.primary)
            .padding()
            .navigationTitle(L.t(Strings.shared.manual_entry_date_label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.t(Strings.shared.action_done)) {
                        if let date = DateSheet.localDate(picked) { onChosen(date) }
                    }
                }
            }
        }
        .presentationDetents([.large])
        .onAppear { picked = DateSheet.date(chosen ?? today) }
    }

    /// A calendar date as the device's midnight on that day.
    static func date(_ day: Kotlinx_datetimeLocalDate) -> Date {
        let formatter = isoFormatter
        return formatter.date(from: day.description()) ?? Date()
    }

    /// The device's calendar day of `date`, as the shared type.
    static func localDate(_ date: Date) -> Kotlinx_datetimeLocalDate? {
        Dates.shared.parse(iso: isoFormatter.string(from: date))
    }

    private static var isoFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }
}

/// Adding an account without leaving the entry: named, and of a kind the
/// person chose — the kind starts unanswered.
private struct NewAccountSheet: View {
    @ObservedObject var model: ManualEntryViewModel

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
