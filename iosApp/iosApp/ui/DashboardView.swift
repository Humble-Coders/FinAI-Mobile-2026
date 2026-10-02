import SharedLogic
import SwiftUI

/**
 The dashboard (PRD F3): one month of what happened, against what was expected
 of it. This is home — the first screen a signed-in, set-up person meets.

 Every figure arrives already decided, by the server and then by the view
 model. Nothing here computes money, which is what keeps iOS and Android from
 disagreeing about a number somebody is acting on.
 */
struct DashboardView: View {
    @ObservedObject var model: DashboardViewModel
    let userId: String
    /// Importing a statement (#31) — the main way in.
    let onImportStatement: () -> Void
    /// Typing a transaction in (#30) — the other.
    let onAddTransaction: () -> Void
    /// The review queue (#32).
    let onReview: () -> Void
    let onSignOut: () -> Void

    var body: some View {
        ScreenScaffold(alignment: .leading) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L.t(Strings.shared.dashboard_greeting))
                    .font(.title2.weight(.semibold))
                Text(L.t(Strings.shared.dashboard_subtitle))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            monthStrip.padding(.top, 16)

            if model.loadFailed {
                loadFailed
            } else if model.showsEmptyState {
                firstSteps
            } else {
                figures
            }

            GradientButton(title: L.t(Strings.shared.import_entry), action: onImportStatement)
                .padding(.top, 20)
            Button(action: onAddTransaction) {
                Text(L.t(Strings.shared.manual_entry_title)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            Button(action: onReview) {
                Text(L.t(Strings.shared.review_entry)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            // Not green: the accent belongs to the one primary action above.
            Button(L.t(Strings.shared.action_sign_out), action: onSignOut)
                .foregroundColor(.primary)
                .tappableArea()
                .frame(maxWidth: .infinity, alignment: .center)
        }
        .onAppear { model.bind(userId: userId) }
        .onDisappear { model.unbind() }
    }

    // MARK: - The month

    private var monthStrip: some View {
        HStack {
            // Text glyphs rather than SF Symbols, to match Android exactly.
            // The label each carries is what VoiceOver announces, so the glyph
            // itself is hidden from it.
            Button(action: model.showPreviousMonth) {
                Text("\u{2039}").font(.title3).accessibilityHidden(true)
            }
            .disabled(!model.canGoBack)
            .foregroundColor(.primary)
            .frame(width: 56, height: 48)
            .accessibilityLabel(L.t(Strings.shared.dashboard_previous_month))

            Spacer()
            Text(model.monthLabel).font(.headline)
            Spacer()

            // Hidden rather than disabled on the month that is running: a
            // month which has not happened holds nothing to look at.
            if model.canGoForward {
                Button(action: model.showNextMonth) {
                    Text("\u{203A}").font(.title3).accessibilityHidden(true)
                }
                .foregroundColor(.primary)
                .frame(width: 56, height: 48)
                .accessibilityLabel(L.t(Strings.shared.dashboard_next_month))
            } else {
                Color.clear.frame(width: 56, height: 1)
            }
        }
    }

    // MARK: - The figures

    @ViewBuilder
    private var figures: some View {
        netCard

        HStack(alignment: .top, spacing: 12) {
            FigureCard(
                label: L.t(Strings.shared.dashboard_income),
                amount: model.incomeAmount,
                detail: model.incomeExpectation
            )
            FigureCard(
                label: L.t(Strings.shared.dashboard_expenses),
                amount: model.expensesAmount,
                detail: model.expensesExpectation,
                // Only expenses: earning more than expected is good news.
                detailIsWarning: model.expensesAreOver
            )
        }

        HStack(alignment: .top, spacing: 12) {
            FigureCard(
                label: L.t(Strings.shared.dashboard_investments),
                amount: model.investmentsAmount,
                detail: model.investmentsMovement
            )
            FigureCard(
                label: L.t(Strings.shared.dashboard_debts),
                amount: model.debtsAmount,
                detail: model.debtsMovement
            )
        }

        if let pending = model.pendingReviewLabel {
            Text(pending)
                .font(.footnote)
                .foregroundColor(Brand.amber)
                .frame(maxWidth: .infinity, alignment: .leading)
        }

        if model.showsTrend { trend.padding(.top, 16) }
        if !model.commitmentRows.isEmpty { commitments.padding(.top, 16) }
    }

    private var netCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L.t(Strings.shared.dashboard_net_label))
                .font(.subheadline.weight(.medium))
                .foregroundColor(Brand.onGreen)
            Text(model.net)
                .font(.largeTitle.weight(.bold))
                .foregroundColor(Brand.onGreen)
            // Absent, not "+0%", when there is no month to compare against.
            if let change = model.changeLabel {
                Text(change).font(.footnote).foregroundColor(Brand.onGreen)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.green)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var trend: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.t(Strings.shared.dashboard_trend_title)).font(.subheadline.weight(.semibold))
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(model.bars) { bar in
                    VStack(spacing: 4) {
                        Spacer(minLength: 0)
                        // A month with no rows is an outline. A zero-height
                        // bar would state a fact nobody observed.
                        Group {
                            if let fraction = bar.fraction {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(bar.isNegative ? Color.red : Brand.green)
                                    .frame(height: max(Self.barHeight * fraction, 2))
                            } else {
                                RoundedRectangle(cornerRadius: 4)
                                    .stroke(Brand.border, lineWidth: 1)
                                    .frame(height: 6)
                            }
                        }
                        Text(bar.shortLabel)
                            .font(.caption2)
                            .foregroundColor(Brand.textMuted)
                            .accessibilityHidden(true)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(bar.description)
                }
            }
            .frame(height: Self.barHeight + 20)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var commitments: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t(Strings.shared.dashboard_commitments_title)).font(.subheadline.weight(.semibold))
            if let summary = model.commitmentsSummary {
                Text(summary).font(.footnote).foregroundColor(Brand.textMuted)
            }
            ForEach(model.commitmentRows) { row in
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.name).font(.body)
                        // Not red when unseen: we do not know it is unpaid,
                        // only that we did not find it.
                        Text(row.detail).font(.footnote).foregroundColor(Brand.textMuted)
                    }
                    Spacer()
                    Text(row.expected).font(.body).foregroundColor(Brand.textMuted)
                    // Decorative: `detail` already says this in words, so
                    // VoiceOver is not told twice.
                    Text(row.wasSeen ? "✓" : "–")
                        .foregroundColor(row.wasSeen ? Brand.green : Brand.textMuted)
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 48)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var firstSteps: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L.t(Strings.shared.dashboard_empty_title)).font(.headline)
            Text(L.t(Strings.shared.dashboard_empty_body))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var loadFailed: some View {
        VStack(spacing: 8) {
            ErrorText(messageKey: model.errorKey)
            Button(action: { model.load() }) {
                Text(L.t(Strings.shared.dashboard_retry)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
        }
    }

    private static let barHeight: Double = 96
}

/// One of the four cards: a label, a figure, and what it is measured against.
private struct FigureCard: View {
    let label: String
    let amount: String
    let detail: String?
    var detailIsWarning = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundColor(Brand.textMuted)
            Text(amount).font(.title3.weight(.semibold))
            if let detail {
                Text(detail)
                    .font(.footnote)
                    .foregroundColor(detailIsWarning ? Brand.amber : Brand.textMuted)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
