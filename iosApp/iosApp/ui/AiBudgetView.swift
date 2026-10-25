import SwiftUI
import SharedLogic

/**
 The suggested budget. `inSetup` draws it as setup's last step ("Step 4 of 4",
 with Skip); otherwise it is opened from the Budget tab. `onDone` runs once the
 budget is started, or on Skip / Cancel. The Android `AiBudgetScreen` is the
 same screen.
 */
struct AiBudgetView: View {
    @StateObject private var model = AiBudgetViewModel()
    let userId: String
    let inSetup: Bool
    let onDone: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if inSetup {
                    Text(L.t(Strings.shared.ai_budget_step))
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(.secondary)
                }
                Text(L.t(Strings.shared.ai_budget_title))
                    .font(.largeTitle.weight(.bold))
                    .accessibilityAddTraits(.isHeader)

                if model.policy != nil {
                    consentStep
                } else if model.proposal == nil {
                    waiting
                } else {
                    proposalStep
                }

                if inSetup || model.proposal == nil {
                    Button(L.t(inSetup ? Strings.shared.ai_budget_skip : Strings.shared.action_cancel), action: onDone)
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear { model.bind(userId: userId) }
        .onChange(of: model.unavailable) { _, unavailable in
            // Nothing to suggest is not a dead end in setup: the step passes.
            if unavailable && inSetup { onDone() }
        }
    }

    // MARK: - States

    private var waiting: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L.t(Strings.shared.ai_budget_intro))
                .font(.subheadline)
                .foregroundColor(.secondary)
            if model.loading || model.suggesting {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(L.t(Strings.shared.ai_budget_loading))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .accessibilityElement(children: .combine)
            } else {
                ErrorText(messageKey: model.errorKey)
                if !model.unavailable {
                    GradientButton(title: L.t(Strings.shared.ai_budget_retry)) {
                        Task { await model.load() }
                    }
                }
            }
        }
    }

    private var consentStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L.t(Strings.shared.ai_budget_consent_title)).font(.headline)
            Text(L.t(Strings.shared.ai_budget_consent_body)).font(.subheadline)
            ErrorText(messageKey: model.errorKey)
            // The words are the server's, and it records which version was shown.
            if let policy = model.policy {
                Text(policy.body)
                    .font(.footnote)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
            }
            Toggle(isOn: $model.consentTicked) {
                Text(L.t(Strings.shared.import_consent_agree))
            }
            .toggleStyle(CheckboxStyle())
            GradientButton(
                title: L.t(Strings.shared.ai_budget_consent_allow),
                enabled: model.consentTicked,
                busy: model.consentBusy
            ) {
                Task { await model.agree() }
            }
        }
    }

    @ViewBuilder
    private var proposalStep: some View {
        let dark = scheme == .dark
        Text(L.t(Strings.shared.ai_budget_intro))
            .font(.subheadline)
            .foregroundColor(.secondary)
        if let shares = model.shareLabel {
            Text(shares).font(.subheadline.weight(.semibold))
        }
        if let note = model.proposal?.note {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "sparkles").foregroundColor(Mint.greenText(dark)).accessibilityHidden(true)
                Text(note).font(.subheadline)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Mint.greenText(dark).opacity(dark ? 0.2 : 0.12)))
        }

        ForEach(Array(model.draft.lines.enumerated()), id: \.offset) { index, item in
            lineCard(index, item)
        }

        VStack(alignment: .leading, spacing: 6) {
            AmountField(
                label: L.t(Strings.shared.ai_budget_savings),
                symbol: model.symbol,
                placeholder: "0",
                suffix: L.t(Strings.shared.setup_per_month),
                large: false,
                text: Binding(get: { model.draft.savings }, set: { model.setSavings($0) })
            )
            Text(L.t(Strings.shared.ai_budget_savings_hint)).font(.caption).foregroundColor(.secondary)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color(.secondarySystemBackground)))

        if let left = model.leftLabel {
            Text(left)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(model.block == .overIncome ? .red : .primary)
        }
        ErrorText(messageKey: model.errorKey ?? (model.block == .belowCommitment ? nil : model.block?.messageKey))
        Text(L.t(Strings.shared.ai_budget_privacy)).font(.caption).foregroundColor(.secondary)
        GradientButton(title: L.t(Strings.shared.ai_budget_start), enabled: model.canStart, busy: model.starting) {
            Task { await model.start(onDone: onDone) }
        }
        if let again = model.attemptsLabel {
            Button(model.suggesting ? L.t(Strings.shared.ai_budget_loading) : again) {
                Task { await model.suggest() }
            }
            .disabled(!model.canSuggestAgain)
            .frame(maxWidth: .infinity, minHeight: 44)
        }
    }

    private func lineCard(_ index: Int, _ item: AiDraftLine) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(item.line.name).font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                Text(AiBudgetEdit.shared.kindLabel(line: item.line, locale: model.locale))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            if item.line.isDebt {
                // The minimums: the budget's own rule sets this line.
                Text(Money.shared.format(amount: item.line.amount, currency: model.currency, locale: model.locale))
                    .font(.title3.weight(.semibold))
            } else {
                AmountField(
                    label: L.t(Strings.shared.setup_item_amount),
                    symbol: model.symbol,
                    placeholder: "0",
                    suffix: L.t(Strings.shared.setup_per_month),
                    isError: model.lineBlock(index) != nil,
                    large: false,
                    text: Binding(
                        get: { model.draft.lines.indices.contains(index) ? model.draft.lines[index].amount : "" },
                        set: { model.setAmount(index, $0) }
                    )
                )
            }
            if let committed = AiBudgetEdit.shared.committedLabel(line: item.line, currency: model.currency, locale: model.locale) {
                Text(committed).font(.caption).foregroundColor(.secondary)
            }
            if let reason = item.line.reason {
                Text(reason).font(.caption).foregroundColor(.secondary)
            }
            if model.lineBlock(index) == .belowCommitment {
                ErrorText(messageKey: AiBudgetBlock.belowCommitment.messageKey)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color(.secondarySystemBackground)))
    }
}

/// A box nothing pre-ticks, with its label as the tap target.
private struct CheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(spacing: 12) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title3)
                configuration.label
                Spacer(minLength: 0)
            }
            .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(configuration.isOn ? [.isButton, .isSelected] : .isButton)
    }
}
