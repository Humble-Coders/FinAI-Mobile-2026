import SwiftUI
import SharedLogic

/**
 The suggested budget. `inSetup` draws it as setup's last step ("Step 4 of 4",
 with Skip); otherwise it is opened from the Budget tab. `onDone` runs once the
 budget is started, or on Skip / Cancel; `onBack` on the back arrow — in setup,
 the step before (without one, back is `onDone`). Either way the suggestion and
 the edits stay kept, so coming back never asks the AI again. The Android
 `AiBudgetScreen` is the same screen.
 */
struct AiBudgetView: View {
    @StateObject private var model = AiBudgetViewModel()
    let userId: String
    let inSetup: Bool
    let onDone: () -> Void
    var onBack: (() -> Void)? = nil

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 4) {
                    Button { (onBack ?? onDone)() } label: {
                        Image(systemName: "chevron.left")
                            .font(.body.weight(.semibold))
                            .tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.action_back))
                    .foregroundColor(.primary)
                    if inSetup {
                        Text(L.t(Strings.shared.ai_budget_step))
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(.secondary)
                    }
                }
                Text(L.t(Strings.shared.ai_budget_title))
                    .font(.largeTitle.weight(.bold))
                    .accessibilityAddTraits(.isHeader)

                if model.aiOff && inSetup {
                    // In setup there is no Profile yet: the one place the AI
                    // consent is asked for in a screen, once.
                    AiConsentPanel(
                        title: L.t(Strings.shared.ai_budget_consent_title),
                        policy: model.policy,
                        ticked: $model.consentTicked,
                        busy: model.consentBusy,
                        errorKey: model.consentErrorKey,
                        onAgree: { Task { await model.agreeToAi() } },
                        onNotNow: onDone
                    )
                } else if model.aiOff {
                    // Consent is given in Profile only (2026-11-06). From the
                    // Budget tab this is a cover, closed on the way there.
                    AiOffNotice(onRetry: { Task { await model.suggest() } }, beforeOpen: onDone)
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
            // Exactly the screen's width: anything a shade wider let the page
            // be dragged sideways while scrolling.
            .containerRelativeFrame(.horizontal)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .scrollDismissesKeyboard(.interactively)
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear { model.bind(userId: userId) }
        .sheet(isPresented: $model.adding) { addSheet }
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
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color(.secondarySystemBackground)))

        if !model.categories.isEmpty {
            Button { model.adding = true } label: {
                Label(L.t(Strings.shared.ai_budget_add_line), systemImage: "plus")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .disabled(model.starting)
        }

        if let left = model.leftLabel {
            Text(left)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(model.block == .overIncome ? .red : .primary)
        }
        ErrorText(messageKey: model.errorKey ?? (model.block == .belowCommitment ? nil : model.block?.messageKey))
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
                if model.canRemove(index) {
                    Button { model.remove(index) } label: {
                        Image(systemName: "trash")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.ai_budget_remove_line, item.line.name))
                }
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
            if let usual = AiBudgetEdit.shared.usualLabel(line: item.line, currency: model.currency, locale: model.locale) {
                Text(usual).font(.caption.weight(.semibold)).foregroundColor(Mint.greenText(scheme == .dark))
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

    /// Choosing a category to add a line for.
    private var addSheet: some View {
        let dark = scheme == .dark
        return NavigationStack {
            Group {
                if model.addable.isEmpty {
                    Text(L.t(Strings.shared.ai_budget_add_empty))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding()
                } else {
                    List(model.addable, id: \.id) { category in
                        Button { model.add(category) } label: {
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
            .navigationTitle(L.t(Strings.shared.ai_budget_add_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.adding = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
