import SwiftUI
import SharedLogic

/**
 "Still learning your patterns" — what stands in for a budget or a score
 before there is enough history to compute one (PRD F8).

 Shared between the Budget tab (#47) and Home (#46) rather than written
 twice: a person who meets this card in two places should meet the same card,
 with the same numbers and the same way out of it. Android's `LearningCard`
 is the same card.

 The progress comes from the server, which owns the threshold. Nothing here
 decides whether the household is ready — `LearningProgress.ready` is read,
 never derived from the counts beside it.
 */
struct LearningFieldCard: View {

    let progress: LearningProgress
    var onImport: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L.t(Strings.shared.learning_title))
                .font(.headline)
                .foregroundColor(Field.ink())
                .accessibilityAddTraits(.isHeader)
            Text(L.t(Strings.shared.learning_body))
                .font(.subheadline)
                .foregroundColor(Field.ink(0.78))
            Text(monthsLabel)
                .font(.subheadline.weight(.medium))
                .foregroundColor(Field.ink(0.9))
            Text(
                L.t(
                    Strings.shared.learning_transactions,
                    String(progress.transactions),
                    String(progress.needs.transactions)
                )
            )
            .font(.subheadline.weight(.medium))
            .foregroundColor(Field.ink(0.9))

            if let onImport {
                Button(L.t(Strings.shared.learning_import), action: onImport)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Field.ink())
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Field.glass))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Field.glassEdge, lineWidth: 1))
    }

    /**
     "1 of 1 full month" rather than "1 of 1 full months".

     The threshold is one month today, so the plural form would be wrong every
     time it was shown; both forms exist because the server owns the number
     and can raise it without an app release.
     */
    private var monthsLabel: String {
        let have = String(progress.completeMonths)
        let need = progress.needs.completeMonths
        return need == 1
            ? L.t(Strings.shared.learning_months_one, have)
            : L.t(Strings.shared.learning_months, have, String(need))
    }
}

/// The same card, written on a white sheet rather than on the green field.
struct LearningCard: View {

    let progress: LearningProgress
    var onImport: (() -> Void)?

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        return VStack(alignment: .leading, spacing: 10) {
            Text(L.t(Strings.shared.learning_title))
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text(L.t(Strings.shared.learning_body))
                .font(.subheadline)
                .foregroundColor(.secondary)

            meter(label: monthsLabel, fraction: fraction(progress.completeMonths, progress.needs.completeMonths))
            meter(
                label: L.t(
                    Strings.shared.learning_transactions,
                    String(progress.transactions),
                    String(progress.needs.transactions)
                ),
                fraction: fraction(progress.transactions, progress.needs.transactions)
            )

            if let onImport {
                Button(L.t(Strings.shared.learning_import), action: onImport)
                    .font(.subheadline.weight(.semibold))
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Mint.card(dark)))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Mint.edge(dark), lineWidth: 1))
    }

    private func meter(label: String, fraction: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.subheadline.weight(.medium))
            ProgressView(value: fraction)
        }
        // One sentence for VoiceOver: the bar repeats the label.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private var monthsLabel: String {
        let have = String(progress.completeMonths)
        let need = progress.needs.completeMonths
        return need == 1
            ? L.t(Strings.shared.learning_months_one, have)
            : L.t(Strings.shared.learning_months, have, String(need))
    }

    /// A bar's length. A threshold of zero reads as met rather than dividing by it.
    private func fraction(_ have: Int32, _ need: Int32) -> Double {
        need <= 0 ? 1 : min(max(Double(have) / Double(need), 0), 1)
    }
}
