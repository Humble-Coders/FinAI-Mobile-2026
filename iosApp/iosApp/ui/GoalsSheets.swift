import SwiftUI
import SharedLogic

/**
 Creating or changing a goal (#52), in the correction sheet's style. Android's
 `GoalEditorSheet`.

 The disabled Save, the notice and the model's refusal all read one shared
 `GoalEdit` rule. Every field is locked while the goal is being sent, so what is
 on screen is what the server answers about.
 */
struct GoalEditorSheet: View {

    @ObservedObject var model: GoalsViewModel
    @FocusState private var focused: Input?
    @Environment(\.colorScheme) private var scheme

    /// Which input has the keyboard. Not `Field`: that name is the design tokens.
    private enum Input { case name, target, saved, contribution }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L.t(Strings.shared.goals_field_name), text: $model.name)
                        .textInputAutocapitalization(.words)
                        .submitLabel(.next)
                        .focused($focused, equals: .name)
                        .onSubmit { focused = .target }
                } header: {
                    Text(L.t(Strings.shared.goals_field_name))
                }

                Section {
                    kindPicker
                } header: {
                    Text(L.t(Strings.shared.goals_field_kind))
                }

                Section {
                    Picker(L.t(Strings.shared.goals_field_horizon), selection: horizonBinding) {
                        Text(L.t(Strings.shared.goals_horizon_short)).tag(Optional(GoalHorizon.shortTerm))
                        Text(L.t(Strings.shared.goals_horizon_long)).tag(Optional(GoalHorizon.longTerm))
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(L.t(Strings.shared.goals_field_horizon))
                }

                Section {
                    amountField(L.t(Strings.shared.goals_field_target), text: $model.target, field: .target)
                    amountField(
                        L.t(model.creating ? Strings.shared.goals_field_saved_new : Strings.shared.goals_field_saved),
                        text: $model.saved,
                        field: .saved
                    )
                    amountField(L.t(Strings.shared.goals_field_contribution), text: $model.monthlyContribution, field: .contribution)
                }

                Section {
                    if model.targetDate == nil {
                        Button(L.t(Strings.shared.goals_field_date)) { model.targetDate = isoDate(Date()) }
                    } else if let past = pastDate {
                        // An overdue goal's date is shown, not handed to a
                        // picker that only allows today onward: how SwiftUI
                        // resolves a selection outside its range is not ours
                        // to guess, and if it clamped and wrote back, fixing
                        // a name would quietly move the date to today (#53).
                        LabeledContent(L.t(Strings.shared.goals_field_date), value: past)
                        Button(L.t(Strings.shared.goals_field_date_change)) { model.targetDate = isoDate(Date()) }
                        Button(L.t(Strings.shared.goals_field_date_clear), role: .destructive) { model.targetDate = nil }
                    } else {
                        DatePicker(
                            L.t(Strings.shared.goals_field_date),
                            selection: dateBinding,
                            in: startOfToday()...,
                            displayedComponents: .date
                        )
                        Button(L.t(Strings.shared.goals_field_date_clear), role: .destructive) { model.targetDate = nil }
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        // The reason Save is off, in the words the shared rule chose.
                        if let notice = model.editNotice {
                            Text(GoalEdit.shared.blockText(block: notice, currency: model.currency, locale: model.locale)).foregroundColor(.red)
                        }
                        // A refusal from the server, which has the last word.
                        if let errorKey = model.editErrorKey {
                            Text(L.t(errorKey)).foregroundColor(.red)
                        }
                    }
                }

                if !model.creating {
                    Section {
                        Button(L.t(Strings.shared.goals_delete), role: .destructive) { model.askDelete() }
                    }
                }
            }
            .disabled(model.saving)
            .navigationTitle(L.t(model.creating ? Strings.shared.goals_edit_title_new : Strings.shared.goals_edit_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelEdit() }.disabled(model.saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.saving {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.action_save)) { model.save() }.disabled(!model.canSave)
                    }
                }
                // A decimal pad has no return key; this is the way off it.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { focused = nil }
                }
            }
            .confirmationDialog(
                model.confirmingDelete.map { L.t(Strings.shared.goals_delete_title, $0.name) } ?? "",
                isPresented: deleteShown,
                titleVisibility: .visible
            ) {
                Button(L.t(Strings.shared.goals_delete_confirm), role: .destructive) { model.delete() }
                Button(L.t(Strings.shared.action_cancel), role: .cancel) { model.confirmingDeleteId = nil }
            } message: {
                Text(L.t(Strings.shared.goals_delete_body))
            }
        }
        .presentationDetents([.large])
    }

    private func amountField(_ label: String, text: Binding<String>, field: Input) -> some View {
        HStack(spacing: 6) {
            Text(label).foregroundColor(.secondary)
            Spacer(minLength: 8)
            Text(Money.shared.symbol(currency: model.currency)).foregroundColor(.secondary)
            TextField(
                Money.shared.normalize(raw: "0", fractionDigits: Money.shared.fractionDigits(currency: model.currency)) ?? "0",
                text: text
            )
            .keyboardType(.decimalPad)
            .multilineTextAlignment(.trailing)
            .focused($focused, equals: field)
        }
    }

    /// What the goal is for, as tiles — for its picture only. Tapping the chosen one clears it.
    private var kindPicker: some View {
        let dark = scheme == .dark
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(GoalKind.companion.choosable, id: \.self) { kind in
                    let selected = model.kind == kind
                    let icon = GoalEdit.shared.iconOf(kind: kind)
                    Button { model.kind = selected ? nil : kind } label: {
                        VStack(spacing: 4) {
                            IconTile(symbol: icon.systemName, accent: icon.tint(dark), size: 36, iconSize: 16)
                            Text(GoalEdit.shared.kindLabel(kind: kind, locale: model.locale)).font(.caption2).lineLimit(1)
                        }
                        .padding(6)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? icon.tint(dark) : .clear, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var horizonBinding: Binding<GoalHorizon?> {
        Binding(get: { model.horizon }, set: { model.horizon = $0 })
    }

    private var deleteShown: Binding<Bool> {
        Binding(get: { model.confirmingDeleteId != nil }, set: { if !$0 { model.confirmingDeleteId = nil } })
    }

    /// The target date written out, when it is before today; nil otherwise.
    private var pastDate: String? {
        guard let iso = model.targetDate, let date = parseDate(iso), date < startOfToday() else { return nil }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    private var dateBinding: Binding<Date> {
        Binding(
            get: { model.targetDate.flatMap(parseDate) ?? Date() },
            set: { model.targetDate = isoDate($0) }
        )
    }
}

/**
 Adding to one goal: a single amount field, locked while it is sent. Android's
 `AddToGoalSheet`.
 */
struct AddToGoalSheet: View {

    @ObservedObject var model: GoalsViewModel
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 6) {
                        Text(Money.shared.symbol(currency: model.currency)).foregroundColor(.secondary)
                        TextField(
                            Money.shared.normalize(raw: "0", fractionDigits: Money.shared.fractionDigits(currency: model.currency)) ?? "0",
                            text: $model.addAmount
                        )
                        .keyboardType(.decimalPad)
                        .focused($focused)
                    }
                } header: {
                    Text(L.t(Strings.shared.goals_field_add_amount))
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let notice = model.addNotice {
                            Text(GoalEdit.shared.addBlockText(block: notice, currency: model.currency, locale: model.locale)).foregroundColor(.red)
                        }
                        if let errorKey = model.addErrorKey { Text(L.t(errorKey)).foregroundColor(.red) }
                    }
                }
            }
            .disabled(model.adding)
            .navigationTitle(L.t(Strings.shared.goals_add_title, model.addingTo?.name ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelAdd() }.disabled(model.adding)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.adding {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.goals_add_money)) { model.add() }.disabled(!model.canAdd)
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { focused = false }
                }
            }
        }
        .presentationDetents([.medium])
        .onAppear { focused = true }
    }
}

// MARK: - Dates as the server writes them

/// `YYYY-MM-DD` in the person's calendar — the day they picked, not a moment.
private let isoFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter
}()

private func isoDate(_ date: Date) -> String { isoFormatter.string(from: date) }

private func parseDate(_ iso: String) -> Date? { isoFormatter.date(from: iso) }

private func startOfToday() -> Date { Calendar.current.startOfDay(for: Date()) }
