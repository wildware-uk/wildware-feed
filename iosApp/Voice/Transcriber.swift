import AVFoundation
import Speech
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// What a recording said: its phrases in order, and word by word what the recognizer settled on
/// (`best`) and its other whole guesses (`others`), for `VoiceWords.place`.
struct Heard: @unchecked Sendable {
    var phrases: [String] = []
    var best: [HeardWord] = []
    var others: [[HeardWord]] = []
}

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

    /// What was heard. No phrases when nothing could be made out.
    static func transcribe(_ file: URL) async -> Heard {
        if #available(iOS 26, *), let heard = try? await analyze(file) {
            return heard
        }
        return (try? await recognize(file)) ?? Heard()
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
    private static func analyze(_ file: URL) async throws -> Heard? {
        guard SpeechTranscriber.isAvailable,
              let locale = await SpeechTranscriber.supportedLocale(equivalentTo: .current)
        else { return nil }
        // As the long-form preset, plus how sure it was of each word, when, and its other guesses.
        let transcriber = SpeechTranscriber(
            locale: locale,
            transcriptionOptions: [],
            reportingOptions: [.alternativeTranscriptions],
            attributeOptions: [.audioTimeRange, .transcriptionConfidence]
        )
        if let download = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try await download.downloadAndInstall()
        }
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let phrases = Task {
            var heard = Heard()
            for try await result in transcriber.results {
                heard.phrases.append(String(result.text.characters))
                heard.best += Self.words(result.text)
                heard.others += result.alternatives.map { Self.words($0) }
            }
            return heard
        }
        defer { phrases.cancel() }
        if let end = try await analyzer.analyzeSequence(from: AVAudioFile(forReading: file)) {
            try await analyzer.finalizeAndFinish(through: end)
        } else {
            await analyzer.cancelAndFinishNow()
        }
        return try await phrases.value
    }

    /// The words of one of the long-form model's guesses, each with how sure it was and when.
    @available(iOS 26, *)
    private static func words(_ text: AttributedString) -> [HeardWord] {
        text.runs.compactMap { run in
            let piece = String(text[run.range].characters).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !piece.isEmpty else { return nil }
            let time = run.audioTimeRange
            return HeardWord(
                text: piece,
                confidence: run.transcriptionConfidence ?? 1,
                startSeconds: time?.start.seconds ?? 0,
                endSeconds: time?.end.seconds ?? 0,
                alternatives: []
            )
        }
    }

    /// The older recognizer's final result: its best guess word by word, and its other guesses.
    static func heard(_ result: SFSpeechRecognitionResult) -> Heard {
        let best = result.bestTranscription
        // A recognizer that gives no confidence at all reports 0 for every word: then none is doubted.
        let rated = best.segments.contains { $0.confidence > 0 }
        return Heard(
            phrases: [best.formattedString],
            best: best.segments.map { Self.word($0, rated: rated) },
            others: result.transcriptions
                .filter { $0.formattedString != best.formattedString }
                .map { $0.segments.map { Self.word($0, rated: false) } }
        )
    }

    private static func word(_ segment: SFTranscriptionSegment, rated: Bool) -> HeardWord {
        HeardWord(
            text: segment.substring,
            confidence: rated ? Double(segment.confidence) : 1,
            startSeconds: segment.timestamp,
            endSeconds: segment.timestamp + segment.duration,
            alternatives: segment.alternativeSubstrings
        )
    }

    /// The older recognizer, kept on the phone when the language allows it.
    private static func recognize(_ file: URL) async throws -> Heard {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized,
              let recognizer = SFSpeechRecognizer(), recognizer.isAvailable
        else { return Heard() }
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
                    answer.give(.success(Self.heard(result)))
                } else if let error {
                    answer.give(.failure(error))
                }
            }
        }
    }

    /// The recognizer can report an error after its final result, and a continuation resumes only once.
    /// Its callbacks arrive one at a time on its own queue.
    private final class Answer: @unchecked Sendable {
        private var continuation: CheckedContinuation<Heard, Error>?

        init(_ continuation: CheckedContinuation<Heard, Error>) { self.continuation = continuation }

        func give(_ result: Result<Heard, Error>) {
            continuation?.resume(with: result)
            continuation = nil
        }
    }
}
