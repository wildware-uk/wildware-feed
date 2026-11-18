import AVFoundation
import Speech

/// Writes down what a recording says, on the phone itself.
enum Transcriber {
    /// Gets ready while the app is open, because the lock screen can neither ask nor wait:
    /// on iOS 26 it downloads the speech model, on older iOS it asks for speech recognition.
    static func prepare() async {
        if #available(iOS 26, *), let transcriber = await longFormTranscriber() {
            try? await AssetInventory.assetInstallationRequest(supporting: [transcriber])?.downloadAndInstall()
            return
        }
        await askForSpeech()
    }

    /// Asks once to use speech recognition: the older recognizer, and the words shown live while a
    /// note is held, both need it.
    static func askForSpeech() async {
        guard SFSpeechRecognizer.authorizationStatus() == .notDetermined else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            SFSpeechRecognizer.requestAuthorization { _ in continuation.resume() }
        }
    }

    /// The phrases heard, in order. Empty when nothing could be made out.
    static func transcribe(_ file: URL) async -> [String] {
        if #available(iOS 26, *), let phrases = try? await analyze(file) {
            return phrases
        }
        return (try? await recognize(file)).map { [$0] } ?? []
    }

    @available(iOS 26, *)
    private static func longFormTranscriber() async -> SpeechTranscriber? {
        guard SpeechTranscriber.isAvailable,
              let locale = await SpeechTranscriber.supportedLocale(equivalentTo: .current)
        else { return nil }
        return SpeechTranscriber(locale: locale, preset: .transcription)
    }

    /// iOS 26's long-form model: better than the older recognizer at long notes, names and punctuation.
    /// Nil when it doesn't speak the phone's language.
    @available(iOS 26, *)
    private static func analyze(_ file: URL) async throws -> [String]? {
        guard let transcriber = await longFormTranscriber() else { return nil }
        if let download = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try await download.downloadAndInstall()
        }
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let phrases = Task {
            var phrases: [String] = []
            for try await result in transcriber.results {
                phrases.append(String(result.text.characters))
            }
            return phrases
        }
        defer { phrases.cancel() }
        if let end = try await analyzer.analyzeSequence(from: AVAudioFile(forReading: file)) {
            try await analyzer.finalizeAndFinish(through: end)
        } else {
            await analyzer.cancelAndFinishNow()
        }
        return try await phrases.value
    }

    /// The older recognizer, kept on the phone when the language allows it.
    private static func recognize(_ file: URL) async throws -> String {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized,
              let recognizer = SFSpeechRecognizer(), recognizer.isAvailable
        else { return "" }
        let request = SFSpeechURLRecognitionRequest(url: file)
        request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        request.addsPunctuation = true
        request.shouldReportPartialResults = false
        return try await withCheckedThrowingContinuation { continuation in
            let answer = Answer(continuation)
            recognizer.recognitionTask(with: request) { result, error in
                // Holding the recognizer here keeps it alive until the task ends.
                withExtendedLifetime(recognizer) {}
                if let result, result.isFinal {
                    answer.give(.success(result.bestTranscription.formattedString))
                } else if let error {
                    answer.give(.failure(error))
                }
            }
        }
    }

    /// The recognizer can report an error after its final result, and a continuation resumes only once.
    /// Its callbacks arrive one at a time on its own queue.
    private final class Answer: @unchecked Sendable {
        private var continuation: CheckedContinuation<String, Error>?

        init(_ continuation: CheckedContinuation<String, Error>) { self.continuation = continuation }

        func give(_ result: Result<String, Error>) {
            continuation?.resume(with: result)
            continuation = nil
        }
    }
}
