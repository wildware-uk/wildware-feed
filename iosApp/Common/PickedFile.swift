import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UniformTypeIdentifiers

/// A file picked in Files or shared from another app, read to go up with a message. Its name and type
/// are made ones the dashboard takes (Kotlin's `Attachable`), and it is read no further than one byte
/// past the most the dashboard takes for its type, so a huge file is turned away with a message
/// instead of filling the memory (a share sheet has little).
struct PickedFile {
    let filename: String
    let mimeType: String
    let data: Data

    /// Why it can't go, in words for the owner (empty, or over the limit), or nil when it can.
    var refusal: String? {
        Attachable.shared.refusal(name: filename, mimeType: mimeType, bytes: Int64(data.count))
    }

    /// Reads [url]. [name] is what the other app calls it, when that is better than the file's own
    /// name; [typeIdentifier] what it said it is, for a file whose name has no extension.
    static func read(_ url: URL, name: String? = nil, typeIdentifier: String? = nil) -> PickedFile? {
        let filename = Attachable.shared.fileName(raw: name ?? url.lastPathComponent, fallback: "file")
        let declared = UTType(filenameExtension: (filename as NSString).pathExtension)?.preferredMIMEType
            ?? UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? typeIdentifier.flatMap { UTType($0)?.preferredMIMEType }
        let mimeType = Attachable.shared.mimeType(raw: declared, name: filename)
        let limit = Int(Attachable.shared.maxBytes(mimeType: mimeType))
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: limit + 1) else { return nil }
        return PickedFile(filename: filename, mimeType: mimeType, data: data)
    }

    /// The name to keep for a file another app hands over: its [suggested] name, with the file's
    /// extension put back when the suggestion lost it ("Report" for "Report.pdf").
    static func name(suggested: String?, file url: URL) -> String {
        guard let suggested, !suggested.isEmpty else { return url.lastPathComponent }
        let ext = url.pathExtension
        if ext.isEmpty || (suggested as NSString).pathExtension.lowercased() == ext.lowercased() { return suggested }
        return "\(suggested).\(ext)"
    }
}
