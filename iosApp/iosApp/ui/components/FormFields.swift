import SwiftUI

// The form fields the setup wizard introduced, shared with every screen that
// takes figures or text the same way (manual entry, #30), so the boxes look and
// behave alike wherever money is typed.

extension SetupView {
    /// The corner radius every field and list row shares.
    static let fieldRadius: CGFloat = 16
}

extension View {
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
struct AmountField: View {
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

/// A plain text field in the same box as the amounts, red while its text will not do.
struct WizardField: View {
    let label: String
    var placeholder: String = ""
    var keyboard: UIKeyboardType = .default
    /// Names want words capitalised; a rate does not.
    var autocapitalization: TextInputAutocapitalization = .never
    var isError = false
    var submitLabel: SubmitLabel = .return
    var onSubmit: () -> Void = {}
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: label)
            TextField("", text: $text, prompt: Text(placeholder).foregroundColor(Brand.textMuted.opacity(0.6)))
                .font(.body)
                .keyboardType(keyboard)
                .textInputAutocapitalization(autocapitalization)
                .autocorrectionDisabled()
                .submitLabel(submitLabel)
                .onSubmit(onSubmit)
                .focused($focused)
                .tint(Brand.green)
                .accessibilityLabel(label)
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .fieldFrame(focused: focused, isError: isError)
                .contentShape(RoundedRectangle(cornerRadius: SetupView.fieldRadius))
                .onTapGesture { focused = true }
        }
    }
}

struct FieldLabel: View {
    let text: String

    var body: some View {
        Text(text).font(.subheadline.weight(.medium)).foregroundColor(Brand.textMuted)
    }
}

/// A field that opens a chooser rather than taking typing — an account, a date,
/// a category — in the same box as the typed ones. Empty, it shows a faint
/// placeholder, never a value nobody picked.
struct PickerField: View {
    let label: String
    let value: String?
    let placeholder: String
    var isError = false
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FieldLabel(text: label)
            Button(action: action) {
                HStack {
                    Text(value ?? placeholder)
                        .font(.body)
                        .foregroundColor(value == nil ? Brand.textMuted.opacity(0.6) : .primary)
                        .lineLimit(1)
                        .truncationMode(.tail)
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
