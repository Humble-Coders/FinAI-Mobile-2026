import SwiftUI
import SharedLogic

/**
 Ticking a commitment off by hand: match a payment already recorded this month
 — nothing new is counted — or type the payment in, which is recorded once, in
 the commitment's category, and named as what paid it. Android's
 `CommitmentTickSheet`.
 */
struct CommitmentTickSheet: View {
    @ObservedObject var model: BudgetViewModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        NavigationStack {
            Form {
                if let commitment = model.ticking, let budget = model.budget {
                    Section {
                        Text(CommitmentTick.shared.subtitle(commitment: commitment, budget: budget, locale: model.locale))
                            .font(.caption)
                            .foregroundColor(.secondary)
                        if commitment.paidByHand {
                            Button(L.t(Strings.shared.tick_untick), role: .destructive) { model.untick() }
                                .disabled(model.tickBusy)
                        }
                    }
                    Section {
                        if model.tickRows == nil {
                            ProgressView()
                        } else if model.tickCandidates.isEmpty {
                            Text(L.t(Strings.shared.tick_match_empty)).font(.caption).foregroundColor(.secondary)
                        } else {
                            ForEach(model.tickCandidates, id: \.transactionId) { candidate in
                                candidateRow(candidate, chosen: commitment.transactionId == candidate.transactionId, dark: dark)
                            }
                        }
                    } header: {
                        Text(L.t(Strings.shared.tick_match_title))
                    }
                    Section {
                        HStack(spacing: 6) {
                            Text(Money.shared.symbol(currency: budget.currency)).foregroundColor(.secondary)
                            TextField(L.t(Strings.shared.tick_amount), text: $model.payAmount)
                                .keyboardType(.decimalPad)
                        }
                        DatePicker(L.t(Strings.shared.tick_date), selection: $model.payDate, in: model.payDateRange, displayedComponents: .date)
                        if !model.accounts.isEmpty {
                            Picker(L.t(Strings.shared.tick_account), selection: $model.payAccountId) {
                                Text("—").tag(String?.none)
                                ForEach(model.accounts, id: \.id) { account in
                                    Text(account.name).tag(String?.some(account.id))
                                }
                            }
                        }
                        if let block = model.payBlock, !(block == .amount && model.payAmount.isEmpty) {
                            Text(L.t(block.messageKey)).font(.caption).foregroundColor(.secondary)
                        }
                        if let duplicate = model.tickDuplicate {
                            Text(L.t(Strings.shared.tick_duplicate, duplicate.description_ ?? "", Money.shared.format(amount: duplicate.amount, currency: budget.currency, locale: model.locale)))
                                .font(.caption)
                                .foregroundColor(.red)
                            Button(L.t(Strings.shared.tick_use_existing)) { model.useDuplicate() }
                                .disabled(model.tickBusy)
                        } else if let key = model.tickErrorKey {
                            Text(L.t(key)).font(.caption).foregroundColor(.red)
                        }
                        Button {
                            dismissKeyboard()
                            model.addPayment()
                        } label: {
                            HStack {
                                Text(L.t(Strings.shared.tick_add)).fontWeight(.medium)
                                Spacer()
                                if model.tickBusy { ProgressView() }
                            }
                        }
                        .disabled(!model.canPay)
                    } header: {
                        Text(L.t(Strings.shared.tick_add_title))
                    } footer: {
                        Text(L.t(Strings.shared.tick_add_hint))
                    }
                }
            }
            .navigationTitle(model.ticking.map { CommitmentTick.shared.title(commitment: $0, locale: model.locale) } ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.closeTick() }
                        .disabled(model.tickBusy)
                }
            }
        }
        .presentationDetents([.large])
    }

    /// A payment out this month, with its amount — "Likely" when it is close to what is expected.
    private func candidateRow(_ candidate: TickCandidate, chosen: Bool, dark: Bool) -> some View {
        Button { model.tickWith(candidate.transactionId) } label: {
            HStack(spacing: 10) {
                Image(systemName: chosen ? "checkmark.circle.fill" : "doc.text")
                    .foregroundColor(Mint.greenText(dark))
                VStack(alignment: .leading, spacing: 2) {
                    Text(candidate.title).font(.subheadline).foregroundColor(.primary).lineLimit(1)
                    Text(candidate.detail).font(.caption2).foregroundColor(.secondary)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(candidate.amount).font(.subheadline.weight(.medium)).foregroundColor(.primary)
                    if candidate.likely {
                        Text(L.t(Strings.shared.tick_likely))
                            .font(.caption2)
                            .foregroundColor(Mint.greenText(dark))
                            .padding(.horizontal, 6)
                            .background(Capsule().fill(Mint.tile(BoardPalette.green, dark)))
                    }
                }
            }
            .frame(minHeight: 44)
        }
        .disabled(model.tickBusy)
    }
}
