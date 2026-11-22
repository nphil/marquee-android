import Foundation
import MarqueeCore

struct PrepareSpeechCall: Decodable {
    let session: String
    let ratingKey: String
    let partId: Int
    let subtitle: Int
    let playback: String
    let address: String
    let digest: String
    let data: Data
}

struct AlignSpeechCall: Decodable {
    let session: String
    let playback: String
    let dueMs: Int
}

struct StopSpeechCall: Decodable {
    let session: String
    let playback: String
}

struct SpeechTiming: Sendable {
    let playback: String
    let origin: URL
    let identity: SpeechDock.Identity
    let digest: String
    let cues: [SpeechDock.Cue]
    var asked: Set<Int> = []

    /// No bridge call while the playhead is more than 20 s behind the first eligible cue.
    func nextAsk(at due: Int) -> Int? {
        cues.first { $0.end > due && !asked.contains($0.ordinal) }.map { max(0, $0.start - 20_000 + 1) }
    }
}

struct SpeechTimingReply: Encodable, Sendable {
    struct TimedCue: Encodable, Sendable {
        let startMs: Int
        let text: String
        let starts: [Double]
    }

    var active = true
    var nextAtMs: Int?
    var busy = false
    var cues: [TimedCue] = []
}

extension PlaySession {
    func prepareSpeech(_ call: PrepareSpeechCall) throws -> SpeechTimingReply {
        try Task.checkCancellation()
        guard hasStream, let file = subtitleFile, file.key == .init(ratingKey: call.ratingKey, partId: call.partId),
              let audio = file.audio, let origin = try? SpeechDock.origin(call.address).get(),
              let identity = SpeechDock.identity(
                  server: library.server.id, ratingKey: call.ratingKey, media: file.media, audio: audio, subtitle: call.subtitle
              ), call.data.count <= 4 * 1024 * 1024, let plain = SpeechDock.cues(call.data) else {
            return SpeechTimingReply(active: false)
        }
        let cues = SpeechDock.alignable(plain).filter { $0.end <= 86_400_000 }
        try Task.checkCancellation()
        guard !cues.isEmpty else { return SpeechTimingReply(active: false) }
        let timing = SpeechTiming(playback: call.playback, origin: origin, identity: identity, digest: call.digest, cues: cues)
        speechTiming = timing
        return SpeechTimingReply(nextAtMs: timing.nextAsk(at: 0))
    }

    func stopSpeech(_ call: StopSpeechCall) {
        if speechTiming?.playback == call.playback { speechTiming = nil }
    }

    func alignSpeech(_ call: AlignSpeechCall, transport: any PlexTransport) async throws -> SpeechTimingReply {
        guard var timing = speechTiming, timing.playback == call.playback else { return SpeechTimingReply(active: false) }
        // Android plays captions on the file's own clock. No offset is applied to that clock.
        let due = min(86_400_000, max(0, call.dueMs))
        let window = SpeechDock.window(timing.cues, asked: timing.asked, due: due, offset: 0)
        guard !window.isEmpty else { return SpeechTimingReply(nextAtMs: timing.nextAsk(at: due)) }
        let request = SpeechDock.Request(
            id: UUID().uuidString, generation: 0, playback: timing.playback, identity: timing.identity,
            digest: timing.digest, offset: 0, cues: window
        )
        let answer = try? await SpeechDockClient(origin: timing.origin, transport: transport).align(request)
        try Task.checkCancellation()
        guard speechTiming?.playback == call.playback else { return SpeechTimingReply(active: false) }
        switch answer?.outcome {
        case .read?:
            timing.asked.formUnion(window.map(\.ordinal))
            speechTiming = timing
            let starts = answer?.words ?? [:]
            return SpeechTimingReply(nextAtMs: timing.nextAsk(at: due), cues: window.compactMap { cue in
                starts[cue.ordinal].map { .init(startMs: cue.start, text: cue.text, starts: $0) }
            })
        case .busy?:
            return SpeechTimingReply(nextAtMs: due, busy: true)
        case .refused(_)?, nil:
            // The local estimates stay when SpeechDock cannot read the source.
            speechTiming = nil
            return SpeechTimingReply(active: false)
        }
    }
}

struct RevealCall: Decodable {
    struct Line: Decodable {
        let text: String
        let durationMs: Double?
        let measured: [Double]?

        func words(in mode: SubtitleLook.Reveal) -> [Word] {
            let duration = durationMs.map { $0 / 1000 } ?? SubtitleReveal.spoken(text)
            let timing = measured.flatMap { SubtitleReveal.measured(text, starts: $0, by: mode) }
                ?? SubtitleReveal.estimate(text, duration: duration, by: mode)
            var cursor = text.startIndex
            var character = 0
            var offset = 0
            return timing.map { word in
                // Cocoa's word positions count graphemes; Android spans index UTF-16.
                let start = text.index(cursor, offsetBy: word.offset - character)
                let end = text.index(start, offsetBy: word.length)
                offset += text[cursor..<start].utf16.count
                let length = text[start..<end].utf16.count
                let result = Word(start: offset, end: offset + length, atMs: word.start * 1000)
                cursor = end
                character = word.offset + word.length
                offset += length
                return result
            }
        }
    }

    struct Word: Encodable {
        let start: Int
        let end: Int
        let atMs: Double
    }

    let mode: SubtitleLook.Reveal
    let cues: [Line]
}
