import SwiftUI
import SharedLogic

/**
 Your plan, and Your debts behind it (#76, redesign 2026-10-31). Android's
 `PlanRoute`, `PlanScreen` and `DebtsScreen`.

 In the Goals tab's language: the field with the monthly surplus on a dark
 card, white sheets beneath — where it goes, each row editable, then the
 choices it was worked out from. Every figure is the server's and every
 sentence shared `YourPlan`'s or `DebtEdit`'s. Guidance, not advice: "Save or
 invest" names no product, and the disclaimer is on the page.

 `startOnDebts` opens Your debts first — from Home's Debts screen — and Back
 from there closes rather than showing a plan the person did not come for.
 */
struct PlanView: View {

    @ObservedObject var model: PlanViewModel
    let userId: String
    let startOnDebts: Bool
    let onClose: () -> Void
    /// "Go to Goals", from the emergency-fund suggestion.
    let onOpenGoals: () -> Void

    @SceneStorage("plan.draft") private var stored = ""
    /// A diagnostic open over the plan: `allocation` or `invest`.
    @State private var diagnostic: String?
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

            VStack(spacing: 0) {
                HStack {
                    Button(action: back) {
                        Image(systemName: "chevron.left").font(.body.weight(.semibold)).foregroundColor(Field.ink()).tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.action_back))
                    Spacer()
                }
                .padding(.horizontal, 8)
                .frame(height: 52)

                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(L.t(model.page == .plan ? Strings.shared.plan_title : Strings.shared.debts_title))
                            .font(.largeTitle.weight(.bold))
                            .foregroundColor(Field.ink())
                            .accessibilityAddTraits(.isHeader)
                        if model.loading && model.plan == nil {
                            // The coin covers a first load.
                            EmptyView()
                        } else if model.loadFailed {
                            loadFailed(dark)
                        } else if model.page == .plan {
                            planContent(dark)
                        } else {
                            debtsContent(dark)
                        }
                    }
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .onAppear {
            if !stored.isEmpty {
                model.restore(from: stored)
            } else {
                model.page = startOnDebts ? .debts : .plan
            }
            model.bind(userId: userId)
        }
        .onChange(of: model.snapshot) { _, snapshot in stored = snapshot }
        .sheet(isPresented: choicesShown) { ChoicesSheet(model: model) }
        .sheet(isPresented: rowEditShown) { RowEditSheet(model: model) }
        .sheet(isPresented: debtShown) { DebtEditorSheet(model: model) }
        .sheet(isPresented: balanceShown) { BalanceSheet(model: model) }
        .fullScreenCover(isPresented: diagnosticShown) {
            if let kind = diagnostic {
                DiagnosticView(kind: kind) {
                    diagnostic = nil
                    // A diagnostic can change the plan's choices ("Pay debt before goals").
                    Task { await model.load(refresh: true) }
                }
            }
        }
    }

    private func back() {
        if model.page == .debts && !startOnDebts {
            model.page = .plan
        } else {
            stored = ""
            onClose()
        }
    }

    private func openGoals() {
        stored = ""
        onOpenGoals()
    }

    private var rowEditShown: Binding<Bool> {
        Binding(get: { model.rowEditBase != nil }, set: { if !$0 { model.cancelRowEdit() } })
    }

    private var choicesShown: Binding<Bool> {
        Binding(get: { model.choosing }, set: { if !$0 { model.cancelChoices() } })
    }

    private var debtShown: Binding<Bool> {
        Binding(get: { model.editingDebt != nil }, set: { if !$0 { model.cancelDebt() } })
    }

    private var balanceShown: Binding<Bool> {
        Binding(get: { model.balanceDebt != nil }, set: { if !$0 { model.cancelBalance() } })
    }

    // MARK: - Your plan

    @ViewBuilder
    private func planContent(_ dark: Bool) -> some View {
        if let card = model.surplusCard {
            SurplusCardView(card: card, dark: dark)
        }
        if model.showsSplit {
            PlanSheet(dark: dark) {
                SheetTitle(text: L.t(Strings.shared.plan_split_title))
                if let note = model.splitNote {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(note.text).font(.caption.weight(.medium)).foregroundColor(Mint.greenText(dark))
                        if let why = note.why { Text(why).font(.caption).foregroundColor(.secondary) }
                    }
                    .accessibilityElement(children: .combine)
                }
                ForEach(Array(model.splitRows.enumerated()), id: \.offset) { _, row in
                    SplitLine(row: row, dark: dark) { model.startRowEdit(row) }
                }
            }
        }
        PlanSheet(dark: dark) {
            HStack {
                SheetTitle(text: L.t(Strings.shared.plan_choices_title))
                Spacer()
                Button(L.t(Strings.shared.plan_choices_change)) { model.startChoices() }
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Mint.greenText(dark))
                    .disabled(model.plan == nil || model.weightRows.isEmpty)
                    .tappableArea()
            }
            if let suggestion = model.emergencySuggestion {
                EmergencySuggestion(title: suggestion.title, text: suggestion.body, dark: dark, action: openGoals)
            }
            ForEach(model.weightRows, id: \.priority) { row in
                WeightLine(row: row, dark: dark)
            }
            Divider().padding(.vertical, 4)
            Button { model.page = .debts } label: {
                HStack(spacing: 12) {
                    let icon = CategoryIcon.card
                    IconTile(symbol: icon.systemName, accent: icon.tint(dark), size: 40, iconSize: 18)
                    Text(L.t(Strings.shared.plan_debts_link)).foregroundColor(.primary)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").foregroundColor(.secondary)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        if model.plan != nil {
            PlanSheet(dark: dark) {
                SheetTitle(text: L.t(Strings.shared.diagnostic_card_title))
                diagnosticRow(PlanDiagnostics.companion.ALLOCATION, title: Strings.shared.diagnostic_allocation_title, icon: .piggy, dark: dark)
                diagnosticRow(PlanDiagnostics.companion.INVEST, title: Strings.shared.diagnostic_invest_title, icon: .salary, dark: dark)
            }
        }
        disclaimer
    }

    private var diagnosticShown: Binding<Bool> {
        Binding(get: { diagnostic != nil }, set: { if !$0 { diagnostic = nil } })
    }

    private func diagnosticRow(_ kind: String, title: String, icon: CategoryIcon, dark: Bool) -> some View {
        Button { diagnostic = kind } label: {
            HStack(spacing: 12) {
                IconTile(symbol: icon.systemName, accent: icon.tint(dark), size: 40, iconSize: 18)
                Text(L.t(title)).foregroundColor(.primary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundColor(.secondary)
            }
            .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Your debts

    @ViewBuilder
    private func debtsContent(_ dark: Bool) -> some View {
        if let line = model.debtFreeLine {
            Headline(text: line, detail: model.totalsLine)
        }
        PlanSheet(dark: dark) {
            if model.showsNoDebts {
                Text(L.t(Strings.shared.debts_empty)).font(.subheadline).foregroundColor(.secondary)
            } else {
                strategySwitch
                ForEach(model.debtRows, id: \.debtId) { row in
                    DebtLine(model: model, row: row, dark: dark)
                }
            }
            if let notice = model.noticeKey {
                Text(L.t(notice)).font(.caption).foregroundColor(.red)
            }
        }
        disclaimer
    }

    @ViewBuilder
    private var strategySwitch: some View {
        if let chosen = model.plan?.preferences.strategy {
            VStack(alignment: .leading, spacing: 8) {
                SheetTitle(text: L.t(Strings.shared.debts_strategy_title))
                Picker(L.t(Strings.shared.debts_strategy_title), selection: Binding(get: { chosen }, set: { model.chooseStrategy($0) })) {
                    ForEach([PlanStrategy.avalanche, PlanStrategy.snowball], id: \.self) { strategy in
                        Text(YourPlan.shared.strategyLabel(strategy: strategy, locale: model.locale)).tag(strategy)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(model.switchingStrategy)
                if let line = model.comparisonLine {
                    Text(line).font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }

    // MARK: - Both

    /// The plan's guidance line, always, and the region's disclaimer under it.
    @ViewBuilder
    private var disclaimer: some View {
        if model.plan != nil {
            VStack(alignment: .leading, spacing: 6) {
                Text(L.t(Strings.shared.plan_guidance)).font(.caption).foregroundColor(Field.ink(0.85))
                if let text = model.disclaimerText {
                    Text(text).font(.caption2).foregroundColor(Field.ink(0.75))
                }
            }
        }
    }

    private func loadFailed(_ dark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L.t(model.errorKey ?? Strings.shared.error_title)).font(.subheadline)
            Button(L.t(Strings.shared.action_retry)) { model.retry() }.font(.subheadline.weight(.semibold))
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Mint.edge(dark), lineWidth: 1))
    }
}

// MARK: - Pieces

/// The figure at the top, on glass in the green field.
private struct Headline: View {
    let text: String
    let detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text).font(.title3.weight(.semibold)).foregroundColor(Field.ink())
            if let detail { Text(detail).font(.subheadline).foregroundColor(Field.ink(0.85)) }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Field.glass))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Field.glassEdge, lineWidth: 1))
    }
}

private struct PlanSheet<Content: View>: View {
    let dark: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content() }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 24).fill(Mint.panel(dark)))
    }
}

private struct SheetTitle: View {
    let text: String

    var body: some View {
        Text(text).font(.headline).accessibilityAddTraits(.isHeader)
    }
}

/**
 The money left over, the month it is for, and a bar of where it goes with a
 legend beside it — on a dark green card in the field. With nothing to split,
 the headline says why instead of an amount.
 */
private struct SurplusCardView: View {
    let card: SurplusCard
    let dark: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(card.month).font(.caption.weight(.semibold)).foregroundColor(Field.ink(0.8))
                Text(card.title).font(.subheadline.weight(.medium)).foregroundColor(Field.ink(0.9))
                if let amount = card.amount {
                    Text(amount)
                        .font(.system(.largeTitle, design: .rounded).weight(.bold))
                        .foregroundColor(Field.ink())
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                } else {
                    Text(card.headline).font(.title3.weight(.semibold)).foregroundColor(Field.ink())
                }
            }
            if !card.segments.isEmpty {
                bar
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(card.segments.enumerated()), id: \.offset) { _, segment in
                        HStack(spacing: 8) {
                            Circle().fill(segmentColor(segment.kind)).frame(width: 10, height: 10)
                            Text(segment.label).font(.subheadline).foregroundColor(Field.ink(0.9)).lineLimit(1).truncationMode(.tail)
                            Spacer(minLength: 8)
                            Text(segment.amount).font(.subheadline.weight(.semibold)).foregroundColor(Field.ink()).lineLimit(1)
                        }
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(cardFill))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Field.glassEdge, lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    /// Each part's length is its share of the whole; a sliver stays visible.
    private var bar: some View {
        GeometryReader { geometry in
            let gaps = CGFloat(max(card.segments.count - 1, 0)) * 3
            let room = max(geometry.size.width - gaps, 0)
            HStack(spacing: 3) {
                ForEach(Array(card.segments.enumerated()), id: \.offset) { _, segment in
                    Capsule()
                        .fill(segmentColor(segment.kind))
                        .frame(width: max(room * CGFloat(segment.fraction), 4))
                }
            }
        }
        .frame(height: 10)
        .accessibilityHidden(true)
    }

    private var cardFill: Color {
        dark
            ? Color(red: 0x06 / 255, green: 0x24 / 255, blue: 0x14 / 255)
            : Color(red: 0x0A / 255, green: 0x3F / 255, blue: 0x22 / 255)
    }

    private var spoken: String {
        let lead = [card.month, card.title, card.amount ?? card.headline]
        let parts = card.segments.map { "\($0.label), \($0.amount)" }
        return (lead + parts).joined(separator: ", ")
    }

    /// Bright enough on the dark card in both modes; the legend names each one, so colour is never the only cue.
    private func segmentColor(_ kind: SplitKind) -> Color {
        switch kind {
        case .emergency: Color(red: 0xF4 / 255, green: 0x72 / 255, blue: 0xB6 / 255)
        case .goal: Color(red: 0xA7 / 255, green: 0x8B / 255, blue: 0xFA / 255)
        case .extraDebt: Color(red: 0xFB / 255, green: 0xBF / 255, blue: 0x24 / 255)
        case .saveOrInvest: Color(red: 0x4A / 255, green: 0xDE / 255, blue: 0x80 / 255)
        }
    }
}

/// One part of the split, and the Edit that sets it by hand.
private struct SplitLine: View {
    let row: SplitRow
    let dark: Bool
    let onEdit: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 12) {
                IconTile(symbol: row.icon.systemName, accent: row.icon.tint(dark), size: 40, iconSize: 18)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.label).font(.body.weight(.medium)).lineLimit(1).truncationMode(.tail)
                    if let detail = row.detail {
                        Text(detail).font(.caption).foregroundColor(.secondary).lineLimit(1).truncationMode(.tail)
                    }
                }
                Spacer(minLength: 8)
                Text(row.amount).font(.subheadline.weight(.bold)).lineLimit(1)
            }
            // Nothing here this month: quieter, never hidden.
            .opacity(row.isZero ? 0.6 : 1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.accessibility)
            Button(action: onEdit) {
                Text(L.t(Strings.shared.plan_edit))
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Mint.greenText(dark))
                    .tappableArea()
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.t(Strings.shared.plan_edit_named, row.label))
        }
        .frame(minHeight: 44)
    }
}

/// One of the choices: its place in the order, its share, and why it is there.
private struct WeightLine: View {
    let row: WeightRow
    let dark: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(String(row.priority))
                .font(.subheadline.weight(.bold))
                .foregroundColor(Mint.greenText(dark))
                .frame(width: 28, height: 28)
                .background(Circle().fill(Mint.tile(Mint.greenText(dark), dark)))
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(row.label).font(.body.weight(.medium)).lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 8)
                    Text(row.percent).font(.body.weight(.semibold)).lineLimit(1)
                }
                Text(row.explanation).font(.caption).foregroundColor(.secondary)
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([L.t(Strings.shared.plan_choices_priority, String(row.priority)), row.label, row.percent, row.explanation].joined(separator: ", "))
    }
}

/// "Add an emergency fund": it becomes a choice once it is a goal, so the button goes to Goals.
private struct EmergencySuggestion: View {
    let title: String
    let text: String
    let dark: Bool
    let action: () -> Void

    var body: some View {
        let green = Mint.greenText(dark)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: CategoryIcon.shield.systemName)
                    .font(.title3)
                    .foregroundColor(green)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold))
                    Text(text).font(.caption).foregroundColor(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
            Button(action: action) {
                Text(L.t(Strings.shared.plan_emergency_suggest_action))
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(green)
                    .tappableArea()
            }
            .buttonStyle(.plain)
            .padding(.leading, 36)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(green.opacity(dark ? 0.2 : 0.1)))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(green.opacity(0.35), lineWidth: 1))
    }
}

private struct DebtLine: View {
    @ObservedObject var model: PlanViewModel
    let row: DebtRow
    let dark: Bool

    var body: some View {
        let source = model.sourceLine(row.debtId)
        let stale = model.staleLine(row.debtId)
        let promotion = model.promotionLine(row.debtId)
        let linked = model.linkedLine(row.debtId)
        HStack(alignment: .top, spacing: 12) {
            IconTile(symbol: row.icon.systemName, accent: row.icon.tint(dark), size: 40, iconSize: 18)
            VStack(alignment: .leading, spacing: 3) {
                if let step = row.step {
                    Text(step).font(.caption.weight(.semibold)).foregroundColor(Mint.greenText(dark))
                }
                HStack {
                    Text(row.name).font(.body.weight(.medium)).lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 8)
                    Text(row.balance).font(.subheadline.weight(.bold)).lineLimit(1)
                }
                Text(row.subtitle).font(.caption).foregroundColor(.secondary)
                if let promotion { Text(promotion).font(.caption).foregroundColor(.secondary) }
                Text(row.payoff).font(.caption.weight(.medium)).foregroundColor(row.payoffNeedsAttention ? .red : .primary)
                if let note = row.note { Text(note).font(.caption.weight(.medium)).foregroundColor(Mint.greenText(dark)) }
                if let source { Text(source).font(.caption2).foregroundColor(.secondary) }
                if let linked { Text(linked).font(.caption2).foregroundColor(.secondary) }
                if let stale { Text(stale).font(.caption2).foregroundColor(.red) }
                Button(L.t(Strings.shared.debts_update_balance)) { model.startBalance(row.debtId) }
                    .font(.caption.weight(.semibold))
                    .frame(minHeight: 44)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { model.editDebt(row.debtId) }
        // One sentence, heard once; "Update balance" is an action on it.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([row.accessibility, promotion, source, stale, linked].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { model.editDebt(row.debtId) }
        .accessibilityAction(named: L.t(Strings.shared.debts_update_balance)) { model.startBalance(row.debtId) }
    }
}
