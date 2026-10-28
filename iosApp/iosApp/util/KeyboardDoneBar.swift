import UIKit
import SharedLogic

/**
 A Done bar above every keyboard in the app.

 Number and amount keyboards have no return key, so without it there is no way
 out of one but tapping somewhere else. Installed once, for every text field
 and text view as it starts editing — sheets and full-screen covers included,
 which a SwiftUI keyboard toolbar would have to be added to one by one.
 */
enum KeyboardDoneBar {

    private static var observers: [NSObjectProtocol] = []

    @MainActor static func install() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UITextField.textDidBeginEditingNotification, object: nil, queue: .main) { note in
            MainActor.assumeIsolated { attach(to: note.object as? UITextField) }
        })
        observers.append(center.addObserver(forName: UITextView.textDidBeginEditingNotification, object: nil, queue: .main) { note in
            MainActor.assumeIsolated { attach(to: note.object as? UITextView) }
        })
    }

    @MainActor private static func attach(to field: UITextField?) {
        guard let field, field.inputAccessoryView == nil else { return }
        field.inputAccessoryView = bar()
        field.reloadInputViews()
    }

    @MainActor private static func attach(to view: UITextView?) {
        guard let view, view.inputAccessoryView == nil, view.isEditable else { return }
        view.inputAccessoryView = bar()
        view.reloadInputViews()
    }

    @MainActor private static func bar() -> UIToolbar {
        let bar = UIToolbar(frame: CGRect(x: 0, y: 0, width: 320, height: 44))
        let done = UIBarButtonItem(
            title: L.t(Strings.shared.action_done),
            style: .done,
            target: Closer.shared,
            action: #selector(Closer.close)
        )
        bar.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil), done]
        bar.sizeToFit()
        return bar
    }

    /// The Done button's target: closes whatever keyboard is up.
    private final class Closer: NSObject {
        static let shared = Closer()

        @objc func close() {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
    }
}
