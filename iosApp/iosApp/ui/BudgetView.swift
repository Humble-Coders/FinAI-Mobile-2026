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
    /// Your plan (#76), from the Savings line; nil when the plan is off, which hides the link.
    var onOpenPlan: (() -> Void)?

    /// The edit in progress as the system keeps it for this scene, so the app
    /// coming back after iOS reclaimed it reopens the line being typed.
    @SceneStorage("budget.draft") private var stored = ""
    @Environment(\.colorScheme) private var scheme
    /// How far the big title has shrunk into the bar, as on Home.
    @State private var collapse = HeaderCollapse()
    @State private var pickingMonth = false

    private static let space = "budget"

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
                        // The month as the statements tell it, first.
                        if let budget = model.budget { overview(budget, dark: dark) }
                        if model.budget.map({ BudgetOverview.shared.showsPlan(budget: $0) }) ?? true {
                            // Hand-set lines show whatever the status says:
                            // manual budgeting is available before the first
                            // full month (PRD F9), and hiding someone's own
                            // figures would be a lie about what they had set.
                            planSummary
                            sheet(dark)
                        } else if let budget = model.budget {
                            // A month that has ended is a record, not a plan:
                            // a one-off is a row in its month, not a line.
                            spent(budget, dark: dark)
                        }
                    }
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .coordinateSpace(name: Self.space)
            .scrollBounceBehavior(.basedOnSize)
            // Pinned once the page has scrolled: the screen's name, centred.
            .overlay(alignment: .top) {
                CollapsingBar(collapse: collapse, dark: dark) {
                    CompactTitle(title: L.t(Strings.shared.tab_budget))
                }
            }
        }
        .onAppear {
            if !stored.isEmpty { model.restore(from: stored) }
            model.bind(userId: userId)
        }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: editingShown) { BudgetEditorSheet(model: model) }
        .sheet(isPresented: $model.picking) { BudgetCategorySheet(model: model) }
        .sheet(isPresented: $pickingMonth) {
            MonthYearPickerSheet(
                selected: model.month,
                onPick: { key in
                    pickingMonth = false
                    model.showMonth(key)
                },
                onCancel: { pickingMonth = false }
            )
        }
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
                .modifier(HeaderFade(collapse: collapse))
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .named(Self.space)).minY
                } action: { (top: CGFloat) in
                    collapse.track(top: top)
                }

            monthButton
        }
    }

    /// "Your budget": spent of allocated, expected income, what is set aside.
    private var planSummary: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.t(Strings.shared.budget_your_budget))
                .font(.title2.weight(.bold))
                .foregroundColor(Field.ink())
                .accessibilityAddTraits(.isHeader)
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
                    // What the plan does with it (#76) — the same figure, split.
                    if let onOpenPlan {
                        Button(L.t(Strings.shared.budget_savings_plan_link), action: onOpenPlan)
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Field.ink())
                            .frame(minHeight: 44)
                    }
                }

                if let shortfall = budget.shortfall {
                    shortfallRow(L.t(Strings.shared.budget_shortfall, money(shortfall)))
                }
            }
        }
    }

    // MARK: - Above the lines

    /// The month so far, its commitments, what is still to come, and what to do with the rest.
    @ViewBuilder
    private func overview(_ budget: Budget, dark: Bool) -> some View {
        let words = BudgetOverview.shared
        if let rows = words.soFar(budget: budget, locale: model.locale) {
            card(words.soFarTitle(budget: budget, locale: model.locale), subtitle: L.t(Strings.shared.budget_from_statements), dark: dark) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in figureRow(row) }
            }
        }
        let commitments = words.commitments(budget: budget, locale: model.locale)
        if !commitments.isEmpty {
            card(L.t(Strings.shared.budget_commitments_title), dark: dark) {
                ForEach(Array(commitments.enumerated()), id: \.offset) { _, item in commitmentRow(item, dark: dark) }
            }
        }
        if let rows = words.stillExpected(budget: budget, locale: model.locale) {
            card(L.t(Strings.shared.budget_still_expected_title), dark: dark) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in figureRow(row) }
            }
        }
        if let text = words.suggestion(budget: budget, locale: model.locale) {
            HStack(spacing: 10) {
                Image(systemName: words.suggestsUsing(budget: budget) ? "banknote" : "exclamationmark.triangle.fill")
                    .foregroundColor(Field.ink(0.9))
                    .accessibilityHidden(true)
                Text(text)
                    .font(.subheadline)
                    .foregroundColor(Field.ink())
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16).fill(Field.glass))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Field.glassEdge, lineWidth: 1))
        }
    }

    private func card<Content: View>(_ title: String, subtitle: String? = nil, dark: Bool, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).font(.caption).foregroundColor(.secondary)
            }
            content()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Mint.panel(dark)))
    }

    /// A label and its amount; the card's bottom line is ruled off and set in bold.
    @ViewBuilder
    private func figureRow(_ row: OverviewRow) -> some View {
        if row.isTotal { Divider() }
        HStack(spacing: 12) {
            Text(row.label)
            Spacer(minLength: 8)
            Text(row.amount)
        }
        .font(row.isTotal ? .subheadline.weight(.semibold) : .subheadline)
        .frame(minHeight: 32)
        .accessibilityElement(children: .combine)
    }

    /// A tick when its payment was seen, a dashed circle when not — and the words, always.
    private func commitmentRow(_ item: OverviewCommitment, dark: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: item.seen ? "checkmark.circle.fill" : "circle.dashed")
                .foregroundColor(item.seen ? Mint.greenText(dark) : .secondary)
            Text(item.name)
                .font(.subheadline)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(item.amount).font(.subheadline)
                Text(item.status).font(.caption2).foregroundColor(.secondary)
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.description_)
    }

    /// A month that has ended: every category at what was actually spent.
    private func spent(_ budget: Budget, dark: Bool) -> some View {
        let rows = BudgetOverview.shared.spent(budget: budget, locale: model.locale)
        return VStack(alignment: .leading, spacing: 12) {
            Text(L.t(Strings.shared.budget_where_it_went))
                .font(.title2.weight(.bold))
                .foregroundColor(Field.ink())
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 10) {
                if rows.isEmpty {
                    Text(L.t(Strings.shared.budget_empty)).font(.subheadline).foregroundColor(.secondary)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 12) {
                        IconTile(
                            symbol: CategoryIcons.shared.forSlug(slug: row.slug).systemName,
                            accent: CategoryIcons.shared.forSlug(slug: row.slug).tint(dark),
                            size: 36,
                            iconSize: 16
                        )
                        Text(row.name).font(.body).lineLimit(1).truncationMode(.tail)
                        Spacer(minLength: 8)
                        Text(row.amount).font(.body)
                    }
                    .frame(minHeight: 44)
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 24).fill(Mint.panel(dark)))
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

    /**
     The month shown, on a glass pill that opens the month-and-year calendar.
     One control rather than a strip of a dozen pills: any month in range is a
     tap or two away, and the field stays uncluttered.
     */
    private var monthButton: some View {
        Button { pickingMonth = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "calendar")
                    .font(.subheadline)
                Text(monthLabel(model.month))
                    .font(.subheadline.weight(.semibold))
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
            }
            .foregroundColor(Field.ink())
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(Capsule().fill(Field.glass))
            .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L.t(Strings.shared.budget_month_change, monthLabel(model.month)))
        .accessibilityAddTraits(.isButton)
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

            if let budget = model.budget,
               let unbudgeted = BudgetEdit.shared.unbudgetedLine(budget: budget, locale: model.locale) {
                unbudgetedCard(budget, label: unbudgeted, dark: dark)
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

    /**
     "Not in your budget": the categories spent in this month that have no
     line, each with what was spent and a way to give it one.
     */
    private func unbudgetedCard(_ budget: Budget, label: String, dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t(Strings.shared.budget_unbudgeted_title))
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            ForEach(budget.unbudgeted, id: \.categoryId) { spend in
                HStack(spacing: 12) {
                    IconTile(
                        symbol: CategoryIcons.shared.forSlug(slug: spend.slug).systemName,
                        accent: CategoryIcons.shared.forSlug(slug: spend.slug).tint(dark),
                        size: 36,
                        iconSize: 16
                    )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(spend.name)
                            .font(.body)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Text(money(spend.spent))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer(minLength: 8)
                    Button(L.t(Strings.shared.budget_unbudgeted_add)) {
                        model.addFromUnbudgeted(categoryId: spend.categoryId)
                    }
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .frame(minHeight: 44)
                    .accessibilityLabel("\(L.t(Strings.shared.budget_unbudgeted_add)), \(spend.name)")
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Mint.edge(dark), lineWidth: 1))
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
        // Everything spent, including categories with no line, against what the lines allocate.
        L.t(Strings.shared.budget_spent_of, money(budget.spentTotal), money(budget.totalAllocated))
    }

    /// A bar's length, never a figure — see `BudgetLine.fraction`.
    private func totalFraction(_ budget: Budget) -> Double {
        let digits = Money.shared.fractionDigits(currency: budget.currency)
        guard Money.shared.isPositive(raw: budget.totalAllocated, fractionDigits: digits),
              let spent = Double(Money.shared.normalize(raw: budget.spentTotal, fractionDigits: digits) ?? ""),
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
                        // The edit button beside the name. The whole row opens
                        // the same editor — this says so, where the eye looks.
                        Image(systemName: "pencil")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(Mint.greenText(dark))
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(Mint.greenText(dark).opacity(dark ? 0.2 : 0.12)))
                            .accessibilityHidden(true)
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
        .accessibilityHint(L.t(Strings.shared.budget_edit_line, line.name))
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
