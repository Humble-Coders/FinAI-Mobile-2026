import SwiftUI
import SharedLogic

/**
 A diagnostic over Your plan: the savings form, then the server's figures and
 the AI's reasons. The Android `DiagnosticScreen` is the same screen.
 */
struct DiagnosticView: View {
    @StateObject private var model: DiagnosticViewModel
    let onClose: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(kind: String, onClose: @escaping () -> Void) {
        _model = StateObject(wrappedValue: DiagnosticViewModel(kind: kind))
        self.onClose = onClose
    }

    var body: some View {
        let dark = scheme == .dark
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Button(action: onClose) {
                    Image(systemName: "chevron.left").font(.body.weight(.semibold)).frame(width: 44, height: 44)
                }
                .accessibilityLabel(L.t(Strings.shared.action_back))
                Text(model.title).font(.largeTitle.weight(.bold)).accessibilityAddTraits(.isHeader)

                if model.policy != nil {
                    consentStep
                } else if model.diagnostics != nil {
                    form
                    if model.result != nil { result(dark) }
                    if let title = model.recommendation { recommendationCard(title, dark: dark) }
                } else if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                } else {
                    ErrorText(messageKey: model.errorKey)
                }
                Text(L.t(Strings.shared.diagnostic_guidance)).font(.caption).foregroundColor(.secondary)
                if let disclaimer = model.result?.disclaimer {
                    Text(disclaimer).font(.caption2).foregroundColor(.secondary)
                }
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear { model.bind() }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 14) {
            AmountField(
                label: L.t(Strings.shared.diagnostic_savings_target),
                symbol: model.symbol,
                placeholder: "0",
                isError: model.block != nil,
                large: false,
                text: $model.savingsTarget
            )
            AmountField(
                label: L.t(Strings.shared.diagnostic_savings_now),
                symbol: model.symbol,
                placeholder: "0",
                isError: model.block != nil,
                large: false,
                text: $model.savingsNow
            )
            ErrorText(messageKey: model.errorKey ?? model.block?.messageKey)
            GradientButton(
                title: L.t(model.result == nil ? Strings.shared.diagnostic_run : Strings.shared.diagnostic_run_again),
                enabled: model.canRun,
                busy: model.running
            ) {
                Task { await model.run() }
            }
            if let left = model.runsLeftLabel {
                Text(left).font(.caption).foregroundColor(.secondary)
            }
            if model.running {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(L.t(Strings.shared.diagnostic_running))
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func result(_ dark: Bool) -> some View {
        let answer = model.result
        return VStack(alignment: .leading, spacing: 10) {
            if let headline = answer?.headline {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "sparkles").foregroundColor(Mint.greenText(dark)).accessibilityHidden(true)
                    Text(headline).font(.headline)
                }
            }
            if answer?.stored == true {
                Text(L.t(Strings.shared.diagnostic_stored)).font(.caption).foregroundColor(.secondary)
            }
            if !model.checks.isEmpty {
                Text(L.t(Strings.shared.diagnostic_check_title)).font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
                ForEach(Array(model.checks.enumerated()), id: \.offset) { _, check in
                    HStack(spacing: 10) {
                        Image(systemName: check.done ? "checkmark.circle.fill" : "circle.dashed")
                            .foregroundColor(check.done ? Mint.greenText(dark) : .secondary)
                        Text(check.text).font(.subheadline)
                    }
                    .frame(minHeight: 32)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(check.text)
                }
                Divider()
            } else {
                Text(L.t(Strings.shared.diagnostic_where_goes)).font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
            }
            ForEach(Array(model.rows.enumerated()), id: \.offset) { _, row in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.label).foregroundColor(row.isZero ? .secondary : .primary)
                        if let detail = row.detail {
                            Text(detail).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer(minLength: 8)
                    Text(row.amount).font(.body.weight(.semibold)).foregroundColor(row.isZero ? .secondary : .primary)
                }
                .frame(minHeight: 36)
                .accessibilityElement(children: .combine)
            }
            if let comparison = model.comparison { Text(comparison).font(.footnote) }
            if let payoff = model.payoff { Text(payoff).font(.footnote) }

            if let answer, !answer.steps.isEmpty || answer.why != nil {
                Divider()
                Text(L.t(Strings.shared.diagnostic_why)).font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
            }
            ForEach(Array((answer?.steps ?? []).enumerated()), id: \.offset) { _, step in
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.stepLabel(step.to)).font(.caption.weight(.semibold)).foregroundColor(Mint.greenText(dark))
                    Text(step.why).font(.subheadline)
                }
            }
            if let why = answer?.why { Text(why).font(.subheadline) }
            if let whereTo = answer?.where {
                Text(L.t(Strings.shared.diagnostic_where)).font(.caption.weight(.semibold)).foregroundColor(Mint.greenText(dark))
                Text(whereTo).font(.subheadline)
            }
            if let next = answer?.next {
                Text(L.t(Strings.shared.diagnostic_next)).font(.caption.weight(.semibold)).foregroundColor(Mint.greenText(dark))
                Text(next).font(.subheadline)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Color(.secondarySystemBackground)))
    }

    /// The AI's pick between the plan's order and paying debt before goals, with both side by side.
    private func recommendationCard(_ title: String, dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "sparkles").foregroundColor(Mint.greenText(dark)).accessibilityHidden(true)
                Text(title).font(.headline).accessibilityAddTraits(.isHeader)
            }
            if let why = model.result?.recommendWhy { Text(why).font(.subheadline) }
            ForEach(Array(model.orders.enumerated()), id: \.offset) { _, row in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.label)
                        if let detail = row.detail { Text(detail).font(.caption).foregroundColor(.secondary) }
                    }
                    Spacer(minLength: 8)
                    Text(row.amount).font(.subheadline.weight(.semibold))
                }
                .frame(minHeight: 36)
                .accessibilityElement(children: .combine)
            }
            if let waiting = model.waiting { Text(waiting).font(.caption).foregroundColor(.secondary) }
            if model.applied {
                Text(L.t(Strings.shared.diagnostic_applied)).font(.subheadline).foregroundColor(Mint.greenText(dark))
            } else if model.canApplyDebtFirst {
                GradientButton(title: L.t(Strings.shared.diagnostic_apply_debt_first), busy: model.applying) {
                    Task { await model.applyDebtFirst() }
                }
                Text(L.t(Strings.shared.diagnostic_apply_hint)).font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Color(.secondarySystemBackground)))
    }

    private var consentStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L.t(Strings.shared.ai_budget_consent_title)).font(.headline)
            Text(L.t(Strings.shared.ai_budget_consent_body)).font(.subheadline)
            ErrorText(messageKey: model.errorKey)
            if let policy = model.policy {
                Text(policy.body)
                    .font(.footnote)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
            }
            Toggle(isOn: $model.consentTicked) { Text(L.t(Strings.shared.import_consent_agree)) }
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
}
