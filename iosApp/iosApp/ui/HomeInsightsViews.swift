import SwiftUI
import SharedLogic

/*
 Home's M4 sections (#46): the freshness line and the score card on the green
 field; the budget and "Where it went" on the sheet; and the score's
 breakdown. Built from Home's own pieces — the field's glass and ink, the
 sheet's white cards, the category tiles — with no new colours. Mirrors
 Android's `HomeInsightsViews.kt`.

 Everything shown arrives worded from `HomeInsights` in shared code; these
 only lay it out.
 */

// MARK: - On the field

/**
 "As of Oct 2, 2026 · last import 3 days ago", under the hero figure.

 Stale data reads as a nudge rather than a report, on a glass pill that opens
 the import — the one thing that would make it current.
 */
struct FreshnessText: View {
    let line: FreshnessLine
    let onImport: () -> Void

    var body: some View {
        if line.isStale {
            Button(action: onImport) {
                HStack(spacing: 8) {
                    FinAiIcon(symbol: "square.and.arrow.down", tint: Field.ink(), size: 14)
                    Text(line.text)
                        .font(.footnote.weight(.medium))
                        .foregroundColor(Field.ink())
                        .multilineTextAlignment(.leading)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Capsule().fill(Field.glass))
                .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
        } else {
            HStack(spacing: 6) {
                FinAiIcon(symbol: "clock", tint: Field.ink(0.75), size: 12)
                    .accessibilityHidden(true)
                Text(line.text)
                    .font(.footnote)
                    .foregroundColor(Field.ink(0.8))
                    .lineLimit(2)
            }
        }
    }
}

/**
 The score on the field: a ring out of 100, the change since last month, and
 the way into its breakdown.

 The ring is the system's own `Gauge` — the platform's ring, not a drawing of
 one — and does not animate, so reduce-motion has nothing to stop.
 */
struct ScoreFieldCard: View {
    let card: ScoreCard
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 14) {
                Gauge(value: card.fraction) {
                    EmptyView()
                } currentValueLabel: {
                    Text(card.score)
                        .font(.title3.weight(.bold))
                        .foregroundColor(Field.ink())
                }
                .gaugeStyle(.accessoryCircularCapacity)
                .tint(Field.ink())
                .scaleEffect(1.15)
                .frame(width: 64, height: 64)

                VStack(alignment: .leading, spacing: 2) {
                    Text(L.t(Strings.shared.home_score_title))
                        .font(.headline)
                        .foregroundColor(Field.ink())
                        .lineLimit(1)
                    Text(L.t(Strings.shared.home_score_out_of))
                        .font(.caption)
                        .foregroundColor(Field.ink(0.75))
                    if let change = card.change {
                        Text(change)
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(Field.ink(0.92))
                            .padding(.top, 2)
                    }
                    if let held = card.held {
                        Text(held)
                            .font(.caption)
                            .foregroundColor(Field.ink(0.75))
                            .padding(.top, 2)
                    }
                }
                Spacer(minLength: 8)
                FinAiIcon(symbol: "chevron.right", tint: Field.ink(0.85), size: 14)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Field.glass))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Field.glassEdge, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(card.accessibility)
        .accessibilityHint(L.t(Strings.shared.home_score_open))
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - On the sheet

/// A sheet section's heading, as "Recent transactions" is drawn.
private struct SectionHeader<Action: View>: View {
    let symbol: String
    let title: String
    @ViewBuilder let action: () -> Action
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(Accent.income.icon.opacity(dark ? 0.2 : 0.12))
                FinAiIcon(symbol: symbol, tint: Accent.income.label(dark), size: 17)
            }
            .frame(width: 36, height: 36)
            .accessibilityHidden(true)
            Text(title)
                .font(.title3.weight(.bold))
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            action()
        }
    }
}

/// A white row card, as the recent list's rows are.
private struct SheetCard<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        content()
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(dark ? Brand.surface : .white))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(dark ? Color.white.opacity(0.06) : Color(red: 0xE8 / 255, green: 0xF1 / 255, blue: 0xEC / 255), lineWidth: 1)
            )
    }
}

private struct CategoryTile: View {
    let icon: CategoryIcon
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        let tint = icon.tint(dark)
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(tint.opacity(dark ? 0.2 : 0.12))
            FinAiIcon(symbol: icon.systemName, tint: tint, size: 19)
        }
        .frame(width: 42, height: 42)
        .accessibilityHidden(true)
    }
}

/**
 "This month's budget": the totals and the lines nearest or over their
 allocation. "See budget" is drawn only when there is a Budget tab to open.
 */
struct BudgetSection: View {
    let card: BudgetCard
    let onOpenBudget: (() -> Void)?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(symbol: "chart.pie.fill", title: L.t(Strings.shared.home_budget_title)) {
                if let onOpenBudget {
                    Button(action: onOpenBudget) {
                        HStack(spacing: 2) {
                            Text(L.t(Strings.shared.home_budget_see)).font(.subheadline.weight(.semibold))
                            FinAiIcon(symbol: "chevron.right", tint: Accent.income.label(dark), size: 11)
                        }
                        .foregroundColor(Accent.income.label(dark))
                        .padding(.leading, 12)
                        .padding(.trailing, 9)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Accent.income.icon.opacity(dark ? 0.18 : 0.1)))
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(card.totals).font(.subheadline).foregroundColor(Brand.textMuted)
                if let shortfall = card.shortfall {
                    Text(shortfall).font(.subheadline).foregroundColor(Accent.expenses.label(dark))
                }
            }
            ForEach(Array(card.rows.enumerated()), id: \.offset) { _, row in line(row, dark: dark) }
        }
    }

    private func line(_ row: BudgetRow, dark: Bool) -> some View {
        let warning = Accent.expenses.label(dark)
        return SheetCard {
            HStack(spacing: 12) {
                CategoryTile(icon: row.icon)
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        Text(row.name).font(.headline.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                        Spacer(minLength: 0)
                        Text(row.amounts).font(.subheadline).foregroundColor(Brand.textMuted).lineLimit(1)
                    }
                    ProgressView(value: row.fraction)
                        .tint(row.isOver ? warning : row.icon.tint(dark))
                    // Never colour alone: an over line says so, with an icon.
                    if let over = row.overLabel {
                        HStack(spacing: 4) {
                            FinAiIcon(symbol: "exclamationmark.triangle.fill", tint: warning, size: 12)
                            Text(over).font(.caption.weight(.semibold)).foregroundColor(warning)
                        }
                    }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.accessibility)
    }
}

/// "Where it went": the month's spending by category, the top five, then all.
struct SpendingSection: View {
    let card: SpendingCard
    let expanded: Bool
    let onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(symbol: "chart.donut.fill", title: L.t(Strings.shared.home_spend_title)) { EmptyView() }
            ForEach(Array((expanded ? card.all : card.top).enumerated()), id: \.offset) { _, row in
                SheetCard {
                    HStack(spacing: 12) {
                        CategoryTile(icon: row.icon)
                        Text(row.name).font(.headline.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                        Spacer(minLength: 8)
                        Text(row.amount).font(.headline.weight(.bold)).lineLimit(1)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(row.accessibility)
            }
            if let more = card.showAllLabel {
                Button(expanded ? L.t(Strings.shared.home_spend_show_less) : more, action: onToggle)
                    .tappableArea()
            }
        }
    }
}

// MARK: - The breakdown

/**
 What the score is made of, read from `/health-score` when opened.

 A part the server could not score says "Not counted yet" and draws no bar — a
 bar at zero would claim a score of zero it does not have.
 */
struct ScoreBreakdownSheet: View {
    let breakdown: ScoreBreakdown?
    let loading: Bool
    let failed: Bool
    let onRetry: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L.t(Strings.shared.home_breakdown_title))
                    .font(.title3.weight(.bold))
                    .accessibilityAddTraits(.isHeader)
                if let breakdown {
                    content(breakdown)
                } else if failed {
                    Text(L.t(Strings.shared.home_breakdown_failed)).font(.subheadline)
                    Button(L.t(Strings.shared.action_retry), action: onRetry).tappableArea()
                } else if loading {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24)
                }
            }
            .padding(24)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func content(_ breakdown: ScoreBreakdown) -> some View {
        Text(L.t(Strings.shared.home_breakdown_score, breakdown.score))
            .font(.largeTitle.weight(.bold))
        VStack(alignment: .leading, spacing: 18) {
            ForEach(Array(breakdown.rows.enumerated()), id: \.offset) { _, row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(row.title).font(.headline.weight(.semibold)).lineLimit(1)
                        Spacer(minLength: 0)
                        Text(row.score)
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(row.available ? .primary : Brand.textMuted)
                    }
                    if row.available {
                        ProgressView(value: row.fraction)
                    }
                    if !row.sentence.isEmpty {
                        Text(row.sentence).font(.subheadline).foregroundColor(Brand.textMuted)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(row.accessibility)
            }
        }
        VStack(alignment: .leading, spacing: 4) {
            if let formula = breakdown.formula {
                Text(formula).font(.caption).foregroundColor(Brand.textMuted)
            }
            Text(breakdown.disclaimer).font(.footnote).foregroundColor(Brand.textMuted)
        }
        .padding(.top, 4)
    }
}
