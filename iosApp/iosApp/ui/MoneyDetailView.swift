import SharedLogic
import SwiftUI

/**
 Income, Expenses, Investments or Debts: one month of one kind of money, from
 Home's four cards. One frame for all four, washed in the card's colour; each
 adds its own sections. Every figure is the server's — the screen only places
 them, by the shared `MoneyDetail`. Mirrors Android's `MoneyDetailScreen`.
 */
struct MoneyDetailView: View {
    @ObservedObject var model: MoneyDetailViewModel
    @ObservedObject var entry: ManualEntryViewModel
    let userId: String
    let kind: MoneyKind
    let onBack: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var adding = false
    @State private var markObligation = false
    private var dark: Bool { scheme == .dark }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            backdrop
            VStack(spacing: 0) {
                topBar
                if model.loadFailed {
                    loadFailed
                } else if !model.loading {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            title
                            summaryCard
                            sections
                            Spacer().frame(height: 96)
                        }
                        .frame(maxWidth: 560, alignment: .leading)
                        .padding(.horizontal, 20)
                        .frame(maxWidth: .infinity)
                    }
                    .refreshable { model.load(refresh: true) }
                } else {
                    Spacer()
                }
            }
            if !model.loading && !model.loadFailed && kind != .investments {
                Button(action: openEntry) {
                    Image(systemName: "plus")
                        .font(.title2.weight(.semibold))
                        .foregroundColor(.white)
                        .frame(width: 64, height: 64)
                        .background(Circle().fill(Brand.green))
                        .shadow(color: .black.opacity(0.18), radius: 8, y: 4)
                }
                .accessibilityLabel(L.t(Strings.shared.money_add_transaction))
                .padding(20)
            }
        }
        .onAppear { model.bind(userId: userId, kind: kind) }
        .onDisappear { model.unbind() }
        .sheet(isPresented: $adding, onDismiss: { entry.discard() }) {
            AddMoneySheet(
                entry: entry,
                investment: kind == .investments,
                markObligation: $markObligation,
                onClose: { adding = false }
            )
        }
        .onChange(of: entry.saved) { _, saved in
            guard saved != nil, adding else { return }
            let draft = entry.draft
            if markObligation, draft.direction == .debit {
                model.markAsObligation(
                    name: draft.description_.trimmingCharacters(in: .whitespaces),
                    amount: draft.amount,
                    dueDay: draft.occurredOn.map { Int($0.day) }
                )
            }
            entry.discard()
            adding = false
        }
        // The entry's categories arrive after it opens; the screen's own category
        // is chosen once they are there.
        .onChange(of: entry.loading) { _, loading in
            if !loading && adding { preset() }
        }
        .sheet(isPresented: obligationShown) { ObligationSheet(model: model) }
        .alert(
            model.noticeKey.map { L.t($0) } ?? "",
            isPresented: Binding(get: { model.noticeKey != nil }, set: { if !$0 { model.noticeKey = nil } })
        ) {
            Button(L.t(Strings.shared.action_done)) { model.noticeKey = nil }
        }
    }

    private var obligationShown: Binding<Bool> {
        Binding(get: { model.obligationDraft != nil }, set: { if !$0 { model.cancelObligation() } })
    }

    /// The entry, preset to this screen's own kind of money — shown chosen,
    /// one tap to change — and, on Debts and Investments, its category.
    private func openEntry() {
        entry.discard()
        markObligation = false
        entry.bind(userId: userId, restoring: "")
        adding = true
        preset()
    }

    private func preset() {
        if entry.draft.direction == nil {
            entry.chooseDirection(kind == .income ? .credit : .debit)
        }
        let slug: String? = switch kind {
        case .investments: MoneyDetail.shared.SAVINGS_SLUG
        case .debts: MoneyDetail.shared.DEBT_PAYMENT_SLUG
        default: nil
        }
        if let slug, entry.draft.categoryId == nil, let category = entry.categories.first(where: { $0.slug == slug }) {
            entry.chooseCategory(category.id)
        }
    }

    // MARK: - Frame

    private var accent: MoneyAccent { MoneyAccent(kind: kind) }

    private var backdrop: some View {
        ZStack(alignment: .topTrailing) {
            LinearGradient(
                colors: [accent.wash(dark), Brand.ground, Brand.ground],
                startPoint: .top,
                endPoint: .bottom
            )
            Circle().fill(accent.color.opacity(0.08)).frame(width: 240, height: 240).offset(x: 60, y: -60)
            Circle().fill(accent.color.opacity(0.06)).frame(width: 140, height: 140).offset(x: -80, y: 90)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left").font(.body.weight(.semibold)).foregroundColor(.primary).tappableArea()
            }
            .accessibilityLabel(L.t(Strings.shared.action_back))
            Spacer()
        }
        .padding(.horizontal, 8)
        .frame(height: 52)
    }

    private var title: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L.t(model.titleKey)).font(.largeTitle.weight(.bold)).accessibilityAddTraits(.isHeader)
            Menu {
                ForEach(model.months, id: \.self) { month in
                    Button(model.monthName(month)) { model.show(month: month) }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(model.monthLabel).font(.headline.weight(.regular))
                    Image(systemName: "chevron.down").font(.footnote.weight(.semibold))
                }
                .foregroundColor(.primary)
                .frame(minHeight: 44)
            }
            .accessibilityLabel(L.t(Strings.shared.money_choose_month))
            .accessibilityValue(model.monthLabel)
        }
    }

    private var summaryCard: some View {
        HStack(spacing: 16) {
            IconTile(symbol: accent.symbol, accent: accent.color, size: 64, iconSize: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(L.t(model.headlineLabelKey)).font(.subheadline).foregroundColor(Brand.textMuted)
                Text(model.headline)
                    .font(.title.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                changeRow
                if let paid = model.paidThisMonth {
                    Text(paid).font(.subheadline).foregroundColor(accent.label(dark))
                }
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .moneyCard(dark)
    }

    @ViewBuilder
    private var changeRow: some View {
        if let label = model.changeLabel {
            let colour = model.changeIsGood ? MoneyAccent.good(dark) : MoneyAccent.bad(dark)
            HStack(spacing: 4) {
                Image(systemName: model.change?.rose == true ? "arrow.up" : "arrow.down").font(.caption.weight(.bold))
                Text(label).font(.subheadline.weight(.semibold))
                Text(L.t(Strings.shared.money_vs_last_month)).font(.subheadline).foregroundColor(Brand.textMuted)
            }
            .foregroundColor(colour)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(model.changeDescription ?? "")
        }
    }

    @ViewBuilder
    private var sections: some View {
        switch kind {
        case .expenses: expensesBody
        case .income:
            trendCard
            transactions
        case .investments: investmentsBody
        case .debts: debtsBody
        }
    }

    private var loadFailed: some View {
        VStack(spacing: 16) {
            Spacer()
            Text(L.t(Strings.shared.money_load_failed))
            if let key = model.errorKey, key != Strings.shared.error_network { ErrorText(messageKey: key) }
            GradientButton(title: L.t(Strings.shared.dashboard_retry)) { model.load() }
            Spacer()
        }
        .padding(24)
    }

    // MARK: - Trend

    @ViewBuilder
    private var trendCard: some View {
        let bars = model.bars
        if bars.contains(where: { $0.value != nil }) {
            VStack(alignment: .leading, spacing: 12) {
                Text(L.t(Strings.shared.money_monthly_trend)).font(.headline).accessibilityAddTraits(.isHeader)
                HStack(alignment: .bottom, spacing: 10) {
                    ForEach(Array(bars.enumerated()), id: \.offset) { _, bar in
                        barColumn(bar)
                    }
                }
                .frame(height: 150)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(model.trendDescription)
            }
            .padding(16)
            .moneyCard(dark)
        }
    }

    private func barColumn(_ bar: MoneyDetail.Bar) -> some View {
        VStack(spacing: 4) {
            if bar.isCurrent, let value = model.barValue(bar) {
                Text(value)
                    .font(.caption2.weight(.bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: 8).fill(accent.color))
            }
            GeometryReader { proxy in
                VStack {
                    Spacer(minLength: 0)
                    // A month with nothing recorded is no bar at all, not a zero-height one.
                    if bar.value != nil {
                        UnevenRoundedRectangle(topLeadingRadius: 6, topTrailingRadius: 6)
                            .fill(bar.isCurrent ? accent.color : accent.color.opacity(dark ? 0.35 : 0.25))
                            .frame(height: max(6, proxy.size.height * CGFloat(bar.height)))
                            .padding(.horizontal, proxy.size.width * 0.14)
                    }
                }
            }
            Text(model.barMonth(bar)).font(.caption2).foregroundColor(Brand.textMuted).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Transactions

    private var transactions: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.searching {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundColor(Brand.textMuted)
                    TextField(L.t(Strings.shared.money_search_hint), text: $model.query)
                        .textInputAutocapitalization(.never)
                        .submitLabel(.search)
                    Button { model.closeSearch() } label: {
                        Image(systemName: "xmark.circle.fill").foregroundColor(Brand.textMuted).tappableArea()
                    }
                    .accessibilityLabel(L.t(Strings.shared.money_close_search))
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .moneyCard(dark)
            } else {
                sectionHeader(L.t(model.listTitleKey)) {
                    CircleButton(symbol: "magnifyingglass", label: L.t(Strings.shared.money_search)) { model.openSearch() }
                }
            }
            let days = model.days
            if days.isEmpty {
                Text(model.emptyMessage).font(.subheadline).foregroundColor(Brand.textMuted)
            }
            ForEach(days, id: \.date) { day in
                Text(model.dayLabel(day)).font(.subheadline).foregroundColor(Brand.textMuted).padding(.top, 4)
                ForEach(day.rows, id: \.id) { row in transactionRow(row) }
            }
            if model.nextCursor != nil && model.query.isEmpty {
                Button(L.t(Strings.shared.transactions_load_more)) { model.loadMore() }
                    .disabled(model.loadingMore)
                    .frame(maxWidth: .infinity)
                    .tappableArea()
            }
        }
    }

    private func transactionRow(_ row: SharedLogic.Transaction) -> some View {
        let icon = model.iconFor(row)
        let title = model.titleOf(row)
        let category = model.categoryLabel(row)
        let amount = model.amountLabel(row)
        return MoneyRow(
            symbol: icon.systemName,
            tint: icon.tint(dark),
            title: title,
            detail: category,
            description: "\(title), \(category), \(amount)"
        ) {
            Text(amount)
                .font(.headline.weight(.bold))
                .lineLimit(1)
                .foregroundColor(model.isCredit(row) ? MoneyAccent.good(dark) : .primary)
        }
    }

    private func sectionHeader<End: View>(_ text: String, @ViewBuilder end: () -> End) -> some View {
        HStack {
            Text(text).font(.headline.weight(.bold)).lineLimit(1).accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            end()
        }
        .frame(minHeight: 40)
    }

    // MARK: - Expenses

    private var expensesBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 0) {
                tabButton(.transactions, Strings.shared.money_tab_transactions)
                tabButton(.obligations, Strings.shared.money_tab_obligations)
            }
            if model.tab == .obligations { obligations }
            transactions
        }
    }

    private func tabButton(_ tab: ExpensesTab, _ key: String) -> some View {
        let selected = model.tab == tab
        return Button { model.tab = tab } label: {
            VStack(spacing: 10) {
                Text(L.t(key))
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .foregroundColor(selected ? accent.label(dark) : Brand.textMuted)
                Rectangle()
                    .fill(selected ? accent.color : Brand.border)
                    .frame(height: selected ? 2 : 1)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var obligations: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader(L.t(Strings.shared.money_monthly_obligations)) {
                if !model.commitments.isEmpty {
                    Text(model.metLabel).font(.subheadline).foregroundColor(Brand.textMuted)
                }
            }
            if model.commitments.isEmpty {
                Text(L.t(Strings.shared.money_no_obligations)).font(.subheadline).foregroundColor(Brand.textMuted)
            }
            ForEach(Array(model.commitments.enumerated()), id: \.offset) { _, item in
                obligationRow(item)
            }
            Button { model.openObligation() } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus").font(.subheadline.weight(.semibold))
                    Text(L.t(Strings.shared.money_add_obligation)).font(.subheadline.weight(.semibold))
                }
                .foregroundColor(accent.label(dark))
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(accent.color.opacity(dark ? 0.16 : 0.10)))
            }
            .buttonStyle(.plain)
        }
    }

    private func obligationRow(_ item: Commitment) -> some View {
        let icon = MoneyDetail.shared.iconFor(name: item.name)
        let due = model.dueLabel(item.dueDay)
        let amount = model.amount(item.expected)
        let status = L.t(item.wasSeen ? Strings.shared.money_met : Strings.shared.money_not_seen)
        return MoneyRow(
            symbol: icon.systemName,
            tint: icon.tint(dark),
            title: item.name,
            detail: due,
            description: "\(item.name), \(amount), \(due), \(status)"
        ) {
            VStack(alignment: .trailing, spacing: 4) {
                Text(amount).font(.subheadline.weight(.bold)).lineLimit(1)
                StatusPill(text: status, seen: item.wasSeen, dark: dark)
            }
        }
    }

    // MARK: - Debts

    private var debtsBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(L.t(Strings.shared.money_my_debts)) { EmptyView() }
                if model.debts.isEmpty {
                    Text(L.t(Strings.shared.money_no_debts)).font(.subheadline).foregroundColor(Brand.textMuted)
                }
                ForEach(Array(model.debts.enumerated()), id: \.offset) { _, debt in
                    let icon = MoneyDetail.shared.iconFor(name: debt.name)
                    let balance = model.amount(debt.balance)
                    let detail = model.debtDetail(debt)
                    MoneyRow(
                        symbol: icon.systemName,
                        tint: icon.tint(dark),
                        title: debt.name,
                        detail: detail,
                        description: [debt.name, balance, detail].compactMap { $0 }.joined(separator: ", ")
                    ) {
                        Text(balance).font(.subheadline.weight(.bold)).lineLimit(1)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(L.t(Strings.shared.money_upcoming_payments)) { EmptyView() }
                let upcoming = model.upcoming
                if upcoming.isEmpty {
                    Text(L.t(Strings.shared.money_no_upcoming)).font(.subheadline).foregroundColor(Brand.textMuted)
                }
                ForEach(Array(upcoming.enumerated()), id: \.offset) { _, item in
                    let amount = model.amount(item.amount)
                    let due = model.upcomingDue(item)
                    MoneyRow(symbol: "calendar", tint: Brand.blue, title: item.name, detail: due, description: "\(item.name), \(amount), \(due)") {
                        Text(amount).font(.subheadline.weight(.bold)).lineLimit(1)
                    }
                }
            }
            transactions
        }
    }

    // MARK: - Investments

    private var investmentsBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            trendCard
            HStack(spacing: 12) {
                MiniFigure(symbol: "arrow.up", tint: MoneyAccent.good(dark), label: L.t(Strings.shared.money_invested), value: model.invested, dark: dark)
                MiniFigure(symbol: "arrow.down", tint: MoneyAccent.bad(dark), label: L.t(Strings.shared.money_withdrawn), value: model.withdrawn, dark: dark)
            }
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(L.t(Strings.shared.money_your_investments)) { EmptyView() }
                if model.shares.isEmpty {
                    Text(L.t(Strings.shared.money_no_investments)).font(.subheadline).foregroundColor(Brand.textMuted)
                }
                ForEach(Array(model.shares.enumerated()), id: \.offset) { _, share in
                    let amount = model.amount(share.amount)
                    let percent = model.shareLabel(share)
                    MoneyRow(
                        symbol: MoneyDetail.shared.iconFor(name: share.name).systemName,
                        tint: accent.color,
                        title: share.name,
                        detail: nil,
                        description: "\(share.name), \(amount), \(percent)"
                    ) {
                        VStack(alignment: .trailing) {
                            Text(amount).font(.subheadline.weight(.bold)).lineLimit(1)
                            Text(percent).font(.caption).foregroundColor(Brand.textMuted)
                        }
                    }
                }
                Text(L.t(Strings.shared.money_holdings_later)).font(.caption).foregroundColor(Brand.textMuted)
            }
            Button(action: openEntry) {
                HStack(spacing: 8) {
                    Image(systemName: "plus").font(.headline)
                    Text(L.t(Strings.shared.money_add_investment)).font(.headline)
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(accent.color))
            }
            .buttonStyle(.plain)
            transactions
        }
    }
}

// MARK: - Pieces

/// One screen's colour: the card's accent on Home, and the wash behind it.
struct MoneyAccent {
    let color: Color
    let text: Color
    let darkText: Color
    let lightWash: Color
    let symbol: String

    init(kind: MoneyKind) {
        switch kind {
        case .income:
            color = Brand.green; text = Brand.greenDeep; darkText = Brand.green
            lightWash = Color(red: 0xE3 / 255, green: 0xF5 / 255, blue: 0xEA / 255); symbol = "wallet.bifold.fill"
        case .expenses:
            color = Brand.red; text = Color(red: 0xDC / 255, green: 0x26 / 255, blue: 0x26 / 255)
            darkText = Color(red: 0xF8 / 255, green: 0x71 / 255, blue: 0x71 / 255)
            lightWash = Color(red: 0xFD / 255, green: 0xE8 / 255, blue: 0xE6 / 255); symbol = "creditcard.fill"
        case .investments:
            color = Brand.blue; text = Color(red: 0x25 / 255, green: 0x63 / 255, blue: 0xEB / 255)
            darkText = Color(red: 0x60 / 255, green: 0xA5 / 255, blue: 0xFA / 255)
            lightWash = Color(red: 0xE5 / 255, green: 0xEE / 255, blue: 0xFD / 255); symbol = "dollarsign.circle.fill"
        case .debts:
            color = Brand.amber; text = Color(red: 0xB4 / 255, green: 0x53 / 255, blue: 0x09 / 255); darkText = Brand.amber
            lightWash = Color(red: 0xFE / 255, green: 0xF1 / 255, blue: 0xDC / 255); symbol = "doc.text.fill"
        }
    }

    func wash(_ dark: Bool) -> Color { dark ? color.opacity(0.16) : lightWash }
    func label(_ dark: Bool) -> Color { dark ? darkText : text }

    static func good(_ dark: Bool) -> Color { dark ? Brand.green : Brand.greenDeep }
    static func bad(_ dark: Bool) -> Color {
        dark ? Color(red: 0xF8 / 255, green: 0x71 / 255, blue: 0x71 / 255) : Color(red: 0xDC / 255, green: 0x26 / 255, blue: 0x26 / 255)
    }
}

extension View {
    /// A white card, as every section of the money screens sits on.
    func moneyCard(_ dark: Bool) -> some View {
        background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Mint.panel(dark)))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Mint.edge(dark), lineWidth: 1))
    }
}

/// A row on a white card: tile, title and line under it, and whatever goes on the right.
struct MoneyRow<Trailing: View>: View {
    let symbol: String
    let tint: Color
    let title: String
    let detail: String?
    let description: String
    @ViewBuilder let trailing: () -> Trailing
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 12) {
            IconTile(symbol: symbol, accent: tint, size: 44, iconSize: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                if let detail {
                    Text(detail).font(.caption).foregroundColor(Brand.textMuted).lineLimit(1).truncationMode(.tail)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 68)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mint.panel(scheme == .dark)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Mint.edge(scheme == .dark), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}

/// "Met" in green, "Not seen" quietly — never red: not seen is not unpaid.
private struct StatusPill: View {
    let text: String
    let seen: Bool
    let dark: Bool

    var body: some View {
        let colour = seen ? MoneyAccent.good(dark) : Brand.textMuted
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundColor(colour)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(RoundedRectangle(cornerRadius: 10).fill(colour.opacity(0.12)))
    }
}

private struct CircleButton: View {
    let symbol: String
    let label: String
    let action: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.primary)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Mint.panel(scheme == .dark)))
                .overlay(Circle().stroke(Mint.edge(scheme == .dark), lineWidth: 1))
                .tappableArea(minWidth: 48, minHeight: 48)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct MiniFigure: View {
    let symbol: String
    let tint: Color
    let label: String
    let value: String
    let dark: Bool

    var body: some View {
        HStack(spacing: 10) {
            IconTile(symbol: symbol, accent: tint, size: 40, iconSize: 18)
            VStack(alignment: .leading, spacing: 0) {
                Text(label).font(.caption).foregroundColor(Brand.textMuted).lineLimit(1)
                Text(value).font(.headline.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .moneyCard(dark)
        .accessibilityElement(children: .combine)
    }
}

/// Adding an obligation: name, monthly amount, and the day it falls due.
private struct ObligationSheet: View {
    @ObservedObject var model: MoneyDetailViewModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    let notice = model.obligationNotice
                    WizardField(
                        label: L.t(Strings.shared.money_name_label),
                        placeholder: L.t(Strings.shared.money_merchant_hint),
                        autocapitalization: .words,
                        isError: notice == .noName || notice == .nameTooLong,
                        text: Binding(get: { model.obligationDraft?.name ?? "" }, set: { model.setObligation(name: $0) })
                    )
                    AmountField(
                        label: L.t(Strings.shared.money_amount_monthly),
                        symbol: model.currencySymbol,
                        placeholder: model.amountPlaceholder,
                        isError: [.noAmount, .amountNotMoney, .amountZero].contains { $0 == notice },
                        large: false,
                        text: Binding(get: { model.obligationDraft?.amount ?? "" }, set: { model.setObligation(amount: $0) })
                    )
                    WizardField(
                        label: L.t(Strings.shared.money_due_day_label),
                        placeholder: L.t(Strings.shared.money_due_day_hint),
                        keyboard: .numberPad,
                        isError: notice == .dueDayInvalid,
                        text: Binding(get: { model.obligationDraft?.dueDay ?? "" }, set: { model.setObligation(dueDay: $0) })
                    )
                    ErrorText(messageKey: model.obligationErrorKey ?? notice?.messageKey)
                    GradientButton(
                        title: L.t(Strings.shared.money_obligation_save),
                        enabled: model.canSaveObligation,
                        busy: model.savingObligation
                    ) { model.saveObligation() }
                }
                .padding(24)
            }
            .navigationTitle(L.t(Strings.shared.money_add_obligation))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t(Strings.shared.action_cancel)) { model.cancelObligation() }.disabled(model.savingObligation)
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L.t(Strings.shared.action_done)) { dismissKeyboard() }
                }
            }
        }
        .interactiveDismissDisabled(model.savingObligation)
    }
}
