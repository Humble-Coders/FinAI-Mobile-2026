import SharedLogic
import SwiftUI

/**
 Everything the household has, one statement or one month at a time (#F3).

 The two modes exist because people hold two different things in mind: a
 statement they are checking against the paper, and a month they are reasoning
 about. Which filter each sends is `TransactionBrowsing`'s to decide, not this
 view's.
 */
struct TransactionsView: View {
    @ObservedObject var model: TransactionsViewModel
    let userId: String
    let onClose: () -> Void

    var body: some View {
        ScreenScaffold(alignment: .leading) {
            Text(L.t(Strings.shared.transactions_title)).font(.title3.weight(.bold))

            modeToggle.padding(.top, 14)
            slicePicker.padding(.top, 10)

            Group {
                if model.loading {
                    ProgressView().frame(maxWidth: .infinity)
                } else if model.loadFailed {
                    failed
                } else if model.showsEmpty {
                    Text(model.emptyMessage).font(.subheadline).foregroundColor(Brand.textMuted)
                } else {
                    rows
                }
            }
            .padding(.top, 12)

            Button(action: onClose) {
                Text(L.t(Strings.shared.transactions_close)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
            .padding(.top, 20)
        }
        .onAppear { model.bind(userId: userId) }
        .onDisappear { model.unbind() }
        .sheet(isPresented: editingShown) { CorrectionSheet(model: model) }
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { model.editing != nil }, set: { if !$0 { model.cancelEdit() } })
    }

    private var modeToggle: some View {
        HStack(spacing: 4) {
            segment(L.t(Strings.shared.transactions_by_month), selected: !model.browsingByStatement) {
                model.show(mode: TransactionBrowsing.Mode.byMonth)
            }
            segment(L.t(Strings.shared.transactions_by_statement), selected: model.browsingByStatement) {
                model.show(mode: TransactionBrowsing.Mode.byStatement)
            }
        }
        .padding(4)
        .background(Brand.surfaceField)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func segment(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .foregroundColor(selected ? .primary : Brand.textMuted)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(selected ? Brand.surface : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    /// Which statement, or which month — whichever the mode is asking for.
    private var slicePicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if model.browsingByStatement {
                    ForEach(model.statements, id: \.id) { statement in
                        chip(model.statementLabel(statement), selected: statement.id == model.statementId) {
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
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption.weight(.medium))
                .foregroundColor(selected ? Brand.onGreen : Brand.textMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .frame(minHeight: 36)
                .background(selected ? Brand.green : Brand.surfaceField)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            totals
            ForEach(model.days, id: \.date) { day in
                Text(model.dateLabel(day.date))
                    .font(.caption)
                    .foregroundColor(Brand.textMuted)
                    .padding(.top, 12)
                    .padding(.bottom, 4)
                VStack(spacing: 0) {
                    ForEach(Array(day.rows.enumerated()), id: \.element.id) { index, row in
                        // Any row can be fixed from here, not only the ones the
                        // review queue holds: a wrong category on a confidently
                        // filed row is just as wrong.
                        Button { model.edit(row.id) } label: { transactionRow(row) }
                            .buttonStyle(.plain)
                            .accessibilityHint(model.editLabel(row))
                        if index != day.rows.count - 1 { Divider().overlay(Brand.border) }
                    }
                }
                .background(Brand.surface)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }

            if model.canLoadMore {
                Button { model.loadMore() } label: {
                    Text(L.t(Strings.shared.transactions_load_more)).font(.headline).tappableRow(minHeight: 52)
                }
                .buttonStyle(.bordered)
                .tint(.primary)
                .padding(.top, 12)
            }
            if model.loadingMore {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 12)
            }
        }
    }

    @ViewBuilder
    private var totals: some View {
        HStack(spacing: 6) {
            if let out = model.totalOut { totalChip(L.t(Strings.shared.import_extracted_out, out)) }
            if let money = model.totalIn { totalChip(L.t(Strings.shared.import_extracted_in, money)) }
        }
    }

    private func totalChip(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundColor(Brand.textMuted)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Brand.surfaceField)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private func transactionRow(_ row: SharedLogic.Transaction) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.titleOf(row)).font(.body).lineLimit(1).truncationMode(.tail)
                Text(model.categoryLabel(row))
                    .font(.caption2)
                    .foregroundColor(model.isFiled(row) ? Brand.textMuted : Brand.amber)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(model.isFiled(row) ? Brand.surfaceField : Brand.amber.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            }
            Spacer()
            Text(model.amountLabel(row))
                .font(.body.weight(.semibold))
                // Green for money in; money out stays plain, so the direction
                // registers instead of every row shouting.
                .foregroundColor(row.direction == .credit ? Brand.green : .primary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.rowDescription(row))
    }

    private var failed: some View {
        VStack(spacing: 8) {
            ErrorText(messageKey: model.errorKey)
            Button { model.load() } label: {
                Text(L.t(Strings.shared.dashboard_retry)).font(.headline).tappableRow(minHeight: 52)
            }
            .buttonStyle(.bordered)
            .tint(.primary)
        }
    }
}
