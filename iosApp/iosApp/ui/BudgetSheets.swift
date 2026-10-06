import SwiftUI
import SharedLogic

/**
 Setting one line by hand (#47).

 In the correction sheet's style, with the same rule behind Save: the
 disabled button, the notice under the field and the view model's refusal all
 read one shared `BudgetEdit.blockingReason`, so they cannot disagree about
 what is wrong.

 A failed save keeps the sheet open with what was typed. Nothing is applied
 by halves — the server answers with the whole month or not at all.
 */
struct BudgetEditorSheet: View {

    @ObservedObject var model: BudgetViewModel
    @FocusState private var amountFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 6) {
                        Text(Money.shared.symbol(currency: model.currency))
                            .foregroundColor(.secondary)
                        TextField(
                            Money.shared.normalize(
                                raw: "0",
                                fractionDigits: Money.shared.fractionDigits(currency: model.currency)
                            ) ?? "0",
                            text: $model.draftAmount
                        )
                        .keyboardType(.decimalPad)
                        .focused($amountFocused)
                        // Locked while the amount is in flight (#47 UI
                        // standards): typing then would leave the field
                        // showing one figure while the server answers about
                        // another.
                        .disabled(model.busy)
                    }
                } header: {
                    Text(L.t(Strings.shared.budget_edit_amount))
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        // The reason Save is off, in the words the shared rule chose.
                        if let notice = model.editNotice {
                            Text(L.t(notice.messageKey, model.currency)).foregroundColor(.red)
                        }
                        // A refusal from the server, which has the last word.
                        if let errorKey = model.editErrorKey {
                            Text(L.t(errorKey)).foregroundColor(.red)
                        }
                    }
                }

                if let suggestion = useSuggestionLabel {
                    Section {
                        Button(suggestion) { model.useSuggestion() }
                            .disabled(model.busy)
                    }
                }
            }
            .navigationTitle(model.editing?.name ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelEdit() }
                        .disabled(model.busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.saving {
                        ProgressView()
                    } else {
                        Button(L.t(Strings.shared.action_save)) { model.save() }
                            .disabled(!model.canSave)
                    }
                }
                // A decimal pad has no return key, so this is the only way
                // off it without tapping elsewhere.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { amountFocused = false }
                }
            }
        }
        .presentationDetents([.medium])
        .onAppear { amountFocused = true }
    }

    /// "Use suggestion ($440)", shown only for a line the person overrode.
    private var useSuggestionLabel: String? {
        guard let line = model.editing, !model.editingIsNew, line.isUserSet else { return nil }
        let amount = Money.shared.format(
            amount: line.suggested,
            currency: model.currency,
            locale: model.locale
        )
        return L.t(Strings.shared.budget_edit_use_suggestion, amount)
    }
}

/**
 Choosing a category to add a line for.

 `income` and `transfers` are not offered, and nor is a category that already
 has a line — both filtered in `BudgetViewModel.pickable`, so the server's
 `not_budgetable` should never be reachable from here.
 */
struct BudgetCategorySheet: View {

    @ObservedObject var model: BudgetViewModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        return NavigationStack {
            Group {
                if model.pickable.isEmpty {
                    VStack {
                        Text(L.t(Strings.shared.budget_picker_empty))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
                } else {
                    List(model.pickable, id: \.id) { category in
                        Button { model.pickCategory(categoryId: category.id) } label: {
                            HStack(spacing: 12) {
                                IconTile(
                                    symbol: CategoryIcons.shared.forSlug(slug: category.slug).systemName,
                                    accent: CategoryIcons.shared.forSlug(slug: category.slug).tint(dark),
                                    size: 36,
                                    iconSize: 16
                                )
                                Text(category.name)
                                    .foregroundColor(.primary)
                                    .lineLimit(1)
                                    .truncationMode(.tail)
                                Spacer(minLength: 0)
                            }
                            .frame(minHeight: 44)
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(L.t(Strings.shared.budget_picker_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelPicking() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
