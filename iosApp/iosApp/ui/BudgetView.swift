import SwiftUI
import SharedLogic

/**
 The month's budget: every category with what has been spent against it, and
 any line changeable by hand (#47, PRD F4).

 Built in Home's language — the green field with the totals, a white sheet
 with the lines — because no design was supplied and the ticket's fallback is
 to match what is already there. The Android screen is the same shape.

 **Over budget is never colour alone.** Every line past its allocation carries
 a warning mark and an "Over by" label, and that is also what VoiceOver reads.
 */
struct BudgetView: View {

    @ObservedObject var model: BudgetViewModel
    let userId: String
    var onReview: () -> Void
    var onImport: () -> Void

    /// The edit in progress as the system keeps it for this scene, so the app
    /// coming back after iOS reclaimed it reopens the line being typed.
    @SceneStorage("budget.draft") private var stored = ""
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        ZStack(alignment: .top) {
            ZStack {
                Field.gradient(dark)
                Waves()
                FieldVectors()
            }
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if model.loadFailed {
                        loadFailed(dark)
                    } else {
                        if let progress = model.learning {
                            LearningFieldCard(progress: progress, onImport: onImport)
                        }
                        // Hand-set lines show whatever the status says:
                        // manual budgeting is available before the first full
                        // month (PRD F9), and hiding someone's own figures
                        // would be a lie about what they had set.
                        sheet(dark)
                    }
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .onAppear {
            if !stored.isEmpty { model.restore(from: stored) }
            model.bind(userId: userId)
        }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: editingShown) { BudgetEditorSheet(model: model) }
        .sheet(isPresented: $model.picking) { BudgetCategorySheet(model: model) }
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { model.editing != nil }, set: { if !$0 { model.cancelEdit() } })
    }

    // MARK: - In the field

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.t(Strings.shared.budget_title))
                .font(.largeTitle.weight(.bold))
                .foregroundColor(Field.ink())
                .accessibilityAddTraits(.isHeader)

            monthStrip

            if let budget = model.budget {
                Text(totalsLabel(budget))
                    .font(.headline)
                    .foregroundColor(Field.ink(0.92))

                ProgressView(value: totalFraction(budget))
                    .tint(Field.ink(0.95))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(L.t(Strings.shared.budget_expected_income))
                        .font(.caption)
                        .foregroundColor(Field.ink(0.7))
                    Text(money(budget.expectedIncome))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Field.ink())
                }

                if let savings = budget.savings {
                    Text(L.t(Strings.shared.budget_set_aside, money(savings.allocated)))
                        .font(.subheadline)
                        .foregroundColor(Field.ink(0.85))
                }

                if let shortfall = budget.shortfall {
                    shortfallRow(L.t(Strings.shared.budget_shortfall, money(shortfall)))
                }
            }
        }
    }

    /// Said in words, not in red: a shortfall is a fact, not a warning colour.
    private func shortfallRow(_ label: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(Field.ink(0.9))
            Text(label)
                .font(.subheadline)
                .foregroundColor(Field.ink())
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Field.glass))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Field.glassEdge, lineWidth: 1))
    }

    private var monthStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.months, id: \.self) { key in
                    let selected = key == model.month
                    Button { model.showMonth(key) } label: {
                        Text(monthLabel(key))
                            .font(.subheadline.weight(selected ? .semibold : .regular))
                            .foregroundColor(selected ? Field.ink() : Field.ink(0.72))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(selected ? Field.glass : .clear))
                            .overlay(Capsule().stroke(selected ? Field.glassEdge : .clear, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
                }
            }
            .padding(.vertical, 2)
        }
    }

    // MARK: - The sheet

    private func sheet(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.showsEmpty {
                Text(L.t(Strings.shared.budget_empty))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }

            ForEach(model.lines, id: \.categoryId) { line in
                LineRow(model: model, line: line, dark: dark)
            }
            if let savings = model.savings {
                LineRow(model: model, line: savings, dark: dark)
            }
            if let debt = model.debt {
                LineRow(model: model, line: debt, dark: dark, caption: L.t(Strings.shared.budget_debt_caption))
            }

            Button { model.addCategory() } label: {
                HStack(spacing: 12) {
                    IconTile(symbol: "plus", accent: Mint.greenText(dark), size: 40, iconSize: 18)
                    Text(L.t(Strings.shared.budget_add_category))
                        .font(.body)
                        .foregroundColor(.primary)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)

            if let unfiled = unfiledLabel {
                VStack(alignment: .leading, spacing: 6) {
                    Text(unfiled).font(.subheadline)
                    Button(L.t(Strings.shared.budget_unfiled_action), action: onReview)
                        .font(.subheadline.weight(.semibold))
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16).fill(Mint.card(dark)))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Mint.edge(dark), lineWidth: 1))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 24).fill(Mint.panel(dark)))
    }

    private func loadFailed(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            // The server's own words for this refusal when it gave any —
            // "not included in your plan" beats "something went wrong".
            Text(L.t(model.errorKey ?? Strings.shared.error_title))
                .font(.subheadline)
            Button(L.t(Strings.shared.action_retry)) { model.retry() }
                .font(.subheadline.weight(.semibold))
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Mint.edge(dark), lineWidth: 1))
    }

    // MARK: - Words

    private func money(_ amount: String) -> String {
        Money.shared.format(amount: amount, currency: model.currency, locale: model.locale)
    }

    private func totalsLabel(_ budget: Budget) -> String {
        L.t(Strings.shared.budget_spent_of, money(budget.totalSpent), money(budget.totalAllocated))
    }

    /// A bar's length, never a figure — see `BudgetLine.fraction`.
    private func totalFraction(_ budget: Budget) -> Double {
        let digits = Money.shared.fractionDigits(currency: budget.currency)
        guard Money.shared.isPositive(raw: budget.totalAllocated, fractionDigits: digits),
              let spent = Double(Money.shared.normalize(raw: budget.totalSpent, fractionDigits: digits) ?? ""),
              let allocated = Double(Money.shared.normalize(raw: budget.totalAllocated, fractionDigits: digits) ?? ""),
              allocated > 0 else { return 0 }
        return min(max(spent / allocated, 0), 1)
    }

    private var unfiledLabel: String? {
        guard let budget = model.budget, budget.hasUncategorised else { return nil }
        return budget.uncategorisedCount == 1
            ? L.t(Strings.shared.budget_unfiled_one)
            : L.t(Strings.shared.budget_unfiled, String(budget.uncategorisedCount))
    }

    private func monthLabel(_ key: String) -> String {
        guard let date = DashboardMonths.shared.parse(raw: key) else { return key }
        return L.t(
            Strings.shared.dashboard_month_display,
            Dates.shared.monthShort(date: date, language: model.locale),
            String(date.year)
        )
    }
}

// MARK: - One line

/// One category: its tile, its name, spent of allocated, and a bar.
private struct LineRow: View {

    @ObservedObject var model: BudgetViewModel
    let line: BudgetLine
    let dark: Bool
    var caption: String?

    var body: some View {
        Button { model.edit(categoryId: line.categoryId) } label: {
            HStack(alignment: .top, spacing: 12) {
                IconTile(
                    symbol: CategoryIcons.shared.forSlug(slug: line.slug).systemName,
                    accent: CategoryIcons.shared.forSlug(slug: line.slug).tint(dark),
                    size: 40,
                    iconSize: 18
                )
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(line.name)
                            .font(.body.weight(.medium))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        if isOver {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.caption2)
                                .foregroundColor(.red)
                        }
                        Spacer(minLength: 0)
                    }
                    Text(L.t(Strings.shared.budget_line_amounts, money(line.spent), money(line.allocated)))
                        .font(.caption)
                        .foregroundColor(.secondary)
                    ProgressView(value: fraction)
                        .tint(isOver ? .red : Mint.greenText(dark))
                    if let over = overLabel {
                        Text(over).font(.caption.weight(.medium)).foregroundColor(.red)
                    }
                    if let suggestion = suggestionLabel {
                        Text(suggestion).font(.caption2).foregroundColor(.secondary)
                    }
                    if let caption {
                        Text(caption).font(.caption2).foregroundColor(.secondary)
                    }
                }
            }
            .frame(minHeight: 44)
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        // One sentence, so the row is heard once and "over by" is heard in
        // words rather than inferred from a colour.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
        .accessibilityAddTraits(.isButton)
    }

    private var digits: Int32 { Money.shared.fractionDigits(currency: model.currency) }

    private var isOver: Bool { line.isOver(fractionDigits: digits) }

    private var fraction: Double { line.fraction(fractionDigits: digits) }

    private var overLabel: String? {
        guard let over = line.overBy(fractionDigits: digits) else { return nil }
        return L.t(Strings.shared.budget_over_by, money(over))
    }

    private var suggestionLabel: String? {
        guard line.differsFromSuggestion(fractionDigits: digits) else { return nil }
        return L.t(Strings.shared.budget_suggested, money(line.suggested))
    }

    private var description: String {
        if let over = line.overBy(fractionDigits: digits) {
            return L.t(
                Strings.shared.budget_line_a11y_over,
                line.name, money(line.spent), money(line.allocated), money(over)
            )
        }
        return L.t(Strings.shared.budget_line_a11y, line.name, money(line.spent), money(line.allocated))
    }

    private func money(_ amount: String) -> String {
        Money.shared.format(amount: amount, currency: model.currency, locale: model.locale)
    }
}
