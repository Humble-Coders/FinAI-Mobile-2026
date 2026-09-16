import SwiftUI
import SharedLogic

/// The financial setup wizard: income, then expenses and obligations, then
/// debts and investments (PRD F1, ticket #17).
///
/// The first two figures are mandatory — the server keeps reporting
/// `financial_setup` until both are stored — and everything itemised is
/// optional, so every list offers a way past. Each step saves, so closing the
/// app resumes where it left off.
struct SetupView: View {
    @ObservedObject var model: SetupViewModel
    let onFinished: () -> Void

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            if model.loading {
                LoadingCoin(visible: true)
            } else if let list = model.editing {
                ItemListView(model: model, list: list)
            } else {
                steps
            }

            LoadingCoin(visible: model.busy)
        }
    }

    private var steps: some View {
        ZStack(alignment: .top) {
            // Drawn from the very top of the screen, under the status bar and
            // the notch, and moving with the page so the path runs on.
            PathBand(step: model.step)
                .ignoresSafeArea(edges: .top)

            VStack(spacing: 0) {
                Spacer().frame(height: Self.bandHeight)

                TabView(selection: Binding(get: { model.step }, set: { model.goTo($0) })) {
                    ForEach(SetupView.allSteps, id: \.self) { step in
                        ScrollView {
                            page(for: step).padding(.horizontal, 24).padding(.top, 8)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .tag(step)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.easeInOut(duration: 0.3), value: model.step)

                VStack(spacing: 8) {
                    ErrorText(messageKey: model.errorKey)
                    if model.errorKey == nil, let block = model.block {
                        // The same rule the button reads, said out loud.
                        ErrorText(messageKey: block.messageKey)
                    }
                    GradientButton(
                        title: L.t(model.step.isLast
                                   ? Strings.shared.setup_complete
                                   : Strings.shared.action_continue),
                        enabled: model.canContinue
                    ) { model.continueStep(onFinished: onFinished) }

                    if model.canSkip {
                        Button(L.t(Strings.shared.setup_skip)) { model.skip(onFinished: onFinished) }
                            .font(.subheadline)
                            .foregroundColor(Brand.textMuted)
                            .tappableRow()
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
            }

            // Over the art, clear of the notch.
            StepHeader(step: model.step) { model.back() }
        }
    }

    @ViewBuilder
    private func page(for step: SetupStep) -> some View {
        switch step {
        case .expenses: expensesStep
        case .portfolio: portfolioStep
        default: incomeStep
        }
    }

    /// The wizard's steps, in order. Written out rather than read off the
    /// bridged enum, whose `entries` is a class property on the Kotlin side.
    static let allSteps: [SetupStep] = [.income, .expenses, .portfolio]

    /// How far down the art reaches: to the top of the fields, as in the design.
    static let bandHeight: CGFloat = 300

    // MARK: - Steps

    private var incomeStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(step: model.step,
                      titleKey: Strings.shared.setup_income_title,
                      bodyKey: Strings.shared.setup_income_body)
            WizardCard {
                AmountField(
                    label: L.t(Strings.shared.setup_income_label),
                    symbol: model.symbol,
                    text: Binding(get: { model.draft.income }, set: { model.setIncome($0) })
                )
            }
            Text(L.t(Strings.shared.setup_income_hint))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
        }
    }

    private var expensesStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(step: model.step,
                      titleKey: Strings.shared.setup_expenses_title,
                      bodyKey: Strings.shared.setup_expenses_body)
            WizardCard {
                AmountField(
                    label: L.t(Strings.shared.setup_expense_label),
                    symbol: model.symbol,
                    text: Binding(get: { model.draft.monthlyExpense }, set: { model.setExpense($0) })
                )
            }
            ListRow(label: L.t(Strings.shared.setup_obligations_label),
                    count: model.draft.obligations.count) { model.openList(.obligations) }
        }
    }

    private var portfolioStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(step: model.step,
                      titleKey: Strings.shared.setup_portfolio_title,
                      bodyKey: Strings.shared.setup_portfolio_body)
            ListRow(label: L.t(Strings.shared.setup_debts_label),
                    count: model.draft.debts.count) { model.openList(.debts) }
            ListRow(label: L.t(Strings.shared.setup_investments_label),
                    count: model.draft.investments.count) { model.openList(.investments) }
        }
    }
}

/// The back arrow and the progress dots.
private struct StepHeader: View {
    let step: SetupStep
    let onBack: () -> Void

    var body: some View {
        HStack {
            if step != .income {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.title3.weight(.semibold))
                        .foregroundColor(.primary)
                        .tappableArea()
                }
                .accessibilityLabel(L.t(Strings.shared.action_back))
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
            Spacer()
            HStack(spacing: 6) {
                ForEach(0 ..< Int(SetupStep.companion.COUNT), id: \.self) { index in
                    Capsule()
                        .fill(index <= Int(step.ordinal) ? Brand.green : Brand.border)
                        .frame(width: index == Int(step.ordinal) ? 20 : 8, height: 8)
                }
                Text("\(step.number)/\(SetupStep.companion.COUNT)")
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .padding(.leading, 8)
            }
        }
        .padding(.horizontal, 16)
    }
}

/// The path, running on from step to step.
///
/// One wide illustration, each step showing its own third, so the route the
/// user is walking is literally continuous between screens.
private struct PathBand: View {
    let step: SetupStep

    var body: some View {
        GeometryReader { geometry in
            Image("SetupPath")
                .resizable()
                .scaledToFill()
                .frame(
                    width: geometry.size.width * CGFloat(SetupStep.companion.COUNT),
                    height: SetupView.bandHeight
                )
                .offset(x: -geometry.size.width * CGFloat(step.ordinal))
                .animation(.easeInOut(duration: 0.3), value: step)
        }
        .frame(height: SetupView.bandHeight)
        .clipped()
        .accessibilityHidden(true)
    }
}

private struct StepTitle: View {
    let step: SetupStep
    let titleKey: String
    let bodyKey: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t(Strings.shared.setup_step_label, "\(step.number)").uppercased())
                .font(.caption.weight(.bold))
                .foregroundColor(Brand.green)
            Text(L.t(titleKey)).font(.title2.weight(.bold))
            Text(L.t(bodyKey))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
        }
    }
}

/// A row that opens an itemised list, showing how much is in it.
private struct ListRow: View {
    let label: String
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.headline).foregroundColor(.primary)
                    Text(count == 0
                         ? L.t(Strings.shared.setup_none_yet)
                         : L.t(Strings.shared.setup_items_added, "\(count)"))
                        .font(.footnote)
                        .foregroundColor(Brand.textMuted)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundColor(Brand.textMuted)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 64)
            .frame(maxWidth: .infinity)
            .background(Brand.surfaceField)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }
}

/// The itemised list behind a row: add rows, drop rows, keep them.
private struct ItemListView: View {
    @ObservedObject var model: SetupViewModel
    let list: SetupViewModel.ItemList

    private var debts: Bool { list == .debts }

    private var title: String {
        switch list {
        case .obligations: return L.t(Strings.shared.setup_obligations_label)
        case .debts: return L.t(Strings.shared.setup_debts_label)
        case .investments: return L.t(Strings.shared.setup_investments_label)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { model.discardRows() } label: {
                        Image(systemName: "chevron.left")
                            .font(.title3.weight(.semibold))
                            .foregroundColor(.primary)
                            .tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.action_back))
                    Text(title).font(.title2.weight(.bold))
                }

                ForEach(model.rows.indices, id: \.self) { index in
                    WizardCard {
                        VStack(alignment: .leading, spacing: 8) {
                            WizardField(
                                label: L.t(Strings.shared.setup_item_name),
                                text: binding(index, \.name) { row, value in
                                    ItemDraft(name: value, amount: row.amount,
                                              minimumPayment: row.minimumPayment,
                                              interestRatePercent: row.interestRatePercent)
                                }
                            )
                            AmountField(
                                label: L.t(debts ? Strings.shared.setup_debt_balance : Strings.shared.setup_item_amount),
                                symbol: model.symbol,
                                text: binding(index, \.amount) { row, value in
                                    ItemDraft(name: row.name, amount: value,
                                              minimumPayment: row.minimumPayment,
                                              interestRatePercent: row.interestRatePercent)
                                }
                            )
                            if debts {
                                AmountField(
                                    label: L.t(Strings.shared.setup_debt_minimum),
                                    symbol: model.symbol,
                                    text: binding(index, \.minimumPayment) { row, value in
                                        ItemDraft(name: row.name, amount: row.amount,
                                                  minimumPayment: value,
                                                  interestRatePercent: row.interestRatePercent)
                                    }
                                )
                                WizardField(
                                    label: L.t(Strings.shared.setup_debt_rate),
                                    keyboard: .decimalPad,
                                    text: binding(index, \.interestRatePercent) { row, value in
                                        ItemDraft(name: row.name, amount: row.amount,
                                                  minimumPayment: row.minimumPayment,
                                                  interestRatePercent: value)
                                    }
                                )
                            }
                            if let block = model.rowBlock(at: index) {
                                ErrorText(messageKey: block.messageKey)
                            }
                            Button(L.t(Strings.shared.setup_remove_item)) { model.removeRow(at: index) }
                                .font(.subheadline)
                                .foregroundColor(Brand.textMuted)
                                .tappableRow()
                        }
                    }
                }

                Button(L.t(Strings.shared.setup_add_item)) { model.addRow() }
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Brand.green)
                    .tappableRow()

                GradientButton(title: L.t(Strings.shared.action_save), enabled: model.canKeepRows) {
                    model.keepRows()
                }
                Button(L.t(Strings.shared.action_cancel)) { model.discardRows() }
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .tappableRow()
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    /// SKIE gives Swift no `copy()`, so every edit rebuilds the row.
    private func binding(
        _ index: Int,
        _ field: KeyPath<ItemDraft, String>,
        _ rebuild: @escaping (ItemDraft, String) -> ItemDraft
    ) -> Binding<String> {
        Binding(
            get: { model.rows.indices.contains(index) ? model.rows[index][keyPath: field] : "" },
            set: { value in
                guard model.rows.indices.contains(index) else { return }
                model.rows[index] = rebuild(model.rows[index], value)
            }
        )
    }
}

private struct WizardCard<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Brand.sheet)
            .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

/// An amount, with the currency the server named beside it — never a hardcoded symbol.
private struct AmountField: View {
    let label: String
    let symbol: String
    @Binding var text: String

    var body: some View {
        WizardField(label: label, keyboard: .decimalPad, leading: symbol, text: $text)
    }
}

private struct WizardField: View {
    let label: String
    var keyboard: UIKeyboardType = .default
    var leading: String?
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundColor(Brand.textMuted)
            HStack(spacing: 8) {
                if let leading {
                    Text(leading).font(.headline).foregroundColor(Brand.textMuted)
                }
                TextField("", text: $text)
                    .keyboardType(keyboard)
                    .textInputAutocapitalization(.words)
            }
            .frame(minHeight: 48)
            .padding(.horizontal, 12)
            .background(Brand.surfaceField)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }
}
