import ContactsUI
import SwiftUI
import UIKit

/**
 The phone's own contact picker, for adding someone to split with. SwiftUI has
 none, so this wraps UIKit's. It runs out of the app's process and hands back
 only the one number tapped, so it needs no permission to read the contacts.

 Placed behind the screen (zero size) and presented from there when
 `isPresented` turns true: the picker must be presented modally, and inside a
 SwiftUI sheet of its own it dismisses itself at once.
 */
struct ContactPicker: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    /// The contact's name and the number tapped, as the phone has it.
    let onPicked: (String, String) -> Void

    func makeUIViewController(context: Context) -> UIViewController { UIViewController() }

    func updateUIViewController(_ host: UIViewController, context: Context) {
        context.coordinator.parent = self
        if !isPresented { context.coordinator.showing = false }
        if isPresented, host.presentedViewController == nil, !context.coordinator.showing {
            let picker = CNContactPickerViewController()
            picker.delegate = context.coordinator
            // Only people with a number, and only their numbers to tap.
            picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
            picker.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
            picker.predicateForSelectionOfContact = NSPredicate(format: "phoneNumbers.@count == 1")
            picker.predicateForSelectionOfProperty = NSPredicate(format: "key == 'phoneNumbers'")
            context.coordinator.showing = true
            // After this update pass: presenting during one is refused.
            DispatchQueue.main.async { host.present(picker, animated: true) }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, CNContactPickerDelegate {
        var parent: ContactPicker
        var showing = false

        init(_ parent: ContactPicker) { self.parent = parent }

        /// A contact with one number: that number.
        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            finish(contact, contact.phoneNumbers.first?.value)
        }

        /// A contact with several: the one tapped.
        func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            finish(contactProperty.contact, contactProperty.value as? CNPhoneNumber)
        }

        func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
            showing = false
            parent.isPresented = false
        }

        private func finish(_ contact: CNContact, _ number: CNPhoneNumber?) {
            showing = false
            parent.isPresented = false
            guard let number else { return }
            let name = CNContactFormatter.string(from: contact, style: .fullName) ?? ""
            parent.onPicked(name, number.stringValue)
        }
    }
}
