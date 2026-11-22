import Foundation
import MarqueeCore
import Synchronization

/// The casting plugins' calls. Core decides what the receiver is sent (`CastRouteDecider`, `CastStream`, the
/// transient token) and how its statuses read (`CastProgress`); the sender loads the media and reports the
/// receiver's own clock. The bridge keeps what outlives one call: the play session, the server's conversion and
/// each cast's `CastProgress`. The timeline reports go through the player's `play.report` and `play.stop` on the same
/// session, with the position and state `cast.progress` returned.
enum CastCalls {
    private static let names: Set<String> = ["cast.start", "cast.progress", "cast.end"]

    static func handles(_ method: String) -> Bool {
        names.contains(method)
    }

    static func handle(_ method: String, _ payload: Data, using services: CastServices) async throws -> Data {
        switch method {
        case "cast.start":
            let library = try services.play.library(decode(ServerCall.self, payload))
            return try Reply.ok(await start(decode(CastStartCall.self, payload), library: library, services: services))
        case "cast.progress":
            return try Reply.ok(try services.progress.update(decode(CastProgressCall.self, payload)))
        case "cast.end":
            services.progress.end(try decode(SessionCall.self, payload).session)
            return try Reply.ok(Empty())
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    // MARK: Start

    /// What the iPhone's `PlayerModel` does to cast an item: load it, pick the tracks, decide the receiver's route,
    /// and hand back the stream with a transient token in place of the account's. A server stream starts at the
    /// title's beginning; the receiver is told where to seek.
    private static func start(_ call: CastStartCall, library: PlexLibrary, services: CastServices) async throws -> CastPlanWire {
        let play = services.play
        let airPlay = call.airPlay == true
        let castSession = airPlay ? nil : play.sessions.open(call.session, library: library)
        await castSession?.endStream()

        var item = try await library.metadata(call.ratingKey)
        if let castSession { item.playQueueItemID = await castSession.queued(item) ?? item.playQueueItemID }
        let mediaIndex: Int
        if let recording = call.recordingMediaID {
            guard let index = item.index(ofMediaID: recording) else { throw PlayError.noFile }
            mediaIndex = index
        } else {
            mediaIndex = item.media.indices.contains(call.mediaIndex ?? 0) ? call.mediaIndex ?? 0 : 0
        }
        guard item.media.indices.contains(mediaIndex), let part = item.media[mediaIndex].parts.first else {
            throw PlayError.noFile
        }
        let media = item.media[mediaIndex]
        let duration = Double([item.duration, media.duration, part.duration].first { $0 > 0 } ?? 0) / 1000
        let tracks = MediaTracks(part: part)
        let tags = await play.anime.tags(item, in: library)
        let picked = TrackRules.pick(
            from: tracks, isAnime: tags.isAnime, countries: tags.countries,
            choices: TrackRules.Choices(audio: call.audio, subtitle: call.subtitle?.choice, memory: call.memory)
        )

        let quality: VideoQuality
        switch call.quality {
        case .auto: quality = .original
        case .fixed(let fixed): quality = fixed
        }
        let reason: RouteReason? = call.receiverFailed == true ? .receiverFailed : call.directPlay == .disabled ? .directPlayOff : nil
        let receiver = CastReceiver(modelName: airPlay ? nil : call.receiverModel)
        let limits = RouteLimits(serverReason: reason, quality: quality)
        func decide(_ subtitle: SubtitleDecision) -> PlaybackRoute {
            CastRouteDecider.decide(
                media: media, part: part, tracks: tracks, audio: picked.audio, subtitle: subtitle, receiver: receiver, limits: limits
            )
        }
        let route = decide(picked.subtitle)
        if airPlay {
            // LG's AirPlay HLS player needs CORS on the playlist and segments, which Plex has not been verified to provide.
            guard route.kind == .directPlay, !(picked.subtitle.source == .handPick && picked.subtitle.track != nil),
                  ["h264", "avc", "avc1"].contains((part.videoStream?.codec ?? media.videoCodec ?? "").lowercased()),
                  tracks.audio.count == 1, picked.audio?.codec == "aac",
                  part.key.hasPrefix("/library/parts/"), !part.key.contains("?"), !part.key.contains("#")
            else { throw AirPlayMediaError.unsupported }
        }
        let session = castSession ?? play.sessions.open(call.session, library: library)
        if airPlay {
            await session.endStream()
            item.playQueueItemID = await session.queued(item) ?? item.playQueueItemID
        }
        let lists = PlayTracksWire(
            audio: tracks.audio.map { PlayAudioWire(id: $0.id, label: $0.label, language: $0.language.code, isDefault: $0.isDefault) },
            subtitles: tracks.subtitles.map { track in
                PlaySubtitleWire(
                    id: track.id, label: track.label, language: track.language.code, isDefault: track.isDefault,
                    isExternal: track.isExternal, isHidden: track.isHidden,
                    delivery: airPlay ? "notShown" : DeliveryWire(decide(SubtitleDecision(track: track, source: .handPick)).subtitle).kind,
                    url: nil
                )
            },
            automatic: TrackRules.automaticSubtitle(in: tracks, audio: picked.audio, isAnime: picked.isAnime)?.id,
            headers: [:]
        )

        let token = try await library.transientToken()
        guard token != library.server.accessToken else {
            throw PlexError.decoding("Plex did not issue a separate casting credential.")
        }
        let connection = try await library.connection.connection()
        let base = CastStream.address(current: connection, among: library.server.connections).uri
        let stream: CastStream?
        var conversion: String?
        if route.kind == .directPlay {
            stream = CastStream.file(part: part, base: base, token: token)
        } else {
            try await selectForServer(route, part: part, tracks: tracks, library: library, session: session)
            let id = UUID().uuidString
            conversion = id
            stream = CastStream.transcode(
                TranscodeRequest(item: item, mediaIndex: mediaIndex, route: route, offset: 0, session: id),
                base: base, identity: try services.identity(), isLocal: connection.isLocal, token: token
            )
        }
        guard let stream else { throw PlexError.decoding("bad path") }
        await session.beginCast(item, tracks: tracks, address: connection.uri, conversion: conversion)

        let startAt = startSeconds(call, item: item, duration: duration)
        services.progress.begin(session.id, CastProgress(startingAt: startAt, replacing: call.replacing, now: call.now))

        let badgeAudio = route.audio.flatMap { track in part.streams.first { $0.id == track.id } }
        let thumbnails = part.hasSceneThumbnails
            ? PlexURL.make(base: connection.uri, path: "/library/parts/\(part.id)/indexes/sd")
                .map { ThumbnailsWire(url: $0.absoluteString, headers: library.authorizationHeaders()) }
            : nil
        let artwork = item.type == .episode ? item.grandparentThumb ?? item.thumb : item.thumb
        let poster = (airPlay ? nil : artwork).flatMap {
            PlexURL.make(base: base, path: $0, query: [URLQueryItem(name: PlexHeader.token, value: token)])
        }
        let plan = PlanWire(
            session: session.id, item: ItemWire(item), mediaIndex: mediaIndex, startAt: startAt, duration: duration,
            route: RouteWire(route),
            stream: StreamSpec(kind: conversion == nil ? "file" : "hls", url: stream.url.absoluteString, headers: [:], session: stream.session, offset: 0),
            probe: nil, intro: item.markers(.intro).map(MarkerWire.init), credits: item.markers(.credits).map(MarkerWire.init),
            chapters: item.chapters.map(ChapterWire.init), tracks: lists, isAnime: picked.isAnime, quality: quality.rawValue,
            adapts: false, badge: BadgeWire(FormatBadge.make(video: part.videoStream, media: media, audio: badgeAudio, route: route)),
            thumbnails: thumbnails
        )
        return CastPlanWire(plan: plan, contentType: stream.contentType, poster: poster?.absoluteString)
    }

    /// Where the cast starts: the caller's pick, the beginning, or the server's place unless that is near the end.
    private static func startSeconds(_ call: CastStartCall, item: PlexMetadata, duration: Double) -> Double {
        if let startAt = call.startAt { return max(0, startAt) }
        if call.fromStart == true { return 0 }
        let offset = Double(item.viewOffset) / 1000
        return offset > 0 && (duration == 0 || offset < duration * 0.95) ? offset : 0
    }

    /// The server streams the audio the route plays and the subtitle it burns in, if any. Plex can burn in a selected
    /// or forced subtitle whatever the request says, so the server's own subtitle is cleared for the stream and put
    /// back, with its audio, when the stream ends.
    private static func selectForServer(
        _ route: PlaybackRoute, part: PlexPart, tracks: MediaTracks, library: PlexLibrary, session: PlaySession
    ) async throws {
        let serverAudio = tracks.audio.first(where: \.isSelectedOnServer)?.id
        let serverSubtitle = tracks.subtitles.first(where: \.isSelectedOnServer)?.id
        let audio = route.audio?.id ?? serverAudio
        var subtitle: Int?
        if case .burnedIn(let track) = route.subtitle { subtitle = track.id }
        guard audio != serverAudio || subtitle != serverSubtitle else { return }
        try await library.selectStreams(partID: part.id, audioStreamID: audio, subtitleStreamID: subtitle)
        if let serverSubtitle { await session.willRestore(part: part.id, audio: serverAudio, subtitle: serverSubtitle) }
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }
}

// MARK: Services

/// What the cast calls reach: the player's services, the client identity a receiver's stream address is signed
/// with, and the progress of each cast.
struct CastServices: Sendable {
    let play: PlayServices
    let identity: @Sendable () throws -> PlexClientIdentity
    let progress: CastProgressStore
}

extension Methods {
    var castServices: CastServices {
        CastServices(play: playServices, identity: { try self.identity() }, progress: CastState.progress)
    }
}

private enum CastState {
    static let progress = CastProgressStore()
}

/// Core's `CastProgress` for each play session that casts, so every status and tick of a cast reads against the
/// one before it.
final class CastProgressStore: Sendable {
    private let casts = Mutex<[String: CastProgress]>([:])

    func begin(_ session: String, _ progress: CastProgress) {
        casts.withLock { $0[session] = progress }
    }

    func end(_ session: String) {
        _ = casts.withLock { $0.removeValue(forKey: session) }
    }

    func update(_ call: CastProgressCall) throws -> CastProgressWire {
        try casts.withLock { casts in
            guard var progress = casts[call.session] else { throw PlayError.noSession }
            let end = call.status.flatMap { progress.update($0.status, now: call.now) }
            casts[call.session] = progress
            return CastProgressWire(
                state: progress.state.rawValue, position: progress.position(at: call.now), end: end.map(CastProgressWire.word)
            )
        }
    }
}

// MARK: Wire

// The cast calls as JSON. Kotlin mirrors them in core/CastCore.kt. Times are seconds; `now` is the app's monotonic
// clock in seconds.

/// Casts an item: what to play and where, and what the receiver is.
struct CastStartCall: Decodable {
    let ratingKey: String
    /// The player screen's session; nil opens one.
    let session: String?
    /// Where to start; nil resumes from the server's place.
    let startAt: Double?
    let fromStart: Bool?
    let mediaIndex: Int?
    let quality: QualitySetting
    let directPlay: DirectPlayMode
    let now: Double
    let audio: Int?
    let subtitle: SubtitleChoiceWire?
    let memory: TrackMemory?
    /// The receiver's model name, as its device reports it.
    let receiverModel: String?
    /// The id of the load this one replaces, so statuses of the old item are not taken for this one's.
    let replacing: Int?
    /// The receiver could not play the file itself, so the server converts it.
    let receiverFailed: Bool?
    /// AirPlay receivers currently get only a compatible direct-play file.
    let airPlay: Bool?
    let recordingMediaID: Int?
}

enum AirPlayMediaError: Error {
    case unsupported
}

/// What the receiver is sent: the plan's stream is the address, with the content type and the poster to show.
struct CastPlanWire: Encodable {
    let plan: PlanWire
    let contentType: String
    let poster: String?
}

/// A tick of the cast, with the receiver's newest status when one arrived since the last.
struct CastProgressCall: Decodable {
    let session: String
    let now: Double
    let status: CastStatusWire?
}

/// The receiver's status. `player` is `playing`, `paused`, `buffering`, `loading`, `idle` or anything else for unknown;
/// `idle` says why the receiver went idle: `finished`, `cancelled`, `interrupted`, `error` or none.
struct CastStatusWire: Decodable {
    let mediaSessionId: Int
    let player: String
    let idle: String?
    let position: Double
    let rate: Double

    var status: CastStatus {
        let state: CastStatus.Player
        switch player {
        case "playing": state = .playing
        case "paused": state = .paused
        case "buffering": state = .buffering
        case "loading": state = .loading
        case "idle":
            switch idle {
            case "finished": state = .idle(.finished)
            case "cancelled": state = .idle(.cancelled)
            case "interrupted": state = .idle(.interrupted)
            case "error": state = .idle(.error)
            default: state = .idle(.none)
            }
        default: state = .unknown
        }
        return CastStatus(mediaSessionID: mediaSessionId, player: state, position: position, rate: rate)
    }
}

/// Where the cast is: a `PlaybackState` word, the position in seconds, and how it ended if it did.
struct CastProgressWire: Encodable {
    let state: String
    let position: Double
    /// `finished`, `failed` or `stopped`.
    let end: String?

    static func word(_ end: CastProgress.End) -> String {
        switch end {
        case .finished: "finished"
        case .failed: "failed"
        case .stopped: "stopped"
        }
    }
}
