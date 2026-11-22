import Foundation
import MarqueeCore
import Synchronization

/// The player's calls. Core decides how an item plays (`PlaybackRouteDecider`, `TrackRules`, `AdaptiveQuality`,
/// `LinkProbe`) and ExoPlayer performs it; the bridge keeps what outlives one call: the play queue, the
/// timeline reporter, Auto's estimate and the server's conversion.
enum PlayCalls {
    private static let names: Set<String> = [
        "play.start", "play.queue", "play.report", "play.stop", "play.look", "play.link",
    ]

    static func handles(_ method: String) -> Bool {
        names.contains(method)
    }

    static func handle(_ method: String, _ payload: Data, using services: PlayServices) async throws -> Data {
        switch method {
        case "play.start":
            let library = try services.library(decode(ServerCall.self, payload))
            let plan = try await start(decode(StartCall.self, payload), library: library, services: services)
            return try Reply.ok(plan)
        case "play.queue":
            let session = try services.session(decode(SessionCall.self, payload).session)
            return try Reply.ok(await session.neighbors())
        case "play.report":
            let call = try decode(ReportCall.self, payload)
            guard let state = PlaybackState(rawValue: call.state) else { throw PlayError.badState(call.state) }
            let session = try services.session(call.session)
            return try Reply.ok(TookWire(took: await session.report(state, time: call.timeMs, duration: call.durationMs)))
        case "play.stop":
            let call = try decode(StopCall.self, payload)
            let session = try services.session(call.session)
            await session.stop(time: call.timeMs, duration: call.durationMs)
            if call.close { services.sessions.close(call.session) }
            return try Reply.ok(Empty())
        case "play.look":
            let call = try decode(LookCall.self, payload)
            let look = AdaptiveQuality.Look(
                now: call.now, throughputKbps: call.throughputKbps, transferSeconds: call.transferSeconds,
                bufferSeconds: call.bufferSeconds, waitingSeconds: call.waitingSeconds
            )
            return try Reply.ok(await services.session(call.session).look(look))
        case "play.link":
            let call = try decode(LinkCall.self, payload)
            if let address = try await services.session(call.session).address {
                services.links.record(call.kbps, to: address, at: call.now, network: call.network)
            }
            return try Reply.ok(Empty())
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    // MARK: Start

    /// What the iPhone's `PlayerModel.begin` does before it hands a stream to its player: load the item, pick the
    /// tracks, size Auto, decide the route, and for a server stream have the server prepare it.
    private static func start(_ call: StartCall, library: PlexLibrary, services: PlayServices) async throws -> PlanWire {
        let session = services.sessions.open(call.session, library: library)
        await session.endStream()

        var item = try await library.metadata(call.ratingKey)
        item.playQueueItemID = await session.queued(item) ?? item.playQueueItemID
        let mediaIndex = item.media.indices.contains(call.mediaIndex ?? 0) ? call.mediaIndex ?? 0 : 0
        guard item.media.indices.contains(mediaIndex), let part = item.media[mediaIndex].parts.first else {
            throw PlayError.noFile
        }
        let media = item.media[mediaIndex]
        let duration = Double([item.duration, media.duration, part.duration].first { $0 > 0 } ?? 0) / 1000
        let tracks = MediaTracks(part: part)
        let tagged = await services.anime.isAnime(item, in: library)
        let picked = TrackRules.pick(
            from: tracks, isAnime: tagged,
            choices: TrackRules.Choices(audio: call.audio, subtitle: call.subtitle?.choice, memory: call.memory)
        )

        let connection = try? await library.connection.connection()
        let reading = call.probe.flatMap { LinkProbe.kbps(bytes: $0.bytes, seconds: $0.seconds) }
        if let reading, let connection {
            services.links.record(reading, to: connection.uri, at: call.now, network: call.network)
        }
        let linkKbps = connection.flatMap { services.links.recent(to: $0.uri, at: call.now, network: call.network) }
        let dataCap: VideoQuality? = call.savesData && call.metered ? .mbps4 : nil

        let adaptive = await adapting(call, session: session, media: media, linkKbps: linkKbps, dataCap: dataCap, connected: connection != nil)
        let quality: VideoQuality
        switch call.quality {
        case .auto:
            quality = adaptive?.rung ?? .original
        case .fixed(let fixed):
            if let dataCap, fixed == .original || fixed.rawValue > dataCap.rawValue { quality = dataCap } else { quality = fixed }
        }

        let reach = RouteReach.decide(connection: connection, mediaKbps: media.bitrate, linkKbps: linkKbps)
        let limits: RouteLimits
        switch call.directPlay {
        case .force:
            limits = RouteLimits(phoneOnly: true)
        case .disabled:
            limits = RouteLimits(serverReason: .directPlayOff, quality: quality)
        case .auto:
            limits = RouteLimits(
                serverReason: call.onDeviceFailed == true ? .onDeviceFailed : nil, quality: quality, reach: reach,
                linkKbps: adaptive == nil ? linkKbps : nil
            )
        }
        let route = decide(media: media, part: part, tracks: tracks, picked: picked, call: call, limits: limits)

        let startAt = startSeconds(call, item: item, duration: duration)
        func plan(_ stream: StreamSpec?, probe: ProbeSpec? = nil) -> PlanWire {
            PlanWire(
                session: session.id, item: ItemWire(item), mediaIndex: mediaIndex, startAt: startAt, duration: duration,
                route: RouteWire(route), stream: stream, probe: probe,
                intro: item.markers(.intro).map(MarkerWire.init), credits: item.markers(.credits).map(MarkerWire.init),
                chapters: item.chapters.map(ChapterWire.init), isAnime: picked.isAnime,
                quality: quality.rawValue, adapts: adaptive != nil
            )
        }

        // The first start on a link nobody has measured: the server converts, so the app reads the file for a
        // moment first and asks again with what it measured.
        if call.probe == nil, call.directPlay != .force, let connection, LinkProbe.isNeeded(route: route, reach: reach),
           !services.links.isDistrusted(connection.uri, network: call.network),
           let url = PlexURL.make(base: connection.uri, path: part.key) {
            let probe = ProbeSpec(
                url: url.absoluteString, headers: library.authorizationHeaders(), maxBytes: LinkProbe.maxBytes,
                windowSeconds: LinkProbe.window, timeoutSeconds: LinkProbe.timeout
            )
            return plan(nil, probe: probe)
        }

        switch route.kind {
        case .directPlay, .remux:
            let base = try await library.connection.connection().uri
            guard let url = PlexURL.make(base: base, path: part.key) else { throw PlexError.decoding("bad path") }
            await session.begin(item, adaptive: adaptive, address: base, conversion: nil)
            return plan(StreamSpec(kind: "file", url: url.absoluteString, headers: library.authorizationHeaders(), session: nil, offset: 0))
        case .serverTranscode:
            try await selectForServer(route, part: part, tracks: tracks, library: library)
            let conversion = UUID().uuidString
            let request = TranscodeRequest(item: item, mediaIndex: mediaIndex, route: route, offset: startAt, session: conversion)
            let url = try await library.transcodeURL(request)
            await session.begin(item, adaptive: adaptive, address: connection?.uri, conversion: conversion)
            return plan(StreamSpec(kind: "hls", url: url.absoluteString, headers: library.authorizationHeaders(), session: conversion, offset: startAt))
        }
    }

    /// Where playback starts: the caller's pick, the beginning, or the server's place unless that is near the end.
    static func startSeconds(_ call: StartCall, item: PlexMetadata, duration: Double) -> Double {
        if let startAt = call.startAt { return max(0, startAt) }
        if call.fromStart == true { return 0 }
        let offset = Double(item.viewOffset) / 1000
        return offset > 0 && (duration == 0 || offset < duration * 0.95) ? offset : 0
    }

    /// Auto's machine for this item: kept across a move so its estimate carries on, made again when the item starts
    /// or a reading of the link arrives. Nil when the quality is fixed, the tablet plays by itself, or no server answers.
    private static func adapting(
        _ call: StartCall, session: PlaySession, media: PlexMedia, linkKbps: Int?, dataCap: VideoQuality?, connected: Bool
    ) async -> AdaptiveQuality? {
        guard case .auto = call.quality, call.directPlay != .force, connected else { return nil }
        if let move = call.move, call.probe == nil, var kept = await session.adaptive {
            kept.moved(to: move, at: call.now)
            return kept
        }
        let source = media.width.flatMap { width in media.height.map { AdaptiveQuality.Picture(width: width, height: $0) } }
        return AdaptiveQuality(
            mediaKbps: media.bitrate, linkKbps: linkKbps, cap: dataCap, source: source, view: call.view?.picture, now: call.now
        )
    }

    /// The route for the tracks picked. This player never asks the server to burn a subtitle in, so a picture
    /// subtitle only a re-encode could show is chosen but not shown, as when the rules pick it.
    private static func decide(
        media: PlexMedia, part: PlexPart, tracks: MediaTracks, picked: TrackRules.Pick, call: StartCall, limits: RouteLimits
    ) -> PlaybackRoute {
        func route(_ subtitle: SubtitleDecision) -> PlaybackRoute {
            // ExoPlayer opens HEVC in MP4 whichever sample entry tag the file carries.
            PlaybackRouteDecider.decide(
                media: media, part: part, tracks: tracks, audio: picked.audio, subtitle: subtitle,
                capabilities: call.capabilities.capabilities, limits: limits, hevcSampleEntry: "hvc1"
            )
        }
        let decided = route(picked.subtitle)
        guard case .burnedIn(let track) = decided.subtitle else { return decided }
        return route(SubtitleDecision(track: track, source: .rules))
    }

    /// The server streams the part's selected audio; it is told only when the route plays another.
    private static func selectForServer(
        _ route: PlaybackRoute, part: PlexPart, tracks: MediaTracks, library: PlexLibrary
    ) async throws {
        let serverAudio = tracks.audio.first(where: \.isSelectedOnServer)?.id
        let serverSubtitle = tracks.subtitles.first(where: \.isSelectedOnServer)?.id
        let audio = route.audio?.id ?? serverAudio
        guard audio != serverAudio else { return }
        try await library.selectStreams(partID: part.id, audioStreamID: audio, subtitleStreamID: serverSubtitle)
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }
}

// MARK: Services

/// What the player calls reach: a server's library, the play sessions open, the link readings, and the anime lookup.
struct PlayServices: Sendable {
    let library: @Sendable (ServerCall) throws -> PlexLibrary
    let sessions: PlaySessions
    let links: LinkStore
    let anime: AnimeLookup

    func session(_ id: String) throws -> PlaySession {
        guard let session = sessions.find(id) else { throw PlayError.noSession }
        return session
    }
}

extension Methods {
    var playServices: PlayServices {
        PlayServices(library: { try self.library(for: $0) }, sessions: PlayState.sessions, links: PlayState.links, anime: PlayState.anime)
    }
}

/// What the app's one player shares between its screens.
private enum PlayState {
    static let sessions = PlaySessions()
    static let links = LinkStore()
    static let anime = AnimeLookup()
}

final class PlaySessions: Sendable {
    private let byID = Mutex<[String: PlaySession]>([:])

    /// The session named, or a new one when none is named or it has ended.
    func open(_ id: String?, library: PlexLibrary) -> PlaySession {
        byID.withLock { byID in
            if let id, let session = byID[id] { return session }
            let session = PlaySession(id: UUID().uuidString, library: library)
            byID[session.id] = session
            return session
        }
    }

    func find(_ id: String) -> PlaySession? {
        byID.withLock { $0[id] }
    }

    func close(_ id: String) {
        byID.withLock { $0[id] = nil }
    }
}

/// The readings of the link to each server (`LinkMemory`), shared by every item played.
final class LinkStore: Sendable {
    private let memory = Mutex(LinkMemory())

    func record(_ kbps: Int, to address: URL, at now: TimeInterval, network: Int) {
        memory.withLock { $0.record(kbps, to: address, at: now, network: network) }
    }

    func recent(to address: URL, at now: TimeInterval, network: Int) -> Int? {
        memory.withLock { $0.recent(to: address, at: now, network: network) }
    }

    func isDistrusted(_ address: URL, network: Int) -> Bool {
        memory.withLock { $0.isDistrusted(address, network: network) }
    }
}

/// One player screen's state on the bridge, from opening to closing.
actor PlaySession {
    let id: String
    private let library: PlexLibrary
    private(set) var adaptive: AdaptiveQuality?
    /// The server address the stream comes from, for readings of the link.
    private(set) var address: URL?
    private var item: PlexMetadata?
    private var reporter: TimelineReporter?
    private var conversion: String?
    private var queue: PlexPlayQueue?
    private var queueKey: String?
    private var queueTask: Task<PlexPlayQueue?, Never>?

    init(id: String, library: PlexLibrary) {
        self.id = id
        self.library = library
    }

    /// Ends what the last stream held on the server: its conversion.
    func endStream() async {
        reporter = nil
        guard let conversion else { return }
        self.conversion = nil
        try? await library.stopTranscode(session: conversion)
    }

    /// The item's place in the play queue, or nil while a new queue is made beside the start (`neighbors` waits for it).
    func queued(_ item: PlexMetadata) -> Int? {
        if let queued = queue?.items.first(where: { $0.ratingKey == item.ratingKey }) { return queued.playQueueItemID }
        if queueKey == item.ratingKey, queueTask != nil { return nil }
        queue = nil
        queueKey = item.ratingKey
        queueTask?.cancel()
        let library = library
        queueTask = Task { try? await library.createPlayQueue(for: item) }
        return nil
    }

    func begin(_ item: PlexMetadata, adaptive: AdaptiveQuality?, address: URL?, conversion: String?) {
        self.item = item
        self.adaptive = adaptive
        self.address = address
        self.conversion = conversion
        reporter = TimelineReporter(library: library, item: item, sessionIdentifier: conversion)
    }

    /// The items either side of the one playing, once the play queue has come.
    func neighbors() async -> NeighborsWire {
        if queue == nil, let task = queueTask {
            queue = await task.value
            queueTask = nil
        }
        guard let queue, let item else { return NeighborsWire(next: nil, previous: nil) }
        if let queued = queue.items.first(where: { $0.ratingKey == item.ratingKey })?.playQueueItemID {
            await reporter?.queued(as: queued)
        }
        return NeighborsWire(next: queue.item(after: item).map(ItemWire.init), previous: queue.item(before: item).map(ItemWire.init))
    }

    /// Core's cadence decides whether the report goes out.
    func report(_ state: PlaybackState, time: Int, duration: Int) async -> Bool {
        await reporter?.update(state, time: time, duration: duration) ?? false
    }

    func stop(time: Int, duration: Int) async {
        _ = await reporter?.update(.stopped, time: time, duration: duration)
        await endStream()
    }

    func look(_ look: AdaptiveQuality.Look) -> MoveWire {
        guard var adaptive else { return MoveWire(rung: nil, estimateKbps: nil, belowFloor: false) }
        let move = adaptive.observe(look)
        self.adaptive = adaptive
        return MoveWire(rung: move?.rawValue, estimateKbps: adaptive.estimateKbps, belowFloor: adaptive.belowFloor)
    }
}
