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
    @Environment(\.colorScheme) private var scheme
    /// Content has scrolled under the header, which then turns solid.
    @State private var scrolledUnder = false

    var body: some View {
        ZStack {
            MintBackdrop(decoration: "creditcard.fill")
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
        MintHeader(title: L.t(Strings.shared.manual_entry_title), solid: scrolledUnder) { close() }
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
                    // A marker for where the content rests; the negative padding takes back
                    // the stack's spacing, so it adds no gap.
                    Color.clear.frame(height: 0).padding(.bottom, -20).modifier(ScrolledUnder(scrolled: $scrolledUnder))
                    if fromUnreadable {
                        Text(L.t(Strings.shared.manual_entry_from_unreadable))
                            .font(.subheadline)
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Brand.sheet, in: RoundedRectangle(cornerRadius: 16))
                    }

                    MintPanel {
                    directionChoice

                    accountField

                    dateField

                    AmountField(
                        label: L.t(Strings.shared.manual_entry_amount_label),
                        symbol: model.symbol,
                        placeholder: model.amountPlaceholder,
                        isError: [.noAmount, .amountNotMoney, .amountZero].contains { $0 == model.notice },
                        text: Binding(get: { model.draft.amount }, set: { model.setAmount($0) })
                    )

                    WizardField(
                        label: L.t(Strings.shared.manual_entry_description_label),
                        placeholder: L.t(Strings.shared.manual_entry_description_hint),
                        autocapitalization: .sentences,
                        isError: model.notice == .noDescription || model.notice == .descriptionTooLong,
                        submitLabel: .done,
                        onSubmit: { dismissKeyboard() },
                        leading: "doc.text",
                        text: Binding(get: { model.draft.description_ }, set: { model.setDescription($0) })
                    )

                    VStack(alignment: .leading, spacing: 8) {
                        PickerField(
                            label: L.t(Strings.shared.manual_entry_category_label),
                            value: model.categoryName ?? L.t(Strings.shared.manual_entry_category_none),
                            placeholder: L.t(Strings.shared.manual_entry_category_none),
                            leading: "tag",
                            trailing: "chevron.right"
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
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
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
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
    }

    // MARK: - The design's fields

    /// Two cards, in first as the design has it. Neither is preselected:
    /// "money out" is the likelier answer, which is exactly why guessing it
    /// would go unnoticed when it is wrong.
    private var directionChoice: some View {
        let dark = scheme == .dark
        return HStack(spacing: 12) {
            ForEach([TransactionDirection.credit, TransactionDirection.debit], id: \.self) { direction in
                let selected = model.draft.direction == direction
                Button { model.chooseDirection(direction) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: direction == .credit ? "arrow.up" : "arrow.down")
                            .font(.body.weight(.semibold))
                        Text(L.t(direction == .credit
                            ? Strings.shared.manual_entry_direction_in
                            : Strings.shared.manual_entry_direction_out))
                            .font(.subheadline.weight(selected ? .semibold : .medium))
                            .lineLimit(2)
                    }
                    .foregroundColor(selected ? Mint.greenText(dark) : .primary)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .background(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(selected ? Brand.green.opacity(0.12) : Mint.card(dark))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(
                            selected ? Brand.green : (model.notice == .noDirection ? Color.red : Mint.edge(dark)),
                            lineWidth: selected || model.notice == .noDirection ? 1.5 : 1
                        )
                    )
                    .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L.t(Strings.shared.manual_entry_direction_label))
    }

    /// The account, as the design has it: its bank tile, its name and kind, and
    /// a chevron. Tapping opens the same account list, with "Add an account".
    private var accountField: some View {
        let account = model.account
        let index = model.accounts.firstIndex { $0.id == account?.id } ?? 0
        return VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: L.t(Strings.shared.manual_entry_account_label))
            Button { choosingAccount = true } label: {
                HStack(spacing: 12) {
                    IconTile(
                        symbol: "building.columns.fill",
                        accent: Mint.accountTints[index % Mint.accountTints.count],
                        size: 44, iconSize: 20
                    )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(account?.name ?? L.t(Strings.shared.manual_entry_account_placeholder))
                            .font(.headline.weight(.regular))
                            .foregroundColor(account == nil ? Brand.textMuted.opacity(0.7) : .primary)
                            .lineLimit(1)
                        if let account, let detail = StatementImportView.detail(account) {
                            Text(detail).font(.subheadline).foregroundColor(Brand.textMuted).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Brand.textMuted)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(minHeight: 68)
                .fieldFrame(focused: false, isError: model.notice == .noAccount)
                .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.t(Strings.shared.manual_entry_account_label))
            .accessibilityValue(account?.name ?? L.t(Strings.shared.manual_entry_account_placeholder))
        }
    }

    /// The date with its calendar mark, "Today, 15 Sep 2026" when it is today,
    /// and the one-tap Today kept beside it until today is the date chosen.
    private var dateField: some View {
        let value = model.dateLabel.map {
            model.isToday ? L.t(Strings.shared.manual_entry_date_today_value, $0) : $0
        }
        return VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: L.t(Strings.shared.manual_entry_date_label))
            HStack(spacing: 12) {
                Button {
                    // The calendar's last pickable day is today as of now, not
                    // as of the last edit.
                    model.refreshToday()
                    choosingDate = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "calendar").foregroundColor(Brand.textMuted).accessibilityHidden(true)
                        Text(value ?? L.t(Strings.shared.manual_entry_date_placeholder))
                            .foregroundColor(value == nil ? Brand.textMuted.opacity(0.6) : .primary)
                            .lineLimit(2)
                        Spacer(minLength: 8)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.t(Strings.shared.manual_entry_date_label))
                .accessibilityValue(value ?? L.t(Strings.shared.manual_entry_date_placeholder))
                if !model.isToday {
                    Button(L.t(Strings.shared.manual_entry_date_today)) { model.chooseToday() }
                        .buttonStyle(.bordered)
                        .tint(.secondary)
                        .controlSize(.small)
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Brand.textMuted)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 56)
            .fieldFrame(focused: false, isError: model.notice == .noDate || model.notice == .futureDate)
        }
    }

    private var newAccountShown: Binding<Bool> {
        Binding(get: { model.newAccount != nil }, set: { if !$0 { model.cancelNewAccount() } })
    }

    private var duplicateShown: Binding<Bool> {
        Binding(get: { model.duplicate != nil }, set: { if !$0 { model.dismissDuplicate() } })
    }

    private var accountSheet: some View {
        AccountListSheet(
            accounts: model.accounts,
            chosenId: model.draft.accountId,
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

/// The calendar. Days after today cannot be picked — the limit the shared rule
/// holds Save to. Nothing is taken until Done.
struct DateSheet: View {
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
