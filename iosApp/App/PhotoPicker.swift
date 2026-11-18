import ComposeApp
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// The composer's paperclip on iPhone: Apple's photo picker, which needs no photo library permission.
/// Each picture is sent as a JPEG, so HEIC photos open everywhere the dashboard shows them.
final class PhotoPicker: NSObject, FilePicker, PHPickerViewControllerDelegate {
    private static let limit = 4
    private var onPicked: (([PendingFile]) -> Void)?

    func pick(onPicked: @escaping ([PendingFile]) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = Self.topController() else { return onPicked([]) }
            self.onPicked = onPicked
            var configuration = PHPickerConfiguration()
            configuration.filter = .images
            configuration.selectionLimit = Self.limit
            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = self
            presenter.present(picker, animated: true)
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let onPicked else { return }
        self.onPicked = nil
        Task {
            var files: [PendingFile] = []
            for (index, result) in results.enumerated() {
                guard let data = await Self.jpeg(from: result.itemProvider) else { continue }
                files.append(IosPlatformKt.pendingFile(name: "photo-\(index + 1).jpg", mimeType: "image/jpeg", data: data))
            }
            await MainActor.run { onPicked(files) }
        }
    }

    private static func jpeg(from provider: NSItemProvider) async -> Data? {
        await withCheckedContinuation { continuation in
            provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                continuation.resume(returning: data.flatMap(UIImage.init(data:))?.jpegData(compressionQuality: 0.85))
            }
        }
    }

    /// The screen on top, so the picker opens over whatever is showing.
    private static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
