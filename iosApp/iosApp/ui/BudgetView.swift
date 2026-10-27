import SwiftUI
import SharedLogic

/**
 The month's budget, as a board of cards on the green field (#47, PRD F4): the
 month so far, what is still expected, the AI's word on it, every category
 against its share of the budget, and the next steps.

 Laid out after the reference design the product owner shared; every figure
 in it is the server's, worded once in the shared `BudgetBoard`. The Android
 screen is the same board.

 **Over budget is never colour alone.** Every line past its allocation carries
 a warning mark and an "Over by" label, and that is also what VoiceOver reads.
 */
struct BudgetView: View {

    @ObservedObject var model: BudgetViewModel
    let userId: String
    var onReview: () -> Void
    var onImport: () -> Void
    /// Your plan (#76); nil when the plan is off, which hides what opens it.
    var onOpenPlan: (() -> Void)?

    /// The edit in progress as the system keeps it for this scene, so the app
    /// coming back after iOS reclaimed it reopens the line being typed.
    @SceneStorage("budget.draft") private var stored = ""
    @Environment(\.colorScheme) private var scheme
    /// How far the big title has shrunk into the bar, as on Home.
    @State private var collapse = HeaderCollapse()
    @State private var pickingMonth = false
    /// "Ask AI for a budget": the suggested budget, over this screen.
    @State private var askingAi = false
    /// The AI card opened.
    @State private var insightOpen = false

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
                        loadFailed
                    } else if let budget = model.budget {
                        board(budget, dark: dark)
                    }
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
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
        .sheet(isPresented: $insightOpen) { insightSheet(dark) }
        .fullScreenCover(isPresented: $askingAi) {
            AiBudgetView(userId: userId, inSetup: false) {
                askingAi = false
                Task { await model.load(refresh: true) }
            }
        }
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

    // MARK: - The board

    @ViewBuilder
    private func board(_ budget: Budget, dark: Bool) -> some View {
        let board = BudgetBoard.shared
        let locale = model.locale
        if let progress = model.learning {
            LearningFieldCard(progress: progress, onImport: onImport)
        }
        if let soFar = board.soFar(budget: budget, locale: locale) {
            SoFarCard(board: soFar)
        }
        let commitments = BudgetOverview.shared.commitments(budget: budget, locale: locale)
        if let forecast = board.forecast(budget: budget, locale: locale) {
            ForecastCard(forecast: forecast, commitments: commitments)
        } else if !commitments.isEmpty {
            BoardCard {
                Text(L.t(Strings.shared.budget_commitments_title)).font(.headline.weight(.bold)).accessibilityAddTraits(.isHeader)
                ForEach(Array(commitments.enumerated()), id: \.offset) { _, item in CommitmentLine(item: item) }
            }
        }
        if model.spendingCheck != nil || aiAdvice(budget) != nil {
            InsightCard(headline: insightHeadline(budget), detail: insightDetail) { insightOpen = true }
        }
        if BudgetOverview.shared.showsPlan(budget: budget) {
            // Hand-set lines show whatever the status says: manual budgeting is
            // available before the first full month (PRD F9), and hiding
            // someone's own figures would be a lie about what they had set.
            categories(budget, dark: dark)
            extras(budget, dark: dark)
        } else {
            // A month that has ended is a record, not a plan: a one-off is a
            // row in its month, not a line.
            endedCategories(budget, dark: dark)
        }
        let steps = board.nextSteps(budget: budget, planAvailable: onOpenPlan != nil, locale: locale)
        if !steps.isEmpty {
            NextStepsCard(
                title: board.nextStepsTitle(locale: locale),
                subtitle: board.nextStepsSubtitle(count: Int32(steps.count), locale: locale),
                steps: steps,
                onStep: run
            )
        }
    }

    private func run(_ step: NextStep) {
        switch step.action.name {
        case "REVIEW": onReview()
        case "IMPORT": onImport()
        case "PLAN": onOpenPlan?()
        case "SAVINGS_TARGET": model.setSavingsTarget()
        default:
            if let id = step.categoryId { model.addFromUnbudgeted(categoryId: id) }
        }
    }

    // MARK: - In the field

    /// "FINANCIAL PLANNING", the screen's name, the month, and the review bell.
    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(BudgetBoard.shared.eyebrow(locale: model.locale).uppercased())
                        .font(.caption2.weight(.bold))
                        .tracking(1.4)
                        .foregroundColor(Color(red: 0.525, green: 0.937, blue: 0.675))
                    Circle().fill(Color(red: 0.525, green: 0.937, blue: 0.675)).frame(width: 6, height: 6)
                }
                Text(L.t(Strings.shared.budget_title))
                    .font(.title.weight(.heavy))
                    .foregroundColor(Field.ink())
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 4)
            monthButton
            reviewBell
        }
        .modifier(HeaderFade(collapse: collapse))
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.frame(in: .named(Self.space)).minY
        } action: { (top: CGFloat) in
            collapse.track(top: top)
        }
    }

    /// The bell: the review queue, with a dot while anything waits there.
    private var reviewBell: some View {
        let waiting = Int(model.budget?.uncategorisedCount ?? 0)
        let label = L.t(Strings.shared.board_review)
        return Button(action: onReview) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "bell")
                    .font(.body.weight(.semibold))
                    .foregroundColor(Field.ink())
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Field.glass))
                    .overlay(Circle().stroke(Field.glassEdge, lineWidth: 1))
                if waiting > 0 {
                    Circle()
                        .fill(Color(red: 0.290, green: 0.871, blue: 0.502))
                        .frame(width: 10, height: 10)
                        .overlay(Circle().stroke(Field.top(scheme == .dark), lineWidth: 2))
                        .offset(x: -9, y: 9)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(waiting > 0 ? "\(label), \(waiting)" : label)
    }

    /**
     The month shown, on a glass pill that opens the month-and-year calendar.
     One control rather than a strip of a dozen pills: any month in range is a
     tap or two away, and the field stays uncluttered.
     */
    private var monthButton: some View {
        Button { pickingMonth = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "calendar").font(.caption)
                Text(monthLabel(model.month)).font(.subheadline.weight(.semibold)).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold))
            }
            .foregroundColor(Field.ink())
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(Capsule().fill(Field.glass))
            .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L.t(Strings.shared.budget_month_change, monthLabel(model.month)))
        .accessibilityAddTraits(.isButton)
    }

    // MARK: - The AI

    private func aiAdvice(_ budget: Budget) -> String? {
        BudgetOverview.shared.showsPlan(budget: budget) ? budget.aiAdvice : nil
    }

    /// The AI card's words: the spending check's headline, else the monthly review's.
    private func insightHeadline(_ budget: Budget) -> String? {
        model.spendingCheck?.headline ?? aiAdvice(budget)
    }

    private var insightDetail: String? {
        guard model.spendingCheck?.headline != nil else { return nil }
        return model.spendingCheck?.tips.first
    }

    /**
     The AI card opened: needs, wants and savings as shares of income beside
     what the rule aims for, the AI's advice on spending the right way for that
     income, and the monthly review. Every figure is the server's; the AI's
     part is words.
     */
    private func insightSheet(_ dark: Bool) -> some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let check = model.spendingCheck { spendingBody(check, dark: dark) }
                    if let budget = model.budget, let advice = aiAdvice(budget) {
                        Divider()
                        Text(L.t(Strings.shared.ai_budget_review_title)).font(.subheadline.weight(.semibold))
                        Text(advice).font(.subheadline)
                    }
                    if let onOpenPlan {
                        Button(L.t(Strings.shared.ai_budget_see_plan)) {
                            insightOpen = false
                            onOpenPlan()
                        }
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 44)
                    }
                }
                .padding(20)
            }
            .navigationTitle(L.t(Strings.shared.board_ai_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_done)) { insightOpen = false }
                }
            }
        }
        .presentationDetents([.large])
    }

    @ViewBuilder
    private func spendingBody(_ check: SpendingCheck, dark: Bool) -> some View {
        let words = SpendingCheckWords.shared
        Text(words.title(locale: model.locale)).font(.headline)
        Text(words.incomeLine(c: check, locale: model.locale)).font(.caption).foregroundColor(.secondary)
        ForEach(Array(model.spendingRows.enumerated()), id: \.offset) { _, row in shareRow(row, dark: dark) }
        if let policy = model.spendingPolicy {
            Text(L.t(Strings.shared.ai_budget_consent_title)).font(.subheadline.weight(.semibold))
            Text(L.t(Strings.shared.ai_budget_consent_body)).font(.caption)
            Text(policy.body)
                .font(.caption)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
            Toggle(isOn: $model.spendingConsentTicked) { Text(L.t(Strings.shared.import_consent_agree)) }
                .toggleStyle(CheckboxStyle())
            ErrorText(messageKey: model.spendingErrorKey)
            GradientButton(
                title: L.t(Strings.shared.ai_budget_consent_allow),
                enabled: model.spendingConsentTicked,
                busy: model.spendingConsentBusy
            ) { model.agreeSpending() }
        } else {
            if let headline = check.headline {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "sparkles").foregroundColor(BoardPalette.violet).accessibilityHidden(true)
                    Text(headline).font(.subheadline.weight(.semibold))
                }
            }
            ForEach(Array(check.tips.enumerated()), id: \.offset) { _, tip in
                HStack(alignment: .top, spacing: 8) {
                    Text("•").foregroundColor(BoardPalette.violet).accessibilityHidden(true)
                    Text(tip).font(.subheadline)
                }
            }
            if check.hasAdvice && check.stale {
                Text(L.t(Strings.shared.spending_stale)).font(.caption).foregroundColor(.secondary)
            }
            ErrorText(messageKey: model.spendingErrorKey)
            if model.checkingSpending {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(L.t(Strings.shared.spending_asking)).font(.subheadline)
                }
                .accessibilityElement(children: .combine)
            } else if words.canAsk(c: check) {
                GradientButton(title: words.askLabel(c: check, locale: model.locale), enabled: true, busy: false) {
                    model.askSpending()
                }
                Text(words.runsLeft(c: check, locale: model.locale)).font(.caption2).foregroundColor(.secondary)
            }
            if check.hasAdvice {
                Text(L.t(Strings.shared.diagnostic_guidance)).font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    /// One share of income: what it is, the bar with the aim marked, and how it sits.
    private func shareRow(_ row: SpendingShareRow, dark: Bool) -> some View {
        let tone: Color = switch row.status {
        case .over, .short: .red
        case .toGo: .secondary
        default: Mint.greenText(dark)
        }
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row.label).font(.body.weight(.medium))
                Spacer(minLength: 8)
                Text("\(row.amount) · \(row.share)").font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.tertiarySystemFill))
                    Capsule().fill(tone).frame(width: geo.size.width * CGFloat(row.fraction))
                    // Where the aim sits on the bar.
                    Rectangle()
                        .fill(Color.primary)
                        .frame(width: 2)
                        .offset(x: geo.size.width * CGFloat(row.aimFraction) - 1)
                }
            }
            .frame(height: 6)
            HStack {
                Text(row.aim).font(.caption2).foregroundColor(.secondary)
                Spacer(minLength: 8)
                Text(row.statusLabel).font(.caption.weight(.semibold)).foregroundColor(tone)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(row.label), \(row.amount), \(row.share). \(row.aim). \(row.statusLabel)")
    }

    // MARK: - Categories

    /// The categories card: the donut, every line as a row in its slice's colour, and ways to add.
    private func categories(_ budget: Budget, dark: Bool) -> some View {
        BoardCard(glow: BoardPalette.orange) {
            if let donut = BudgetBoard.shared.donut(budget: budget, locale: model.locale) {
                DonutHeader(donut: donut)
            } else {
                BoardHeader(symbol: "chart.pie.fill", accent: BoardPalette.orange, title: L.t(Strings.shared.board_categories_title))
            }
            if let shortfall = budget.shortfall {
                shortfallRow(L.t(Strings.shared.budget_shortfall, money(shortfall)))
            }
            if model.showsEmpty {
                Text(L.t(Strings.shared.budget_empty)).font(.subheadline).foregroundColor(.secondary)
            }
            ForEach(model.lines, id: \.categoryId) { line in LineRow(model: model, line: line, dark: dark) }
            // Savings is a target the person sets, never a figure filled in for them.
            if let savings = model.savings {
                LineRow(model: model, line: savings, dark: dark)
            } else if budget.savingsCategoryId != nil {
                actionRow(
                    symbol: "banknote",
                    title: L.t(Strings.shared.budget_set_target),
                    detail: BudgetOverview.shared.savingsRoom(budget: budget, locale: model.locale),
                    accent: Mint.greenText(dark)
                ) { model.setSavingsTarget() }
            }
            if let debt = model.debt {
                LineRow(model: model, line: debt, dark: dark, caption: L.t(Strings.shared.budget_debt_caption))
            }
            actionRow(symbol: "plus", title: L.t(Strings.shared.budget_add_category), detail: nil, accent: Mint.greenText(dark)) {
                model.addCategory()
            }
            if budget.aiKind != "review" {
                actionRow(symbol: "sparkles", title: L.t(Strings.shared.ai_budget_open), detail: nil, accent: BoardPalette.violet) {
                    askingAi = true
                }
            }
        }
    }

    /// Spending with no line, the AI's extra categories, and rows with no category yet.
    @ViewBuilder
    private func extras(_ budget: Budget, dark: Bool) -> some View {
        if let unbudgeted = BudgetEdit.shared.unbudgetedLine(budget: budget, locale: model.locale) {
            unbudgetedCard(budget, label: unbudgeted, dark: dark)
        }
        if !budget.aiExtra.isEmpty {
            BoardCard(glow: BoardPalette.violetSoft) {
                BoardHeader(symbol: "sparkles", accent: BoardPalette.violet, title: L.t(Strings.shared.ai_budget_extra_title))
                ForEach(budget.aiExtra, id: \.categoryId) { extra in
                    HStack(spacing: 12) {
                        Text("\(extra.name) · \(money(extra.amount))").font(.body)
                        Spacer(minLength: 8)
                        Button(L.t(Strings.shared.budget_unbudgeted_add)) { model.addFromAi(categoryId: extra.categoryId) }
                            .font(.subheadline.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                }
            }
        }
        if let unfiled = unfiledLabel {
            BoardCard(glow: BoardPalette.color(0, dark: dark)) {
                Text(unfiled).font(.subheadline)
                Button(L.t(Strings.shared.budget_unfiled_action), action: onReview)
                    .font(.subheadline.weight(.semibold))
                    .frame(minHeight: 44)
            }
        }
    }

    /// A month that has ended: the donut of what was spent, and every category at its amount.
    private func endedCategories(_ budget: Budget, dark: Bool) -> some View {
        let rows = BudgetOverview.shared.spent(budget: budget, locale: model.locale)
        return BoardCard(glow: BoardPalette.orange) {
            if let donut = BudgetBoard.shared.donut(budget: budget, locale: model.locale) {
                DonutHeader(donut: donut)
            } else {
                BoardHeader(symbol: "chart.pie.fill", accent: BoardPalette.orange, title: L.t(Strings.shared.budget_where_it_went))
                Text(L.t(Strings.shared.budget_empty)).font(.subheadline).foregroundColor(.secondary)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { index, spend in
                spentRow(spend, index: index, budget: budget, dark: dark)
            }
        }
    }

    private func spentRow(_ spend: OverviewSpend, index: Int, budget: Budget, dark: Bool) -> some View {
        let icon = CategoryIcons.shared.forSlug(slug: spend.slug)
        return HStack(spacing: 12) {
            BoardTile(symbol: icon.systemName, accent: icon.tint(dark))
            VStack(alignment: .leading, spacing: 6) {
                Text(spend.name).font(.body.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                Capsule()
                    .fill(BoardPalette.color(BudgetBoard.shared.colorIndexOfSpend(index: Int32(index)), dark: dark))
                    .frame(width: 64, height: 4)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(spend.amount).font(.body.weight(.bold))
                Text(BudgetBoard.shared.shareOfSpending(index: Int32(index), budget: budget, locale: model.locale))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Mint.edge(dark), lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    /// "Add a category", "Set a savings target", "Ask AI for a budget": an outlined row with a tile.
    private func actionRow(symbol: String, title: String, detail: String?, accent: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                BoardTile(symbol: symbol, accent: accent, size: 36, iconSize: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundColor(accent)
                    if let detail {
                        Text(detail).font(.caption).foregroundColor(.secondary).multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minHeight: 52)
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(accent.opacity(0.35), lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
    }

    /// Said in words, not in red alone: a shortfall is a fact.
    private func shortfallRow(_ label: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.red)
            Text(label).font(.subheadline)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.red.opacity(0.08)))
    }

    /**
     "Not in your budget": the categories spent in this month that have no
     line, each with what was spent and a way to give it one.
     */
    private func unbudgetedCard(_ budget: Budget, label: String, dark: Bool) -> some View {
        BoardCard(glow: BoardPalette.orange) {
            BoardHeader(
                symbol: "exclamationmark.triangle.fill",
                accent: BoardPalette.orange,
                title: L.t(Strings.shared.budget_unbudgeted_title),
                subtitle: label
            )
            ForEach(budget.unbudgeted, id: \.categoryId) { spend in unbudgetedRow(spend, dark: dark) }
        }
    }

    private func unbudgetedRow(_ spend: UnbudgetedSpend, dark: Bool) -> some View {
        let icon = CategoryIcons.shared.forSlug(slug: spend.slug)
        return HStack(spacing: 12) {
            BoardTile(symbol: icon.systemName, accent: icon.tint(dark), size: 36, iconSize: 16)
            VStack(alignment: .leading, spacing: 2) {
                Text(spend.name).font(.body.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                Text(money(spend.spent)).font(.caption).foregroundColor(.secondary)
                if let ai = spend.aiSuggested {
                    Text(L.t(Strings.shared.ai_budget_suggests, money(ai)))
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(BoardPalette.violet)
                }
                if let oneOff = model.oneOff(spend) {
                    Text(oneOff).font(.caption2).foregroundColor(.secondary)
                }
                if let tip = model.tip(for: spend.categoryId) {
                    Text(tip).font(.caption2.weight(.medium)).foregroundColor(BoardPalette.violet)
                }
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

    private var loadFailed: some View {
        BoardCard {
            // The server's own words for this refusal when it gave any —
            // "not included in your plan" beats "something went wrong".
            Text(L.t(model.errorKey ?? Strings.shared.error_title)).font(.subheadline)
            Button(L.t(Strings.shared.action_retry)) { model.retry() }
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: 44)
        }
    }

    // MARK: - Words

    private func money(_ amount: String) -> String {
        Money.shared.format(amount: amount, currency: model.currency, locale: model.locale)
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

/**
 One category: its tile, its name over a bar in its slice's colour, the amount
 and its share of the budget — and under it anything worth saying.
 */
private struct LineRow: View {

    @ObservedObject var model: BudgetViewModel
    let line: BudgetLine
    let dark: Bool
    var caption: String?

    var body: some View {
        VStack(spacing: 0) {
            Button { model.edit(categoryId: line.categoryId) } label: {
                HStack(spacing: 12) {
                    BoardTile(
                        symbol: CategoryIcons.shared.forSlug(slug: line.slug).systemName,
                        accent: CategoryIcons.shared.forSlug(slug: line.slug).tint(dark)
                    )
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            Text(line.name)
                                .font(.body.weight(.semibold))
                                .foregroundColor(.primary)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            if isOver {
                                Image(systemName: "exclamationmark.triangle.fill").font(.caption2).foregroundColor(.red)
                            }
                        }
                        CategoryBar(fraction: fraction, color: barColor)
                        Text(L.t(Strings.shared.budget_line_amounts, money(line.spent), money(line.allocated)))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 6)
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(money(line.allocated)).font(.body.weight(.bold)).foregroundColor(.primary).lineLimit(1)
                        Text(allotted).font(.caption2).foregroundColor(.secondary)
                        Image(systemName: "pencil").font(.caption2.weight(.semibold)).foregroundColor(Mint.greenText(dark))
                    }
                }
                .padding(12)
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // One sentence, so the row is heard once and "over by" is heard in
            // words rather than inferred from a colour.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(description)
            .accessibilityHint(L.t(Strings.shared.budget_edit_line, line.name))
            .accessibilityAddTraits(.isButton)

            if !notes.isEmpty || aiLabel != nil {
                Divider().overlay(Mint.edge(dark))
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(notes.enumerated()), id: \.offset) { _, note in
                        Text(note.0).font(.caption.weight(.medium)).foregroundColor(note.1)
                    }
                    if let label = aiLabel {
                        HStack(spacing: 6) {
                            Image(systemName: "sparkles").font(.caption2).foregroundColor(BoardPalette.violet).accessibilityHidden(true)
                            Text(label).font(.caption.weight(.semibold)).foregroundColor(BoardPalette.violet)
                            Spacer(minLength: 8)
                            Button(L.t(Strings.shared.ai_budget_use)) { model.useAiSuggestion(categoryId: line.categoryId) }
                                .font(.subheadline.weight(.semibold))
                                .frame(minHeight: 44)
                                .disabled(model.saving)
                                .accessibilityLabel("\(L.t(Strings.shared.ai_budget_use)), \(label), \(line.name)")
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(RoundedRectangle(cornerRadius: 18).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(isOver ? Color.red.opacity(0.4) : Mint.edge(dark), lineWidth: 1))
    }

    private var digits: Int32 { Money.shared.fractionDigits(currency: model.currency) }

    private var isOver: Bool { line.isOver(fractionDigits: digits) }

    private var fraction: Double { line.fraction(fractionDigits: digits) }

    private var barColor: Color {
        if isOver { return .red }
        guard let budget = model.budget else { return BoardPalette.color(4, dark: dark) }
        return BoardPalette.color(BudgetBoard.shared.colorIndexOf(line: line, budget: budget), dark: dark)
    }

    private var allotted: String {
        guard let budget = model.budget else { return "" }
        return BudgetBoard.shared.allotted(line: line, budget: budget, locale: model.locale)
    }

    private var aiLabel: String? {
        AiBudgetEdit.shared.suggestionLabel(line: line, currency: model.currency, locale: model.locale)
    }

    /// What is said under the row, each in its own colour.
    private var notes: [(String, Color)] {
        var out: [(String, Color)] = []
        if let over = line.overBy(fractionDigits: digits) { out.append((L.t(Strings.shared.budget_over_by, money(over)), .red)) }
        if line.differsFromSuggestion(fractionDigits: digits) {
            out.append((L.t(Strings.shared.budget_suggested, money(line.suggested)), .secondary))
        }
        if let oneOff = model.oneOff(line) { out.append((oneOff, .secondary)) }
        if let only = model.thisMonthOnly(line) { out.append((only, Mint.greenText(dark))) }
        if let tip = model.tip(for: line.categoryId) { out.append((tip, BoardPalette.violet)) }
        if let caption { out.append((caption, .secondary)) }
        return out
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
