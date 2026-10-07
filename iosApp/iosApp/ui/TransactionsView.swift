import SharedLogic
import SwiftUI

/**
 Everything the household has, one month or one statement at a time (#F3), on
 home's green field so opening it from "View all" keeps one ground.

 The rows are a stack, as in Apple Wallet: each card overlaps the one before
 it, and a card that reaches the top stops there and stacks — a little smaller
 and a little higher with each card that arrives over it — rather than leaving
 the screen. Scrolling back unstacks them. Each card works out its own place
 from where the scroll view has put it (`visualEffect`), so scrolling changes
 no state. Mirrors Android's `TransactionsScreen`.

 Which filter each mode sends is `TransactionBrowsing`'s to decide; this view
 only draws the answer. Tapping a card opens the same editor the review queue
 uses.
 */
struct TransactionsView: View {
    @ObservedObject var model: TransactionsViewModel
    let userId: String
    let onClose: () -> Void
    /// False as a tab, where the bar is the way out and an arrow would point nowhere.
    var showsBack: Bool = true

    @Environment(\.colorScheme) private var scheme
    @State private var picking = false
    /// As a tab, how far the big title has shrunk into the pinned bar, as on
    /// Home. Opened from Home with a back arrow, that row is the header.
    @State private var collapse = HeaderCollapse()

    private static let space = "transactions"

    /// Where cards stop and stack, below the top of the scrolling area — and,
    /// as a tab, below the pinned bar: its 52pt less the 12pt the scrolling
    /// area starts below, and the same 22pt gap again.
    private var pin: CGFloat { showsBack ? 22 : 52 - 12 + 22 }

    /// How much of each card the next one covers: its bottom padding, never its words.
    nonisolated private static let overlap: CGFloat = 14

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
                if showsBack {
                    HStack {
                        Button(action: onClose) {
                            Image(systemName: "chevron.left")
                                .font(.body.weight(.semibold))
                                .foregroundColor(Field.ink())
                                .tappableArea()
                        }
                        .accessibilityLabel(L.t(Strings.shared.action_back))
                        Spacer()
                    }
                    .padding(.horizontal, 8)
                    .frame(height: 52)
                } else {
                    Spacer().frame(height: 12)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        top.padding(.bottom, 12)
                        if !model.loading && !model.loadFailed && !model.showsEmpty {
                            stack
                        }
                        bottom.padding(.top, 12)
                    }
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
                .coordinateSpace(name: Self.space)
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        // Pinned once the page has scrolled, as a tab: the screen's name.
        .overlay(alignment: .top) {
            if !showsBack {
                CollapsingBar(collapse: collapse, dark: dark) {
                    CompactTitle(title: L.t(Strings.shared.tab_transactions))
                }
            }
        }
        .onAppear { model.bind(userId: userId) }
        // Not on disappearing: switching tab fires that, and closing and
        // rebuilding this tab's clients on every switch was the lag coming
        // back to it. RootView unbinds the tabs when they are left for good.
        .sheet(isPresented: editingShown) { CorrectionSheet(model: model) }
        .sheet(isPresented: $picking) { pickerSheet }
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { model.editing != nil }, set: { if !$0 { model.cancelEdit() } })
    }

    // MARK: - Above the stack

    private var top: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L.t(Strings.shared.transactions_title))
                .font(.largeTitle.weight(.bold))
                .foregroundColor(Field.ink())
                .accessibilityAddTraits(.isHeader)
                .modifier(HeaderFade(collapse: collapse))
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .named(Self.space)).minY
                } action: { (top: CGFloat) in
                    collapse.track(top: top)
                }
            modeToggle
            // By category there is nothing to choose between: it is everything.
            if !model.browsingByCategory { sliceRow }
            totals
        }
    }

    /// The two ways of slicing, as a pill with the chosen half lit.
    private var modeToggle: some View {
        HStack(spacing: 0) {
            segment(L.t(Strings.shared.transactions_by_month), selected: model.mode == TransactionBrowsing.Mode.byMonth) {
                model.show(mode: TransactionBrowsing.Mode.byMonth)
            }
            segment(L.t(Strings.shared.transactions_by_statement), selected: model.browsingByStatement) {
                model.show(mode: TransactionBrowsing.Mode.byStatement)
            }
            segment(L.t(Strings.shared.transactions_by_category), selected: model.browsingByCategory) {
                model.show(mode: TransactionBrowsing.Mode.byCategory)
            }
        }
        .padding(4)
        .background(Capsule().fill(Field.glass))
        .overlay(Capsule().stroke(Field.glassEdge, lineWidth: 1))
    }

    private func segment(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(selected ? Brand.greenDeep : Field.ink(0.9))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 6)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Capsule().fill(selected ? Color.white : .clear))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// The months or statements to choose from, and the button that lists them all.
    private var sliceRow: some View {
        HStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if model.browsingByStatement {
                        ForEach(model.statements, id: \.id) { statement in
                            chip(model.statementChip(statement), selected: statement.id == model.statementId) {
                                model.show(statement: statement.id)
                            }
                        }
                    } else {
                        ForEach(model.months, id: \.self) { month in
                            chip(model.monthLabel(month), selected: month == model.month) {
                                model.show(month: month)
                            }
                        }
                    }
                }
            }
            Button { picking = true } label: {
                Image(systemName: "calendar")
                    .font(.title3)
                    .foregroundColor(Brand.greenDeep)
                    .frame(width: 48, height: 48)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white))
            }
            .accessibilityLabel(L.t(model.browsingByStatement
                ? Strings.shared.transactions_pick_statement
                : Strings.shared.transactions_pick_month))
        }
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(selected ? .semibold : .medium))
                .foregroundColor(Field.ink())
                .padding(.horizontal, 18)
                .frame(minHeight: 44)
                .background(Capsule().fill(selected ? Color(red: 0x0B / 255, green: 0x5E / 255, blue: 0x2E / 255) : Field.glass))
                .overlay(Capsule().stroke(selected ? Color.white.opacity(0.25) : Field.glassEdge, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Money in and money out for the slice, side by side. Absent, not "$0.00",
    /// for a slice with nothing in it: zero would be a claim about the
    /// statement rather than an absence of rows.
    private var totals: some View {
        HStack(spacing: 12) {
            total(
                L.t(Strings.shared.transactions_total_in), model.totalIn ?? "—",
                symbol: "arrow.up", accent: Brand.green,
                ink: Color(red: 0x15 / 255, green: 0x80 / 255, blue: 0x3D / 255)
            )
            total(
                L.t(Strings.shared.transactions_total_out), model.totalOut ?? "—",
                symbol: "arrow.down", accent: Brand.red,
                ink: Color(red: 0xB9 / 255, green: 0x1C / 255, blue: 0x1C / 255)
            )
        }
    }

    private func total(_ label: String, _ value: String, symbol: String, accent: Color, ink: Color) -> some View {
        let dark = scheme == .dark
        return HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(accent.opacity(0.18))
                Image(systemName: symbol).font(.body.weight(.semibold)).foregroundColor(dark ? accent : ink)
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.subheadline).foregroundColor(dark ? accent : ink)
                Text(value).font(.headline.weight(.bold)).foregroundColor(.primary).lineLimit(1).minimumScaleFactor(0.7)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(dark ? Brand.surface : .white)
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(accent.opacity(dark ? 0.14 : 0.10)))
        )
        .accessibilityElement(children: .combine)
    }

    // MARK: - The stack

    private var stack: some View {
        let sections = model.sections
        // A running number across the sections, so a later card is always in
        // front of an earlier one wherever the headings fall.
        var starts: [Int] = []
        var running = 0
        for section in sections {
            starts.append(running)
            running += section.rows.count
        }
        // Lazy: only the cards on or near the screen are built. A plain stack
        // laid out every card of the slice — each with its shadow and its
        // scroll effect — before the tab could appear.
        return LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(Array(sections.enumerated()), id: \.offset) { s, section in
                heading(section)
                ForEach(Array(section.rows.enumerated()), id: \.element.id) { i, row in
                    stackedCard(row, first: i == 0, order: starts[s] + i)
                }
            }
        }
    }

    /// A month, or a category with what went through it. It goes as the stack
    /// reaches it — the stacked card in front is about a card tall — rather
    /// than showing beneath it.
    private func heading(_ section: TransactionSection) -> some View {
        HStack {
            Text(section.title)
                .font(.headline.weight(.medium))
                .foregroundColor(Field.ink(0.92))
                .lineLimit(1)
            Spacer(minLength: 8)
            if let total = section.total {
                Text(total)
                    .font(.headline.weight(.semibold))
                    .foregroundColor(section.totalIsIn ? Color(red: 0x86 / 255, green: 0xEF / 255, blue: 0xAC / 255) : Field.ink())
            }
        }
            .padding(.horizontal, 4)
            .padding(.top, 16)
            .padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
            .visualEffect { content, proxy in
                let y: CGFloat = proxy.frame(in: .scrollView).minY
                let fade: CGFloat = min(max((y - 94) / 32, 0), 1)
                return content.opacity(Double(fade))
            }
            .zIndex(0)
    }

    private func stackedCard(_ row: SharedLogic.Transaction, first: Bool, order: Int) -> some View {
        card(row)
            .padding(.top, first ? 0 : -Self.overlap)
            .visualEffect { [pin] content, proxy in Self.stacked(content, proxy, pin: pin) }
            .zIndex(Double(1 + order))
    }

    /// Where a card sits once it has reached the top: stopped there, a little
    /// higher and smaller for each card that has arrived over it, and gone
    /// after three.
    private nonisolated static func stacked(_ content: EmptyVisualEffect, _ proxy: GeometryProxy, pin: CGFloat) -> some VisualEffect {
        let peek: CGFloat = 6
        let y = proxy.frame(in: .scrollView).minY
        let step = max(proxy.size.height - overlap, 1)
        let depth = max(0, (pin - y) / step)
        let shown: CGFloat = min(depth, 3)
        let pinned = y < pin
        let scale: CGFloat = pinned ? 1 - 0.04 * shown : 1
        let lift: CGFloat = pinned ? (pin - y) - peek * shown : 0
        let fade: Double = depth > 3 ? Double(max(0, 1 - (depth - 3))) : 1
        return content
            .scaleEffect(scale, anchor: .top)
            .offset(y: lift)
            .opacity(fade)
    }

    /// One transaction as a card; its bottom padding is what the next card covers.
    private func card(_ row: SharedLogic.Transaction) -> some View {
        let dark = scheme == .dark
        let filed = model.isFiled(row)
        let unfiledInk = Color(red: 0xB4 / 255, green: 0x53 / 255, blue: 0x09 / 255)
        return Button { model.edit(row.id) } label: {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill((filed ? Brand.green : Brand.amber).opacity(0.14))
                    Image(systemName: model.iconFor(row).systemName)
                        .font(.title3)
                        .foregroundColor(filed ? (dark ? Brand.green : Brand.greenDeep) : unfiledInk)
                }
                .frame(width: 48, height: 48)
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.dateLabel(row.occurredOn)).font(.caption).foregroundColor(Brand.textMuted)
                    Text(model.titleOf(row)).font(.headline.weight(.regular)).foregroundColor(.primary).lineLimit(1)
                    Text(model.categoryLabel(row))
                        .font(.subheadline)
                        .foregroundColor(filed ? Brand.textMuted : unfiledInk)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Text(model.amountLabel(row))
                    .font(.headline.weight(.semibold))
                    // Green for money in; money out stays plain, so the
                    // direction registers instead of every row shouting.
                    .foregroundColor(model.isCredit(row) ? (dark ? Brand.green : Brand.greenDeep) : .primary)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Brand.textMuted)
                    .accessibilityHidden(true)
            }
            .padding(.leading, 16)
            .padding(.trailing, 12)
            .padding(.top, 12)
            .padding(.bottom, 12 + Self.overlap)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(dark ? Brand.surface : .white)
                    .shadow(color: .black.opacity(0.22), radius: 10, y: -2)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(dark ? Color.white.opacity(0.06) : Color(red: 0xE8 / 255, green: 0xF1 / 255, blue: 0xEC / 255), lineWidth: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.rowDescription(row))
        .accessibilityHint(model.editLabel(row))
    }

    // MARK: - Below the stack

    /// Loading, a failure, an empty slice, or the next page — whichever applies.
    @ViewBuilder
    private var bottom: some View {
        if model.loading || model.loadingMore {
            ProgressView().tint(Field.ink()).frame(maxWidth: .infinity)
        } else if model.loadFailed {
            VStack(spacing: 10) {
                ErrorText(messageKey: model.errorKey)
                    .padding(16)
                    .frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Brand.ground))
                glassButton(L.t(Strings.shared.dashboard_retry)) { model.load() }
            }
        } else if model.showsEmpty {
            Text(model.emptyMessage)
                .font(.body)
                .foregroundColor(Field.ink(0.9))
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if model.canLoadMore {
            glassButton(L.t(Strings.shared.transactions_load_more)) { model.loadMore() }
        }
    }

    private func glassButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Field.ink())
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Field.glass))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Field.glassEdge, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    /// Every month, or every statement, to choose from when the chips run off the edge.
    private var pickerSheet: some View {
        NavigationStack {
            List {
                if model.browsingByStatement {
                    ForEach(model.statements, id: \.id) { statement in
                        ChoiceRow(title: model.statementLabel(statement), detail: nil, selected: statement.id == model.statementId) {
                            model.show(statement: statement.id)
                            picking = false
                        }
                    }
                } else {
                    ForEach(model.months, id: \.self) { month in
                        ChoiceRow(title: model.monthLabel(month), detail: nil, selected: month == model.month) {
                            model.show(month: month)
                            picking = false
                        }
                    }
                }
            }
            .navigationTitle(L.t(model.browsingByStatement
                ? Strings.shared.transactions_pick_statement
                : Strings.shared.transactions_pick_month))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { picking = false }
                }
            }
        }
    }
}
