import SharedLogic
import SwiftUI

/**
 The dashboard (PRD F3): one month of what happened, against what was expected
 of it. This is home — the first screen a signed-in, set-up person meets.

 Every figure arrives already decided, by the server and then by the view
 model. Nothing here computes money, which is what keeps the two apps from
 disagreeing about a number somebody is acting on.

 The icons are SF Symbols, with Material's equivalents on Android. The two
 differ slightly in shape, which nobody sees side by side, and in exchange
 each app gets icons drawn for its own platform that scale with the reader's
 text size.
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
        ZStack(alignment: .top) {
            hills
            ScreenScaffold(alignment: .leading) {
                header
                heroCard.padding(.top, 18)

                if model.loadFailed {
                    loadFailed.padding(.top, 14)
                } else if !model.showsEmptyState {
                    figures.padding(.top, 14)
                }

                actionPanel.padding(.top, 16)

                Button(L.t(Strings.shared.action_sign_out), action: onSignOut)
                    .foregroundColor(.primary)
                    .tappableArea()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, 10)
            }
        }
        .onAppear { model.bind(userId: userId) }
        .onDisappear { model.unbind() }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 12) {
            // A silhouette, not an initial. We deliberately do not hold a
            // name: the requirements forbid collecting one, and a letter would
            // need it.
            ZStack {
                Circle().fill(Brand.green.opacity(0.15))
                FinAiIcon(symbol: "person.fill", tint: Brand.green, size: 24)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 1) {
                Text(L.t(Strings.shared.dashboard_greeting)).font(.title3.weight(.bold))
                Text(L.t(Strings.shared.dashboard_subtitle))
                    .font(.caption)
                    .foregroundColor(Brand.textMuted)
            }
            Spacer()

            // The bell is the review queue, and its dot means something: rows
            // are waiting. A badge that never changes teaches people to ignore
            // it.
            Button(action: onReview) {
                ZStack(alignment: .topTrailing) {
                    FinAiIcon(symbol: "bell.fill", tint: .primary, size: 22)
                        .frame(width: 44, height: 44)
                    if model.hasPending {
                        Circle().fill(Color.red).frame(width: 8, height: 8).offset(x: -10, y: 10)
                    }
                }
            }
            .accessibilityLabel(model.notificationsLabel)
        }
        .frame(maxWidth: .infinity)
    }

    /// The soft hills behind the header, as in the design. Drawn rather than
    /// shipped as an asset so it tints with the theme and costs no image.
    private var hills: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let height: CGFloat = 240
            ZStack {
                ridge(width: width, height: height, startY: 0.62, peakY: 0.40, alpha: 0.06)
                ridge(width: width, height: height, startY: 0.74, peakY: 0.56, alpha: 0.05)
            }
        }
        .frame(height: 240)
        .accessibilityHidden(true)
    }

    private func ridge(
        width: CGFloat, height: CGFloat, startY: CGFloat, peakY: CGFloat, alpha: Double
    ) -> some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: height))
            path.addLine(to: CGPoint(x: 0, y: height * startY))
            path.addCurve(
                to: CGPoint(x: width, y: height * peakY * 0.92),
                control1: CGPoint(x: width * 0.25, y: height * peakY),
                control2: CGPoint(x: width * 0.55, y: height * startY * 1.08)
            )
            path.addLine(to: CGPoint(x: width, y: height))
            path.closeSubpath()
        }
        .fill(Brand.green.opacity(alpha))
    }

    // MARK: - Hero

    private var heroCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Text(L.t(Strings.shared.dashboard_net_label))
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(Brand.onGreen)
                Button(action: model.toggleAmounts) {
                    FinAiIcon(
                        symbol: model.amountsHidden ? "eye.slash.fill" : "eye.fill",
                        tint: Brand.onGreen,
                        size: 18
                    )
                    .frame(width: 28, height: 28)
                }
                .accessibilityLabel(model.hideToggleLabel)
                Spacer()
                monthPill
            }

            Text(model.net)
                .font(.system(size: 34, weight: .bold))
                .foregroundColor(Brand.onGreen)
                .padding(.top, 10)

            HStack(alignment: .bottom) {
                // Absent, not "+0%", when there is no month to compare against.
                if let change = model.changeLabel {
                    changePill(change, rose: model.netIsPositive)
                }
                Spacer()
                if model.showsTrend { heroBars }
            }
            .padding(.top, 12)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Brand.green, Brand.greenDeep],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private var monthPill: some View {
        HStack(spacing: 0) {
            step("chevron.left", L.t(Strings.shared.dashboard_previous_month), model.canGoBack) {
                model.showPreviousMonth()
            }
            Text(model.monthLabel).font(.subheadline.weight(.medium)).foregroundColor(Brand.onGreen)
            // Hidden rather than disabled on the month that is running.
            if model.canGoForward {
                step("chevron.right", L.t(Strings.shared.dashboard_next_month), true) {
                    model.showNextMonth()
                }
            } else {
                Color.clear.frame(width: 32, height: 1)
            }
        }
        .padding(.horizontal, 4)
        .background(Brand.onGreen.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func step(
        _ symbol: String, _ label: String, _ enabled: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            FinAiIcon(symbol: symbol, tint: Brand.onGreen, size: 14).frame(width: 32, height: 32)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func changePill(_ label: String, rose: Bool) -> some View {
        HStack(spacing: 6) {
            // The same arrow either way, turned over for a fall.
            FinAiIcon(symbol: "arrow.up", tint: Brand.onGreen, size: 12)
                .rotationEffect(.degrees(rose ? 0 : 180))
            Text(label).font(.caption2).foregroundColor(Brand.onGreen)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Brand.onGreen.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var heroBars: some View {
        HStack(alignment: .bottom, spacing: 4) {
            ForEach(model.bars) { bar in
                Group {
                    if let fraction = bar.fraction {
                        RoundedRectangle(cornerRadius: 3)
                            .fill(Brand.onGreen.opacity(bar.isNegative ? 0.45 : 0.85))
                            .frame(height: max(44 * fraction, 3))
                    } else {
                        // A month with nothing recorded is an outline, not a
                        // short bar: a bar would be a figure nobody has.
                        RoundedRectangle(cornerRadius: 3)
                            .stroke(Brand.onGreen.opacity(0.35), lineWidth: 1)
                            .frame(height: 4)
                    }
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(bar.description)
            }
        }
        .frame(width: 132, height: 44, alignment: .bottom)
    }

    // MARK: - The four cards

    @ViewBuilder
    private var figures: some View {
        VStack(spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                FigureCard(
                    symbol: "banknote.fill", accent: Brand.green,
                    label: L.t(Strings.shared.dashboard_income),
                    amount: model.incomeAmount, detail: model.incomeExpectation
                )
                FigureCard(
                    symbol: "creditcard.fill", accent: Brand.red,
                    label: L.t(Strings.shared.dashboard_expenses),
                    amount: model.expensesAmount, detail: model.expensesExpectation,
                    detailIsWarning: model.expensesAreOver
                )
            }
            HStack(alignment: .top, spacing: 12) {
                FigureCard(
                    symbol: "chart.line.uptrend.xyaxis", accent: Brand.purple,
                    label: L.t(Strings.shared.dashboard_investments),
                    amount: model.investmentsAmount, detail: model.investmentsMovement
                )
                FigureCard(
                    symbol: "doc.text.fill", accent: Brand.amber,
                    label: L.t(Strings.shared.dashboard_debts),
                    amount: model.debtsAmount, detail: model.debtsMovement
                )
            }
            if !model.commitmentRows.isEmpty { commitments.padding(.top, 8) }
        }
    }

    private var commitments: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t(Strings.shared.dashboard_commitments_title)).font(.subheadline.weight(.semibold))
            if let summary = model.commitmentsSummary {
                Text(summary).font(.footnote).foregroundColor(Brand.textMuted)
            }
            ForEach(model.commitmentRows) { row in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.name).font(.body)
                        // Not red when unseen: we do not know it is unpaid,
                        // only that we did not find it.
                        Text(row.detail).font(.footnote).foregroundColor(Brand.textMuted)
                    }
                    Spacer()
                    Text(row.expected).font(.body).foregroundColor(Brand.textMuted)
                    ZStack {
                        Circle().fill(row.wasSeen ? Brand.green.opacity(0.18) : Color.gray.opacity(0.12))
                        if row.wasSeen {
                            FinAiIcon(symbol: "checkmark", tint: Brand.green, size: 12)
                        }
                    }
                    .frame(width: 22, height: 22)
                    // Decorative: `detail` already says this in words.
                    .accessibilityHidden(true)
                }
                .frame(minHeight: 48)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Actions

    private var actionPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(Brand.green.opacity(0.18))
                    FinAiIcon(symbol: "bolt.fill", tint: Brand.green, size: 20)
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.t(model.showsEmptyState
                        ? Strings.shared.dashboard_empty_title
                        : Strings.shared.dashboard_quick_actions_title))
                        .font(.subheadline.weight(.bold))
                    if model.showsEmptyState {
                        Text(L.t(Strings.shared.dashboard_empty_body))
                            .font(.footnote)
                            .foregroundColor(Brand.textMuted)
                    }
                }
                Spacer()
            }

            VStack(spacing: 8) {
                ActionRow(
                    symbol: "square.and.arrow.down",
                    label: L.t(Strings.shared.import_entry),
                    primary: true, action: onImportStatement
                )
                ActionRow(
                    symbol: "plus",
                    label: L.t(Strings.shared.manual_entry_title),
                    action: onAddTransaction
                )
                ActionRow(
                    symbol: "list.bullet",
                    label: L.t(Strings.shared.review_entry),
                    action: onReview
                )
            }
            .padding(.top, 14)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.surface.opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
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
}

/// One of the four cards: a tinted icon tile, a label, a figure, and what it
/// is measured against.
private struct FigureCard: View {
    let symbol: String
    let accent: Color
    let label: String
    let amount: String
    var detail: String?
    var detailIsWarning = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous).fill(accent.opacity(0.18))
                    FinAiIcon(symbol: symbol, tint: accent, size: 20)
                }
                .frame(width: 36, height: 36)
                Text(label).font(.caption.weight(.semibold)).foregroundColor(accent)
                Spacer()
                FinAiIcon(symbol: "chevron.right", tint: accent.opacity(0.5), size: 14)
            }
            Text(amount).font(.title3.weight(.bold)).padding(.top, 10)
            if let detail {
                Text(detail)
                    .font(.caption2)
                    .foregroundColor(detailIsWarning ? Brand.amber : Brand.textMuted)
                    .padding(.top, 4)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(accent.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct ActionRow: View {
    let symbol: String
    let label: String
    var primary = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                FinAiIcon(symbol: symbol, tint: content, size: 20)
                Text(label).font(.subheadline.weight(.semibold)).foregroundColor(content)
                Spacer()
                FinAiIcon(symbol: "chevron.right", tint: content, size: 16)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private var content: Color { primary ? Brand.onGreen : .primary }

    @ViewBuilder
    private var background: some View {
        if primary {
            LinearGradient(
                colors: [Brand.green, Brand.greenDeep],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        } else {
            Brand.surfaceField
        }
    }
}
