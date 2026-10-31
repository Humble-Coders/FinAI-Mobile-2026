import SwiftUI
import SharedLogic

/**
 The plan's choices (redesign 2026-10-31): the categories in the order they
 are filled, each with a share. Android's `ChoicesSheet`. Up and down move a
 category; − and + step its share by `YourPlan.PERCENT_STEP`; the field takes
 any percentage. Recalculate keeps them and the server works the split out
 again — so it stays off until the shares add up to 100%.
 */
struct ChoicesSheet: View {

    @ObservedObject var model: PlanViewModel
    @FocusState private var focused: PlanCategory?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        let categories = model.weightsDraft?.categories ?? []
        NavigationStack {
            ScrollViewReader { proxy in
                Form {
                    Section {
                        ForEach(Array(categories.enumerated()), id: \.element) { index, category in
                            row(category, index: index, count: categories.count, dark: dark)
                                .id(category)
                        }
                    } header: {
                        Text(L.t(Strings.shared.plan_choices_hint)).textCase(nil)
                    } footer: {
                        VStack(alignment: .leading, spacing: 6) {
                            if let total = model.totalLine {
                                Text(total).font(.subheadline.weight(.semibold)).foregroundColor(.primary)
                            }
                            if let notice = model.choicesNotice { Text(notice).foregroundColor(.red) }
                            if let errorKey = model.choicesErrorKey { Text(L.t(errorKey)).foregroundColor(.red) }
                        }
                    }

                    Section {
                        GradientButton(
                            title: L.t(Strings.shared.plan_choices_recalculate),
                            enabled: model.canSaveChoices,
                            busy: model.savingChoices
                        ) {
                            focused = nil
                            model.saveChoices()
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                // The field being typed in stays above the keyboard.
                .onChange(of: focused) { _, category in
                    guard let category else { return }
                    withAnimation { proxy.scrollTo(category, anchor: .center) }
                }
            }
            .disabled(model.savingChoices)
            .navigationTitle(L.t(Strings.shared.plan_choices_sheet_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelChoices() }.disabled(model.savingChoices)
                }
                // A decimal pad has no return key; this is the way off it.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { focused = nil }
                }
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(model.savingChoices)
    }

    /// Two lines: the place and name with up and down, then − share +.
    private func row(_ category: PlanCategory, index: Int, count: Int, dark: Bool) -> some View {
        let label = YourPlan.shared.categoryLabel(category: category, locale: model.locale)
        let icon = YourPlan.shared.categoryIcon(category: category)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Text(String(index + 1))
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(Mint.greenText(dark))
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Mint.tile(Mint.greenText(dark), dark)))
                    .accessibilityLabel(L.t(Strings.shared.plan_choices_priority, String(index + 1)))
                IconTile(symbol: icon.systemName, accent: icon.tint(dark), size: 32, iconSize: 15)
                Text(label).font(.body.weight(.medium)).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 4)
                iconButton("chevron.up", label: L.t(Strings.shared.plan_choices_move_up, label), enabled: index > 0) {
                    model.move(category, up: true)
                }
                iconButton("chevron.down", label: L.t(Strings.shared.plan_choices_move_down, label), enabled: index < count - 1) {
                    model.move(category, up: false)
                }
            }
            HStack(spacing: 10) {
                iconButton("minus", label: L.t(Strings.shared.plan_choices_decrease, label), enabled: true) {
                    model.step(category, up: false)
                }
                HStack(spacing: 4) {
                    TextField("0", text: percentBinding(category))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .font(.body.weight(.semibold))
                        .focused($focused, equals: category)
                        .accessibilityLabel(label)
                    Image(systemName: "percent").font(.footnote.weight(.semibold)).foregroundColor(.secondary).accessibilityHidden(true)
                }
                .padding(.horizontal, 10)
                .frame(minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 10).fill(Mint.card(dark)))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Mint.edge(dark), lineWidth: 1))
                iconButton("plus", label: L.t(Strings.shared.plan_choices_increase, label), enabled: true) {
                    model.step(category, up: true)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func iconButton(_ symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.body.weight(.semibold)).tappableArea()
        }
        .buttonStyle(.borderless)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func percentBinding(_ category: PlanCategory) -> Binding<String> {
        Binding(get: { model.percent(of: category) }, set: { model.setPercent($0, of: category) })
    }
}

/**
 One row of the split set by hand (redesign 2026-10-31). Android's
 `RowEditSheet`. The others rebalance so the total stays the same; Save asks
 whether the amount should go where it belongs too — a goal's monthly amount,
 the Budget's Debt line or its savings target.
 */
struct RowEditSheet: View {

    @ObservedObject var model: PlanViewModel
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 6) {
                        Text(model.currencySymbol).foregroundColor(.secondary)
                        TextField(model.amountPlaceholder, text: $model.rowAmount)
                            .keyboardType(.decimalPad)
                            .focused($focused)
                            .accessibilityLabel(L.t(Strings.shared.plan_edit_amount))
                    }
                } header: {
                    Text(L.t(Strings.shared.plan_edit_amount))
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let limit = model.rowLimitLine { Text(limit) }
                        if let rebalance = model.rowRebalanceLine { Text(rebalance) }
                        if let notice = model.rowNotice { Text(notice).foregroundColor(.red) }
                        if let errorKey = model.rowErrorKey { Text(L.t(errorKey)).foregroundColor(.red) }
                    }
                }
            }
            .disabled(model.savingRow)
            .navigationTitle(L.t(Strings.shared.plan_edit_title, model.rowEditBase?.label ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelRowEdit() }.disabled(model.savingRow)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.savingRow {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.plan_edit_save)) {
                            focused = false
                            model.askApply()
                        }
                        .disabled(!model.canSaveRow)
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { focused = false }
                }
            }
            .alert(
                model.applyQuestion?.title ?? "",
                isPresented: $model.askingApply,
                presenting: model.applyQuestion
            ) { question in
                Button(question.confirm) { model.saveRow(apply: true) }
                Button(question.decline) { model.saveRow(apply: false) }
                Button(L.t(Strings.shared.action_cancel), role: .cancel) {}
            } message: { question in
                Text(question.body)
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(model.savingRow)
        .onAppear { focused = true }
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
