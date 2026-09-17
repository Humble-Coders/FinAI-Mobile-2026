import SwiftUI
import SharedLogic

/// The financial setup wizard: income, then expenses and obligations, then
/// debts and investments (PRD F1, ticket #17).
///
/// The steps can be swiped through freely; Continue is what waits for the
/// mandatory figures. Continue slides straight to the next step and saves behind
/// the move, with a small loader only while a request is in flight — the coin
/// loader is for the first load alone, and lives at the app's root.
struct SetupView: View {
    @ObservedObject var model: SetupViewModel
    let onFinished: () -> Void

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            if model.loading {
                // The app's coin loader covers the first load; underneath, ground.
                Color.clear
            } else if model.cancelled {
                SetupRequiredView { model.resume() }
            } else if let list = model.editing {
                ItemListView(model: model, list: list)
            } else {
                steps
            }
        }
        .toolbar {
            // Number pads have no return key, so without this there is no way
            // off the keyboard but tapping the little that stays visible.
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(L.t(Strings.shared.action_done)) { dismissKeyboard() }
            }
        }
    }

    private var steps: some View {
        GeometryReader { geometry in
            let top = geometry.safeAreaInsets.top
            ZStack(alignment: .top) {
                VStack(spacing: 0) {
                    // A paging scroll view rather than a paged TabView: the art
                    // is part of each page, so the picture and the step are one
                    // piece and move together frame for frame under the finger.
                    // (A TabView reports no drag offset, so art drawn behind it
                    // could only catch up after the swipe settled.)
                    ScrollView(.horizontal) {
                        HStack(spacing: 0) {
                            ForEach(SetupView.allSteps, id: \.self) { step in
                                VStack(spacing: 0) {
                                    PathBand(index: Int(step.ordinal), top: top)
                                    ScrollView {
                                        page(for: step).padding(.horizontal, 24).padding(.top, 8)
                                    }
                                    .scrollBounceBehavior(.basedOnSize)
                                    .scrollDismissesKeyboard(.interactively)
                                }
                                .frame(maxHeight: .infinity, alignment: .top)
                                .containerRelativeFrame(.horizontal)
                                .id(step)
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollTargetBehavior(.paging)
                    .scrollIndicators(.hidden)
                    .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                    .scrollPosition(id: Binding(
                        get: { Optional(model.step) },
                        set: { step in if let step { model.goTo(step) } }
                    ))
                    .scrollDisabled(model.busy)
                    // Under the notch: the band starts there and fades in.
                    .ignoresSafeArea(edges: .top)

                    controls
                }

                StepHeader(
                    step: model.step,
                    onBack: { withAnimation(Self.slide) { model.back() } },
                    onCancel: { model.cancel() }
                )

                SavingIndicator(visible: model.showsSaving)
                    .padding(.top, StepHeader.height + 4)
            }
        }
    }

    private var controls: some View {
        VStack(spacing: 8) {
            ErrorText(messageKey: model.errorKey)
            if model.errorKey == nil {
                // The same rule the button reads, said out loud.
                ErrorText(messageKey: model.notice?.messageKey)
            }
            // Skip sits above Continue, so the main action stays last and under
            // the thumb.
            if model.showsSkip {
                Button(L.t(Strings.shared.setup_skip)) { model.skip(onFinished: onFinished) }
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(Brand.textMuted)
                    .opacity(model.canSkip ? 1 : 0.4)
                    .disabled(!model.canSkip)
                    .tappableRow()
            }

            GradientButton(
                title: L.t(model.step.isLast
                           ? Strings.shared.setup_complete
                           : Strings.shared.action_continue),
                enabled: model.canContinue
            ) {
                dismissKeyboard()
                withAnimation(Self.slide) { model.continueStep(onFinished: onFinished) }
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
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

    /// How far the art reaches below the notch: to the top of the fields.
    static let bandHeight: CGFloat = 300

    /// One step to the next: a plain slide, no loader in the way.
    static let slide = Animation.easeInOut(duration: 0.38)

    // MARK: - Steps

    private var incomeStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(step: .income,
                      titleKey: Strings.shared.setup_income_title,
                      bodyKey: Strings.shared.setup_income_body)
            AmountField(
                label: L.t(Strings.shared.setup_income_label),
                symbol: model.symbol,
                placeholder: model.amountPlaceholder,
                suffix: L.t(Strings.shared.setup_per_month),
                isError: model.step == .income && model.notice?.step == .income,
                text: Binding(get: { model.draft.income }, set: { model.setIncome($0) })
            )
            Text(L.t(Strings.shared.setup_income_hint))
                .font(.footnote)
                .foregroundColor(Brand.textMuted)
        }
    }

    private var expensesStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(step: .expenses,
                      titleKey: Strings.shared.setup_expenses_title,
                      bodyKey: Strings.shared.setup_expenses_body)
            AmountField(
                label: L.t(Strings.shared.setup_expense_label),
                symbol: model.symbol,
                placeholder: model.amountPlaceholder,
                suffix: L.t(Strings.shared.setup_per_month),
                isError: model.step == .expenses && model.notice?.step == .expenses,
                text: Binding(get: { model.draft.monthlyExpense }, set: { model.setExpense($0) })
            )
            ListRow(
                label: L.t(Strings.shared.setup_obligations_label),
                total: model.total(of: .obligations),
                hint: L.t(Strings.shared.setup_amount_hint)
            ) { model.openList(.obligations) }
        }
    }

    private var portfolioStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(step: .portfolio,
                      titleKey: Strings.shared.setup_portfolio_title,
                      bodyKey: Strings.shared.setup_portfolio_body)
            ListRow(
                label: L.t(Strings.shared.setup_debts_label),
                total: model.total(of: .debts),
                hint: L.t(Strings.shared.setup_total_balance_hint)
            ) { model.openList(.debts) }
            ListRow(
                label: L.t(Strings.shared.setup_investments_label),
                total: model.total(of: .investments),
                hint: L.t(Strings.shared.setup_total_amount_hint)
            ) { model.openList(.investments) }
        }
    }
}

/// The back arrow, the progress dots and Cancel.
private struct StepHeader: View {
    static let height: CGFloat = 44

    let step: SetupStep
    let onBack: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            HStack(spacing: 6) {
                ForEach(0 ..< Int(SetupStep.companion.COUNT), id: \.self) { index in
                    Capsule()
                        .fill(index <= Int(step.ordinal) ? Brand.green : Brand.border)
                        .frame(width: index == Int(step.ordinal) ? 20 : 8, height: 8)
                }
                Text(L.t(
                    Strings.shared.setup_step_counter,
                    "\(step.number)",
                    "\(SetupStep.companion.COUNT)"
                ))
                    .font(.subheadline)
                    .foregroundColor(Brand.textMuted)
                    .padding(.leading, 8)
            }

            HStack {
                if step != .income {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.title3.weight(.semibold))
                            .foregroundColor(.primary)
                            .tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.action_back))
                }
                Spacer()
                Button(L.t(Strings.shared.action_cancel), action: onCancel)
                    .font(.body.weight(.medium))
                    .foregroundColor(.primary)
                    .frame(minHeight: 44)
            }
        }
        .frame(height: Self.height)
        .padding(.horizontal, 16)
    }
}

/// The path, running on from step to step.
///
/// One wide illustration, and page `index` shows its own third, starting below
/// the notch. The pages sit edge to edge, so their thirds join into the one
/// continuous path. The notch area is plain ground fading into the art, and the
/// art fades back into ground above the fields.
private struct PathBand: View {
    let index: Int
    let top: CGFloat

    private static let topFade: CGFloat = 60
    private static let bottomFade: CGFloat = 72

    var body: some View {
        let ground = Color(.systemBackground)
        // A vector third per page (tools/make_setup_path_vectors.py), with a dark
        // appearance, so a page renders one screen of art, sharp at any scale.
        Color.clear
            .frame(height: SetupView.bandHeight)
            .overlay { Image("SetupPath\(index + 1)").resizable().scaledToFill() }
            .clipped()
            .padding(.top, top)
            .overlay(alignment: .top) {
            LinearGradient(
                stops: [
                    .init(color: ground, location: 0),
                    .init(color: ground.opacity(0.85), location: top / max(top + Self.topFade, 1)),
                    .init(color: ground.opacity(0), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: top + Self.topFade)
        }
        .overlay(alignment: .bottom) {
            LinearGradient(colors: [ground.opacity(0), ground], startPoint: .top, endPoint: .bottom)
                .frame(height: Self.bottomFade)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The small loader for a save behind the steps: a frosted pill, never the coin.
private struct SavingIndicator: View {
    let visible: Bool

    var body: some View {
        ProgressView()
            .controlSize(.small)
            .tint(Brand.green)
            .padding(9)
            .background(.ultraThinMaterial, in: Capsule())
            .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
            .opacity(visible ? 1 : 0)
            .animation(.easeInOut(duration: 0.18), value: visible)
            .allowsHitTesting(false)
            .accessibilityElement()
            .accessibilityLabel(L.t(Strings.shared.setup_saving))
            .accessibilityHidden(!visible)
    }
}

/// What Cancel leads to: setup is required, and the way back to step 1.
private struct SetupRequiredView: View {
    let onResume: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text(L.t(Strings.shared.setup_required_title))
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            Text(L.t(Strings.shared.setup_required_body))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
            Button(action: onResume) {
                Text(L.t(Strings.shared.setup_required_action))
                    .font(.body.weight(.semibold))
                    .underline()
                    .foregroundColor(Brand.green)
            }
            .tappableRow()
        }
        .frame(maxWidth: 420)
        .padding(.horizontal, 32)
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

/// A row that opens an itemised list: the same box as the fields, the list's
/// total in green once there is one, and a tinted chevron that says it opens.
private struct ListRow: View {
    let label: String
    let total: String?
    let hint: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.headline).foregroundColor(.primary)
                    if let total {
                        Text(total).font(.title3.weight(.semibold)).foregroundColor(Brand.green)
                    } else {
                        Text(hint).font(.footnote).foregroundColor(Brand.textMuted)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundColor(Brand.green)
                    .frame(width: 32, height: 32)
                    .background(Brand.green.opacity(0.12), in: Circle())
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 68)
            .frame(maxWidth: .infinity)
            .fieldFrame(focused: false, isError: false)
            .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
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
                                autocapitalization: .words,
                                text: binding(index, \.name) { row, value in
                                    ItemDraft(name: value, amount: row.amount,
                                              minimumPayment: row.minimumPayment,
                                              interestRatePercent: row.interestRatePercent)
                                }
                            )
                            AmountField(
                                label: L.t(debts ? Strings.shared.setup_debt_balance : Strings.shared.setup_item_amount),
                                symbol: model.symbol,
                                placeholder: model.amountPlaceholder,
                                // An obligation is a monthly payment; a balance or a holding is not.
                                suffix: list == .obligations ? L.t(Strings.shared.setup_per_month) : nil,
                                large: false,
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
                                    placeholder: model.amountPlaceholder,
                                    suffix: L.t(Strings.shared.setup_per_month),
                                    large: false,
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
        .scrollDismissesKeyboard(.interactively)
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

extension SetupView {
    /// The corner radius every field and list row shares.
    static let fieldRadius: CGFloat = 16
}

private extension View {
    /// The box every field and list row sits in: the sheet colour, a hairline
    /// border, green and thicker while typing, red while its figure will not do.
    func fieldFrame(focused: Bool, isError: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: SetupView.fieldRadius)
        return self
            .background(Brand.sheet, in: shape)
            .overlay(
                shape.strokeBorder(
                    isError ? Color.red : (focused ? Brand.green : Brand.border),
                    lineWidth: focused || isError ? 2 : 1
                )
            )
            .animation(.easeInOut(duration: 0.15), value: focused)
            .animation(.easeInOut(duration: 0.15), value: isError)
    }
}

/// An amount, the way finance apps take one: a label above, the figure large and
/// bold beside the currency the server named — never a hardcoded symbol — a faint
/// zero while it is empty, and what the figure is per, when it is per anything.
/// Tapping anywhere on the box starts typing.
private struct AmountField: View {
    let label: String
    let symbol: String
    var placeholder: String = ""
    var suffix: String?
    var isError = false
    var large = true
    @Binding var text: String
    @FocusState private var focused: Bool

    private var figure: Font { large ? .title2.weight(.semibold) : .title3.weight(.semibold) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: label)
            HStack(spacing: 8) {
                Text(symbol).font(figure).foregroundColor(Brand.textMuted)
                TextField("", text: $text, prompt: Text(placeholder).foregroundColor(Brand.textMuted.opacity(0.4)))
                    .font(figure)
                    .keyboardType(.decimalPad)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .focused($focused)
                    .tint(Brand.green)
                    .accessibilityLabel(label)
                if let suffix {
                    Text(suffix).font(.subheadline.weight(.medium)).foregroundColor(Brand.textMuted)
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: large ? 68 : 56)
            .fieldFrame(focused: focused, isError: isError)
            .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
            .onTapGesture { focused = true }
        }
    }
}

/// A plain text field in the same box as the amounts.
private struct WizardField: View {
    let label: String
    var keyboard: UIKeyboardType = .default
    /// Names want words capitalised; a rate does not.
    var autocapitalization: TextInputAutocapitalization = .never
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: label)
            TextField("", text: $text)
                .font(.body)
                .keyboardType(keyboard)
                .textInputAutocapitalization(autocapitalization)
                .autocorrectionDisabled()
                .focused($focused)
                .tint(Brand.green)
                .accessibilityLabel(label)
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .fieldFrame(focused: focused, isError: false)
                .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
                .onTapGesture { focused = true }
        }
    }
}

private struct FieldLabel: View {
    let text: String

    var body: some View {
        Text(text).font(.subheadline.weight(.medium)).foregroundColor(Brand.textMuted)
    }
}
