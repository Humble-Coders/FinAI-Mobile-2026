import SwiftUI
import SharedLogic

/**
 The plan's four choices (#76), each opening on what the plan runs on now,
 with its one line of why. Android's `ChoicesSheet`. Saving with a buffer
 keeps it as an Emergency fund goal, and the sheet says so first.
 */
struct ChoicesSheet: View {

    @ObservedObject var model: PlanViewModel
    @FocusState private var focused: Input?

    private enum Input { case buffer, goalShare, rateLine }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle(L.t(Strings.shared.plan_field_keep_buffer), isOn: $model.hasBuffer)
                    if model.hasBuffer {
                        HStack(spacing: 6) {
                            Text(L.t(Strings.shared.plan_field_buffer)).foregroundColor(.secondary)
                            Spacer(minLength: 8)
                            Text(model.currencySymbol).foregroundColor(.secondary)
                            TextField(model.amountPlaceholder, text: $model.buffer)
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .focused($focused, equals: .buffer)
                        }
                    }
                } header: {
                    Text(L.t(Strings.shared.plan_choice_buffer))
                } footer: {
                    Text(L.t(Strings.shared.plan_choice_buffer_why))
                }

                Section {
                    percentField(L.t(Strings.shared.plan_field_goal_share), text: $model.goalShare, field: .goalShare)
                } header: {
                    Text(L.t(Strings.shared.plan_choice_goal_share))
                } footer: {
                    Text(L.t(Strings.shared.plan_choice_goal_share_why))
                }

                Section {
                    percentField(L.t(Strings.shared.plan_field_rate_line), text: $model.rateLine, field: .rateLine)
                } header: {
                    Text(L.t(Strings.shared.plan_choice_rate_line))
                } footer: {
                    Text(L.t(Strings.shared.plan_choice_rate_line_why))
                }

                Section {
                    Picker(L.t(Strings.shared.plan_choice_strategy), selection: $model.strategy) {
                        ForEach([PlanStrategy.avalanche, PlanStrategy.snowball], id: \.self) { strategy in
                            Text(YourPlan.shared.strategyLabel(strategy: strategy, locale: model.locale)).tag(strategy)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(L.t(Strings.shared.plan_choice_strategy))
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L.t(Strings.shared.plan_choice_strategy_why))
                        if let note = model.goalNote { Text(note).foregroundColor(.primary) }
                        if let notice = model.choicesNotice {
                            Text(YourPlan.shared.blockText(block: notice, currency: model.moneyCurrency, locale: model.locale)).foregroundColor(.red)
                        }
                        if let errorKey = model.choicesErrorKey { Text(L.t(errorKey)).foregroundColor(.red) }
                    }
                }
            }
            .disabled(model.savingChoices)
            .navigationTitle(L.t(Strings.shared.plan_choices_sheet_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelChoices() }.disabled(model.savingChoices)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.savingChoices {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.action_save)) { model.saveChoices() }.disabled(!model.canSaveChoices)
                    }
                }
                // A decimal pad has no return key; this is the way off it.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { focused = nil }
                }
            }
        }
        .presentationDetents([.large])
    }

    private func percentField(_ label: String, text: Binding<String>, field: Input) -> some View {
        HStack(spacing: 6) {
            Text(label).foregroundColor(.secondary)
            Spacer(minLength: 8)
            TextField("0", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .focused($focused, equals: field)
        }
    }
}

/**
 A debt's details: name, type, minimum, rate, a promotion and the account its
 statements go into (#76). Android's `DebtEditorSheet`. The balance is not
 here — "Update balance" is, because the server dates that one.
 */
struct DebtEditorSheet: View {

    @ObservedObject var model: PlanViewModel
    @FocusState private var focused: Input?

    private enum Input { case name, minimum, rate, promoRate }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L.t(Strings.shared.debts_field_name), text: $model.debtName)
                        .textInputAutocapitalization(.words)
                        .focused($focused, equals: .name)
                    Picker(L.t(Strings.shared.debts_field_kind), selection: kindBinding) {
                        ForEach(DebtKind.companion.choosable, id: \.self) { kind in
                            Text(DebtEdit.shared.kindLabel(kind: kind, locale: model.locale)).tag(kind)
                        }
                    }
                }

                Section {
                    HStack(spacing: 6) {
                        Text(L.t(Strings.shared.debts_field_minimum)).foregroundColor(.secondary)
                        Spacer(minLength: 8)
                        Text(model.currencySymbol).foregroundColor(.secondary)
                        TextField(model.amountPlaceholder, text: $model.minimum)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .focused($focused, equals: .minimum)
                    }
                    percentField(L.t(Strings.shared.debts_field_rate), text: $model.rate, field: .rate)
                }

                Section {
                    percentField(L.t(Strings.shared.debts_field_promo_rate), text: $model.promoRate, field: .promoRate)
                    if model.promoEndsOn == nil {
                        Button(L.t(Strings.shared.debts_field_promo_ends)) { model.promoEndsOn = isoDate(Date()) }
                    } else {
                        DatePicker(L.t(Strings.shared.debts_field_promo_ends), selection: promoDateBinding, displayedComponents: .date)
                    }
                    if !model.promoRate.isEmpty || model.promoEndsOn != nil {
                        Button(L.t(Strings.shared.debts_field_promo_clear), role: .destructive) {
                            model.promoRate = ""
                            model.promoEndsOn = nil
                        }
                    }
                }

                Section {
                    Picker(L.t(Strings.shared.debts_field_account), selection: $model.accountId) {
                        Text(L.t(Strings.shared.debts_field_account_none)).tag(String?.none)
                        ForEach(model.linkableAccounts, id: \.id) { account in
                            Text(account.name).tag(Optional(account.id))
                        }
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L.t(Strings.shared.debts_field_account_hint))
                        if let notice = model.debtNotice {
                            Text(DebtEdit.shared.blockText(block: notice, currency: model.moneyCurrency, locale: model.locale)).foregroundColor(.red)
                        }
                        if let errorKey = model.debtErrorKey { Text(L.t(errorKey)).foregroundColor(.red) }
                    }
                }
            }
            .disabled(model.savingDebt)
            .navigationTitle(L.t(Strings.shared.debts_edit_title, model.editingDebt?.name ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelDebt() }.disabled(model.savingDebt)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.savingDebt {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.action_save)) { model.saveDebt() }.disabled(!model.canSaveDebt)
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { focused = nil }
                }
            }
        }
        .presentationDetents([.large])
    }

    private func percentField(_ label: String, text: Binding<String>, field: Input) -> some View {
        HStack(spacing: 6) {
            Text(label).foregroundColor(.secondary)
            Spacer(minLength: 8)
            TextField("0", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .focused($focused, equals: field)
        }
    }

    private var kindBinding: Binding<DebtKind> {
        Binding(get: { model.debtKind ?? .other }, set: { model.debtKind = $0 })
    }

    private var promoDateBinding: Binding<Date> {
        Binding(get: { model.promoEndsOn.flatMap(parseDate) ?? Date() }, set: { model.promoEndsOn = isoDate($0) })
    }
}

/**
 One amount: what is owed today (#76). Android's `BalanceSheet`. The server
 dates it and marks it typed by hand.
 */
struct BalanceSheet: View {

    @ObservedObject var model: PlanViewModel
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 6) {
                        Text(model.currencySymbol).foregroundColor(.secondary)
                        TextField(model.amountPlaceholder, text: $model.balanceAmount)
                            .keyboardType(.decimalPad)
                            .focused($focused)
                    }
                } header: {
                    Text(L.t(Strings.shared.debts_field_balance))
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let debt = model.balanceDebt { Text(DebtEdit.shared.sourceLine(debt: debt, locale: model.locale)) }
                        if let notice = model.balanceNotice {
                            Text(DebtEdit.shared.balanceBlockText(block: notice, currency: model.moneyCurrency, locale: model.locale)).foregroundColor(.red)
                        }
                        if let errorKey = model.balanceErrorKey { Text(L.t(errorKey)).foregroundColor(.red) }
                    }
                }
            }
            .disabled(model.savingBalance)
            .navigationTitle(L.t(Strings.shared.debts_balance_title, model.balanceDebt?.name ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelBalance() }.disabled(model.savingBalance)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.savingBalance {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.action_save)) { model.saveBalance() }.disabled(!model.canSaveBalance)
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
