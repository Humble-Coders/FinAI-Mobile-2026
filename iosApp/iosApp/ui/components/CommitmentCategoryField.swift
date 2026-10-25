import SwiftUI
import SharedLogic

/**
 The budget category a commitment belongs to: its line never allocates less
 than it. Shared by setup's commitments, Home's editor and the Expenses sheet.
 Dressed as `PickerField`, opening a menu of the spending categories.
 */
struct CommitmentCategoryField: View {
    let selected: String
    var isError = false
    let onPick: (String) -> Void

    private var value: String? {
        CommitmentCategories.shared.isCategory(slug: selected)
            ? CommitmentCategories.shared.nameOf(slug: selected, locale: "en")
            : nil
    }

    var body: some View {
        let label = L.t(Strings.shared.setup_commitment_category)
        let placeholder = L.t(Strings.shared.setup_commitment_category_pick)
        VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: label)
            Menu {
                ForEach(CommitmentCategories.shared.SLUGS, id: \.self) { slug in
                    Button(CommitmentCategories.shared.nameOf(slug: slug, locale: "en")) { onPick(slug) }
                }
            } label: {
                HStack(spacing: 12) {
                    Text(value ?? placeholder)
                        .font(.body)
                        .foregroundColor(value == nil ? Brand.textMuted.opacity(0.6) : .primary)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Brand.textMuted)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .fieldFrame(focused: false, isError: isError)
                .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
            .accessibilityValue(value ?? placeholder)
        }
    }
}
