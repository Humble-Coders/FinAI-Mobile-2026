import SwiftUI
import UIKit
import SharedLogic

/**
 The budget's month-and-year calendar: the system's own year-and-month wheel
 (`UIDatePicker`, `.yearAndMonth`), in a sheet with Cancel and Done.

 Native, unlike Android's — SwiftUI's `DatePicker` always picks a day, but
 UIKit has had a month-only mode since iOS 17.4 (the app's floor is 17.6).
 Which months can be chosen is `MonthPicker`'s, shared with Android: none that
 has not begun, none before its earliest year.
 */
struct MonthYearPickerSheet: View {
    /// The month shown now, `YYYY-MM`.
    let selected: String
    let onPick: (String) -> Void
    let onCancel: () -> Void

    @State private var date: Date

    init(selected: String, onPick: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.selected = selected
        self.onPick = onPick
        self.onCancel = onCancel
        _date = State(initialValue: Self.date(fromWire: selected) ?? Date())
    }

    var body: some View {
        NavigationStack {
            YearAndMonthWheel(date: $date, minimum: Self.minimum, maximum: Date())
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
                .navigationTitle(L.t(Strings.shared.budget_month_pick_title))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L.t(Strings.shared.action_cancel), action: onCancel)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L.t(Strings.shared.action_done)) {
                            let parts = Calendar(identifier: .gregorian).dateComponents([.year, .month], from: date)
                            onPick(MonthPicker.shared.wire(year: Int32(parts.year ?? 0), month: Int32(parts.month ?? 1)))
                        }
                        .fontWeight(.semibold)
                    }
                }
        }
        .presentationDetents([.height(320)])
    }

    /// January of `MonthPicker`'s earliest year.
    private static var minimum: Date {
        let current = DashboardMonths.shared.current(timeZone: Kotlinx_datetimeTimeZone.companion.currentSystemDefault())
        let earliest = MonthPicker.shared.earliest(current: current)
        return Calendar(identifier: .gregorian).date(from: DateComponents(year: Int(earliest.year), month: 1, day: 1)) ?? Date()
    }

    private static func date(fromWire key: String) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count >= 2 else { return nil }
        return Calendar(identifier: .gregorian).date(from: DateComponents(year: parts[0], month: parts[1], day: 1))
    }
}

/// `UIDatePicker` in `.yearAndMonth` mode, bounded, as a SwiftUI view.
private struct YearAndMonthWheel: UIViewRepresentable {
    @Binding var date: Date
    let minimum: Date
    let maximum: Date

    func makeUIView(context: Context) -> UIDatePicker {
        let picker = UIDatePicker()
        picker.datePickerMode = .yearAndMonth
        picker.preferredDatePickerStyle = .wheels
        picker.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .valueChanged)
        return picker
    }

    func updateUIView(_ picker: UIDatePicker, context: Context) {
        picker.minimumDate = minimum
        picker.maximumDate = maximum
        if picker.date != date { picker.date = date }
    }

    func makeCoordinator() -> Coordinator { Coordinator(date: $date) }

    final class Coordinator: NSObject {
        let date: Binding<Date>
        init(date: Binding<Date>) { self.date = date }

        @objc func changed(_ picker: UIDatePicker) { date.wrappedValue = picker.date }
    }
}
