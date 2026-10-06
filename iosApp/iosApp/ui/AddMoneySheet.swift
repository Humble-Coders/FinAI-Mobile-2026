import SharedLogic
import SwiftUI

/**
 The quick entry from a money screen, as a sheet: "Add transaction", or on
 Investments "Add investment transaction". The same entry, rules and model as
 the full manual entry screen — only laid out as the design's sheet. Whether it
 may be saved is decided in shared, never here. Mirrors Android's `AddMoneySheet`.
 */
struct AddMoneySheet: View {
    @ObservedObject var entry: ManualEntryViewModel
    /// The Investments variant: the category is savings and is not offered.
    let investment: Bool
    @Binding var markObligation: Bool
    let onClose: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var choosingAccount = false
    @State private var choosingDate = false
    @State private var choosingCategory = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if entry.loading {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 160)
                    } else if entry.loadFailed {
                        ErrorText(messageKey: entry.errorKey ?? Strings.shared.manual_entry_load_failed)
                        GradientButton(title: L.t(Strings.shared.manual_entry_retry)) { entry.load() }
                    } else {
                        form
                    }
                }
                .padding(24)
            }
            .navigationTitle(L.t(investment ? Strings.shared.money_add_investment_title : Strings.shared.money_add_transaction))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { onClose() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(L.t(Strings.shared.action_cancel))
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { dismissKeyboard() }
                }
            }
        }
        .interactiveDismissDisabled(entry.saving)
        .sheet(isPresented: $choosingAccount) {
            AccountListSheet(
                accounts: entry.accounts,
                chosenId: entry.draft.accountId,
                onChosen: { id in
                    entry.chooseAccount(id)
                    choosingAccount = false
                },
                onAdd: {
                    choosingAccount = false
                    entry.openNewAccount()
                },
                onCancel: { choosingAccount = false }
            )
        }
        .sheet(isPresented: newAccountShown) { NewAccountSheet(model: entry) }
        .sheet(isPresented: $choosingDate) {
            DateSheet(chosen: entry.draft.occurredOn, today: entry.today) { date in
                entry.chooseDate(date)
                choosingDate = false
            } onCancel: {
                choosingDate = false
            }
        }
        .sheet(isPresented: $choosingCategory) { categorySheet }
        .alert(
            L.t(Strings.shared.manual_entry_duplicate_title),
            isPresented: Binding(get: { entry.duplicate != nil }, set: { if !$0 { entry.dismissDuplicate() } }),
            actions: {
                Button(L.t(Strings.shared.manual_entry_duplicate_keep)) { entry.keepDuplicate() }
                Button(L.t(Strings.shared.manual_entry_duplicate_cancel), role: .cancel) { entry.dismissDuplicate() }
            },
            message: { Text(entry.duplicateMessage ?? "") }
        )
    }

    private var newAccountShown: Binding<Bool> {
        Binding(get: { entry.newAccount != nil }, set: { if !$0 { entry.cancelNewAccount() } })
    }

    @ViewBuilder
    private var form: some View {
        let notice = entry.notice
        segmented(isError: notice == .noDirection)
        AmountField(
            label: L.t(Strings.shared.manual_entry_amount_label),
            symbol: entry.symbol,
            placeholder: entry.amountPlaceholder,
            isError: [.noAmount, .amountNotMoney, .amountZero].contains { $0 == notice },
            large: false,
            text: Binding(get: { entry.draft.amount }, set: { entry.setAmount($0) })
        )
        PickerField(
            label: L.t(Strings.shared.manual_entry_date_label),
            value: entry.dateLabel,
            placeholder: L.t(Strings.shared.manual_entry_date_placeholder),
            isError: notice == .noDate || notice == .futureDate,
            leading: "calendar",
            trailing: "chevron.right"
        ) {
            entry.refreshToday()
            choosingDate = true
        }
        if !investment {
            PickerField(
                label: L.t(Strings.shared.manual_entry_category_label),
                value: entry.categoryName ?? L.t(Strings.shared.manual_entry_category_none),
                placeholder: L.t(Strings.shared.manual_entry_category_none),
                leading: "tag",
                trailing: "chevron.right"
            ) { choosingCategory = true }
        }
        WizardField(
            label: L.t(investment ? Strings.shared.money_investment_label : Strings.shared.money_merchant_label),
            placeholder: L.t(investment ? Strings.shared.money_investment_hint : Strings.shared.money_merchant_hint),
            autocapitalization: .sentences,
            isError: notice == .noDescription || notice == .descriptionTooLong,
            submitLabel: .done,
            onSubmit: { dismissKeyboard() },
            text: Binding(get: { entry.draft.description_ }, set: { entry.setDescription($0) })
        )
        PickerField(
            label: L.t(Strings.shared.money_payment_method),
            value: entry.account?.name,
            placeholder: L.t(Strings.shared.manual_entry_account_placeholder),
            isError: notice == .noAccount,
            leading: "building.columns",
            trailing: "chevron.right"
        ) { choosingAccount = true }
        // Money out only: income is not something that falls due.
        if !investment && entry.draft.direction == .debit {
            Toggle(isOn: $markObligation) {
                HStack(spacing: 12) {
                    Image(systemName: "calendar.badge.clock").foregroundColor(Brand.textMuted).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L.t(Strings.shared.money_mark_obligation)).font(.subheadline.weight(.semibold))
                        Text(L.t(Strings.shared.money_mark_obligation_detail)).font(.caption).foregroundColor(Brand.textMuted)
                    }
                }
            }
            .tint(Brand.green)
            .frame(minHeight: 56)
        }
        ErrorText(messageKey: entry.errorKey ?? notice?.messageKey)
        GradientButton(
            title: L.t(Strings.shared.money_save_transaction),
            enabled: entry.canSave,
            busy: entry.saving
        ) {
            dismissKeyboard()
            entry.save()
        }
    }

    /// Expense / Income, or Investment / Withdrawal: which way the money went.
    /// Investing is money leaving the account for savings, so it is a debit.
    private func segmented(isError: Bool) -> some View {
        let options: [(TransactionDirection, String, Color)] = investment
            ? [(.debit, Strings.shared.money_investment, Brand.blue), (.credit, Strings.shared.money_withdrawal, Brand.blue)]
            : [(.debit, Strings.shared.money_expense, Brand.red), (.credit, Strings.shared.money_income, Brand.green)]
        let dark = scheme == .dark
        return HStack(spacing: 4) {
            ForEach(options, id: \.1) { option in
                let selected = entry.draft.direction == option.0
                Button { entry.chooseDirection(option.0) } label: {
                    Text(L.t(option.1))
                        .font(.subheadline.weight(selected ? .semibold : .regular))
                        .foregroundColor(selected ? option.2 : .primary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(selected ? option.2.opacity(dark ? 0.28 : 0.16) : .clear)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Mint.card(dark)))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(isError ? Color.red : Mint.edge(dark), lineWidth: 1)
        )
    }

    private var categorySheet: some View {
        NavigationStack {
            List {
                ChoiceRow(
                    title: L.t(Strings.shared.manual_entry_category_none),
                    detail: L.t(Strings.shared.manual_entry_category_auto),
                    selected: entry.draft.categoryId == nil
                ) {
                    entry.chooseCategory(nil)
                    choosingCategory = false
                }
                Section {
                    ForEach(entry.categories, id: \.id) { category in
                        ChoiceRow(title: category.name, detail: nil, selected: category.id == entry.draft.categoryId) {
                            entry.chooseCategory(category.id)
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
