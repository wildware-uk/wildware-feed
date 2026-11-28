import ComposeApp
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// The composer's paperclip, "Photos and videos": Apple's photo picker, which needs no photo library
/// permission. Each picture is sent as a JPEG, so HEIC photos open everywhere the dashboard shows
/// them; a video goes as the file it is.
final class PhotoPicker: NSObject, FilePicker, PHPickerViewControllerDelegate {
    private static let limit = 4
    private var onPicked: (([PendingFile]) -> Void)?

    func pick(onPicked: @escaping ([PendingFile]) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = PickerPresenter.topController() else { return onPicked([]) }
            self.onPicked = onPicked
            var configuration = PHPickerConfiguration()
            configuration.filter = .any(of: [.images, .videos])
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
                let provider = result.itemProvider
                if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    guard let video = await Self.video(from: provider) else { continue }
                    files.append(IosPlatformKt.pendingFile(name: video.filename, mimeType: video.mimeType, data: video.data))
                } else if let data = await Self.jpeg(from: provider) {
                    files.append(IosPlatformKt.pendingFile(name: "photo-\(index + 1).jpg", mimeType: "image/jpeg", data: data))
                }
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

    /// The video's own file, read while the picker's copy still exists (it goes when the handler returns).
    private static func video(from provider: NSItemProvider) async -> PickedFile? {
        await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                continuation.resume(returning: url.flatMap {
                    PickedFile.read($0, name: PickedFile.name(suggested: provider.suggestedName, file: $0))
                })
            }
        }
    }
}

/// The composer's paperclip, "Files": the Files browser, for any file on the phone, in iCloud Drive or
/// another app's storage. Each pick is copied in, so no lasting access to the owner's files is kept.
final class DocumentPicker: NSObject, FilePicker, UIDocumentPickerDelegate {
    private var onPicked: (([PendingFile]) -> Void)?

    func pick(onPicked: @escaping ([PendingFile]) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = PickerPresenter.topController() else { return onPicked([]) }
            self.onPicked = onPicked
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
            picker.allowsMultipleSelection = true
            picker.delegate = self
            presenter.present(picker, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let onPicked else { return }
        self.onPicked = nil
        Task {
            var files: [PendingFile] = []
            for url in urls {
                defer { try? FileManager.default.removeItem(at: url) }
                guard let file = PickedFile.read(url) else { continue }
                files.append(IosPlatformKt.pendingFile(name: file.filename, mimeType: file.mimeType, data: file.data))
            }
            await MainActor.run { onPicked(files) }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        onPicked?([])
        onPicked = nil
    }
}

private enum PickerPresenter {
    /// The screen on top, so the picker opens over whatever is showing.
    static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
