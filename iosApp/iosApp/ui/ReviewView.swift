import SwiftUI
import SharedLogic

/**
 The review queue (#32): the rows extraction could not resolve.

 Not an error list to apologise for — the app asking someone to confirm their
 own money. Twenty right rows and two wrong ones is the normal case, so
 confirming is one tap and fixing is two.
 */
struct ReviewView: View {
    @ObservedObject var model: ReviewViewModel
    let userId: String
    let onClose: () -> Void

    /// A correction in progress, kept for the scene so the app coming back
    /// after iOS reclaimed it still has what was typed.
    @SceneStorage("review.correction") private var stored = ""

    var body: some View {
        ZStack {
            Brand.ground.ignoresSafeArea()
            if model.loading {
                // The app's coin loader covers the first load.
                Color.clear
            } else if model.loadFailed {
                loadFailed
            } else if model.isEmpty {
                empty
            } else {
                queue
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .onAppear { model.bind(userId: userId, restoring: stored) }
        .onDisappear { model.unbind() }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: editingShown) { CorrectionSheet(model: model) }
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { model.editing != nil }, set: { if !$0 { model.cancelEdit() } })
    }

    private func close() {
        model.flushPendingDelete()
        stored = model.snapshot
        onClose()
    }

    private var header: some View {
        ZStack {
            Text(L.t(Strings.shared.review_title))
                .font(.headline)
                .lineLimit(1)
                .padding(.horizontal, 56)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Button { close() } label: {
                    Image(systemName: "chevron.left").font(.body.weight(.semibold)).tappableArea()
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

    private var loadFailed: some View {
        VStack(spacing: 16) {
            Text(L.t(Strings.shared.review_load_failed)).multilineTextAlignment(.center)
            if let key = model.errorKey, key != Strings.shared.error_network {
                ErrorText(messageKey: key)
            }
            GradientButton(title: L.t(Strings.shared.review_retry)) { model.load() }
        }
        .frame(maxWidth: 480)
        .padding(24)
    }

    /// Nothing waiting is a good outcome, and is worded as one.
    private var empty: some View {
        VStack(spacing: 12) {
            Text(L.t(Strings.shared.review_empty_title))
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(L.t(Strings.shared.review_empty_body))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
            GradientButton(title: L.t(Strings.shared.import_done)) { close() }
                .padding(.top, 8)
        }
        .frame(maxWidth: 480)
        .padding(24)
    }

    private var queue: some View {
        VStack(spacing: 0) {
            // Re-reading after an action: a thin line, not the coin, so the
            // list keeps its place.
            if model.refreshing { ProgressView().progressViewStyle(.linear) }
            List {
                Section {
                    Text(L.t(Strings.shared.review_intro))
                        .font(.subheadline)
                        .foregroundColor(Brand.textMuted)
                        .listRowSeparator(.hidden)
                }
                ForEach(model.byDate, id: \.day) { group in
                    Section {
                        ForEach(group.rows, id: \.id) { row in
                            ReviewRowView(row: row, model: model)
                        }
                    } header: {
                        Text(Dates.shared.parse(iso: group.day).map { Dates.shared.display(date: $0) } ?? group.day)
                    }
                }
                if model.loadingMore {
                    ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden)
                } else if model.canLoadMore {
                    // One page ahead of the bottom, so scrolling never waits.
                    Color.clear
                        .frame(height: 1)
                        .listRowSeparator(.hidden)
                        .onAppear { model.loadMore() }
                }
            }
            .listStyle(.plain)
            .scrollDismissesKeyboard(.interactively)

            VStack(spacing: 8) {
                // Keyed by position, not by the text: two actions can
                // produce the same sentence, and duplicate ids in a ForEach
                // drop rows.
                ForEach(Array(model.announcements.enumerated()), id: \.offset) { _, message in
                    Text(message)
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .onAppear { AccessibilityNotification.Announcement(message).post() }
                }
                ErrorText(messageKey: model.errorKey)
                if model.pendingDelete != nil { undoBar }
                GradientButton(
                    title: model.confirmAllLabel,
                    enabled: model.canConfirmAll,
                    busy: model.confirmingAll
                ) { model.confirmAll() }
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
    }

    /// Deleted — but nothing has been sent yet, so Undo simply puts it back.
    private var undoBar: some View {
        HStack {
            Text(L.t(Strings.shared.review_deleted))
                .font(.subheadline)
                .foregroundColor(Brand.onGreen)
                .onAppear { AccessibilityNotification.Announcement(L.t(Strings.shared.review_deleted)).post() }
            Spacer()
            Button(L.t(Strings.shared.review_undo)) { model.undoDelete() }
                .foregroundColor(Brand.onGreen)
                .tappableArea()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Brand.green, in: RoundedRectangle(cornerRadius: 16))
    }
}

/**
 One row: what it is, what it cost, why it is here.

 Money out and in are told apart by a sign and a word, never by colour alone.
 */
private struct ReviewRowView: View {
    let row: SharedLogic.Transaction
    @ObservedObject var model: ReviewViewModel

    private var busy: Bool { model.isBusy(row.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text((row.description_?.isEmpty == false ? row.description_! : L.t(Strings.shared.review_reason_other)))
                        .lineLimit(1)
                    Text(model.categoryName(for: row) ?? L.t(Strings.shared.review_category_choose))
                        .font(.footnote)
                        .foregroundColor(Brand.textMuted)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing) {
                    Text((row.direction == .credit ? "+" : "−") + model.amount(for: row))
                        .fontWeight(.medium)
                    Text(L.t(row.direction == .credit
                        ? Strings.shared.manual_entry_direction_in
                        : Strings.shared.manual_entry_direction_out))
                        .font(.caption)
                        .foregroundColor(Brand.textMuted)
                }
            }
            Text(L.t(model.reasonKey(for: row)))
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Brand.surface, in: Capsule())
            // A suspected duplicate shows what it matched before anything can
            // be done to it: this is the one case where deleting loses data.
            if let duplicate = model.duplicateText(for: row) {
                Text(duplicate).font(.footnote).foregroundColor(Brand.textMuted)
            }
            ErrorText(messageKey: model.error(for: row.id))
            if busy {
                Text(L.t(Strings.shared.review_row_saving))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .frame(minHeight: 44, alignment: .leading)
            } else {
                HStack(spacing: 16) {
                    Button(L.t(row.duplicateOf != nil
                        ? Strings.shared.review_not_the_same
                        : Strings.shared.action_done)) { model.confirm(row.id) }
                    Button(L.t(Strings.shared.review_edit_title)) { model.edit(row.id) }
                    Button(L.t(Strings.shared.review_delete), role: .destructive) { model.delete(row.id) }
                }
                .buttonStyle(.borderless)
                .frame(minHeight: 44)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { if !busy { model.edit(row.id) } }
    }
}

/// Fixing a row: date, amount, direction, description, category.
private struct CorrectionSheet: View {
    @ObservedObject var model: ReviewViewModel
    @State private var choosingCategory = false
    @State private var choosingDate = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PickerField(
                        label: L.t(Strings.shared.manual_entry_date_label),
                        value: model.draft.occurredOn.map { Dates.shared.display(date: $0) },
                        placeholder: L.t(Strings.shared.manual_entry_date_placeholder),
                        isError: model.editNotice == .noDate || model.editNotice == .futureDate
                    ) { choosingDate = true }
                    AmountField(
                        label: L.t(Strings.shared.manual_entry_amount_label),
                        symbol: Money.shared.symbol(currency: model.currency),
                        placeholder: Money.shared.normalize(
                            raw: "0",
                            fractionDigits: Money.shared.fractionDigits(currency: model.currency)
                        ) ?? "",
                        isError: [.noAmount, .amountNotMoney, .amountZero].contains { $0 == model.editNotice },
                        large: false,
                        text: Binding(get: { model.draft.amount }, set: { model.setAmount($0) })
                    )
                    Picker(L.t(Strings.shared.manual_entry_direction_label), selection: directionBinding) {
                        Text(L.t(Strings.shared.manual_entry_direction_out)).tag(TransactionDirection?.some(.debit))
                        Text(L.t(Strings.shared.manual_entry_direction_in)).tag(TransactionDirection?.some(.credit))
                    }
                    .pickerStyle(.segmented)
                    WizardField(
                        label: L.t(Strings.shared.manual_entry_description_label),
                        placeholder: L.t(Strings.shared.manual_entry_description_hint),
                        autocapitalization: .sentences,
                        isError: model.editNotice == .noDescription || model.editNotice == .descriptionTooLong,
                        submitLabel: .done,
                        onSubmit: { dismissKeyboard() },
                        text: Binding(get: { model.draft.description_ }, set: { model.setDescription($0) })
                    )
                    PickerField(
                        label: L.t(Strings.shared.review_category_label),
                        value: model.editCategoryName,
                        placeholder: L.t(Strings.shared.review_category_choose)
                    ) { choosingCategory = true }
                }
                Section {
                    ErrorText(messageKey: model.editErrorKey)
                    if model.editErrorKey == nil { ErrorText(messageKey: model.editNotice?.messageKey) }
                    GradientButton(
                        title: L.t(Strings.shared.review_edit_save),
                        enabled: model.canSaveCorrection,
                        busy: model.saving
                    ) {
                        dismissKeyboard()
                        model.saveCorrection()
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle(L.t(Strings.shared.review_edit_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.review_edit_cancel)) { model.cancelEdit() }
                        .disabled(model.saving)
                }
                // Number pads have no return key.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { dismissKeyboard() }
                }
            }
            .sheet(isPresented: $choosingCategory) { categorySheet }
            .sheet(isPresented: newCategoryShown) { NewCategorySheet(model: model) }
            .sheet(isPresented: $choosingDate) {
                CorrectionDateSheet(
                    chosen: model.draft.occurredOn,
                    today: model.today,
                    onChosen: { date in
                        model.setDate(date)
                        choosingDate = false
                    },
                    onCancel: { choosingDate = false }
                )
            }
        }
        .interactiveDismissDisabled(model.saving)
    }

    private var directionBinding: Binding<TransactionDirection?> {
        Binding(get: { model.draft.direction }, set: { if let chosen = $0 { model.setDirection(chosen) } })
    }

    private var newCategoryShown: Binding<Bool> {
        Binding(get: { model.newCategoryName != nil }, set: { if !$0 { model.cancelNewCategory() } })
    }

    private var categorySheet: some View {
        NavigationStack {
            List {
                ForEach(model.pickableCategories, id: \.category.id) { item in
                    ChoiceRow(title: item.name, detail: nil, selected: item.category.id == model.draft.categoryId) {
                        model.chooseCategory(item.category.id)
                        choosingCategory = false
                    }
                }
                Section {
                    Button(L.t(Strings.shared.review_category_new)) {
                        choosingCategory = false
                        model.openNewCategory()
                    }
                }
            }
            .navigationTitle(L.t(Strings.shared.review_category_label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { choosingCategory = false }
                }
            }
        }
    }
}

/// The calendar, with the same limit the shared rule holds a correction to.
private struct CorrectionDateSheet: View {
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
                in: ...ReviewDates.date(today),
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
                        if let date = ReviewDates.localDate(picked) { onChosen(date) }
                    }
                }
            }
        }
        .onAppear { picked = ReviewDates.date(chosen ?? today) }
    }
}

/// A calendar date as the device's midnight on that day, and back again.
enum ReviewDates {
    static func date(_ day: Kotlinx_datetimeLocalDate) -> Date {
        isoFormatter.date(from: day.description()) ?? Date()
    }

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

/// A category the household makes for itself, from the picker.
private struct NewCategorySheet: View {
    @ObservedObject var model: ReviewViewModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(
                        L.t(Strings.shared.review_category_new_name),
                        text: Binding(get: { model.newCategoryName ?? "" }, set: { model.setNewCategoryName($0) }),
                        prompt: Text(L.t(Strings.shared.review_category_new_hint))
                    )
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .onSubmit { dismissKeyboard() }
                } header: {
                    Text(L.t(Strings.shared.review_category_new_name))
                }
                Section {
                    ErrorText(messageKey: model.newCategoryErrorKey)
                    GradientButton(
                        title: L.t(Strings.shared.review_category_create),
                        enabled: model.canCreateCategory,
                        busy: model.creatingCategory
                    ) {
                        dismissKeyboard()
                        model.createCategory()
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle(L.t(Strings.shared.review_category_new))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.account_new_cancel)) { model.cancelNewCategory() }
                        .disabled(model.creatingCategory)
                }
            }
        }
        .interactiveDismissDisabled(model.creatingCategory)
    }
}
