import SwiftUI
import UIKit

/// The system camera, for photographing a statement (#31). SwiftUI has no
/// camera view of its own, so this wraps UIKit's. The photo comes back as
/// JPEG data and is only ever written to the app's own folder.
struct CameraPicker: UIViewControllerRepresentable {
    let onTaken: (Data) -> Void
    let onCancel: () -> Void

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let parent: CameraPicker

        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) {
                parent.onTaken(data)
            } else {
                parent.onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.onCancel() }
    }
}
