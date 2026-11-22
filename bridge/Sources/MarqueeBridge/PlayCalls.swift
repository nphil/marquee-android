import Foundation
import MarqueeCore
import Synchronization

/// The player's calls. Core decides how an item plays (`PlaybackRouteDecider`, `TrackRules`, `AdaptiveQuality`,
/// `LinkProbe`) and ExoPlayer performs it; the bridge keeps what outlives one call: the play queue, the
/// timeline reporter, Auto's estimate and the server's conversion.
enum PlayCalls {
    private static let names: Set<String> = [
        "play.start", "play.queue", "play.report", "play.stop", "play.look", "play.link", "play.remember",
        "play.autoplay", "play.skip.begin", "play.skip.check", "play.tap", "play.tap.reset", "play.transcode",
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
        case "play.remember":
            let call = try decode(RememberCall.self, payload)
            let tracks = try await services.session(call.session).currentTracks()
            var memory = call.memory ?? TrackMemory()
            if let id = call.audio, let track = tracks.audioTrack(id: id) { memory.audio = RememberedAudio(track) }
            // On forgets the subtitle: the rules pick again next time.
            if let choice = call.subtitle?.choice { memory.subtitle = RememberedSubtitle(choice, in: tracks) }
            return try Reply.ok(memory)
        case "play.autoplay":
            let call = try decode(AutoplayCall.self, payload)
            let rule = AutoplayNext(enabled: call.enabled, credits: call.credits, delay: call.delay)
            return try Reply.ok(AutoplayWire(rule, creditsWatched: call.creditsWatched))
        case "play.skip.begin":
            let call = try decode(SkipBeginCall.self, payload)
            try await services.session(call.session).beginSkip(to: call.target)
            return try Reply.ok(Empty())
        case "play.skip.check":
            let call = try decode(SkipCheckCall.self, payload)
            let session = try services.session(call.session)
            return try Reply.ok(await session.checkSkip(playhead: call.playhead, advanced: call.advanced, pending: call.pending))
        case "play.tap":
            let call = try decode(TapCall.self, payload)
            let session = try services.session(call.session)
            return try Reply.ok(TapWire(await session.tap(TapSide(x: call.x, width: call.width), mode: call.mode, at: call.at)))
        case "play.tap.reset":
            try await services.session(decode(SessionCall.self, payload).session).resetTaps()
            return try Reply.ok(Empty())
        case "play.transcode":
            let session = try services.session(decode(SessionCall.self, payload).session)
            return try Reply.ok(TranscodeWire(await session.transcode()))
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
        let tags = await services.anime.tags(item, in: library)
        let picked = TrackRules.pick(
            from: tracks, isAnime: tags.isAnime, countries: tags.countries,
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
        let route = decide(media: media, part: part, tracks: tracks, audio: picked.audio, subtitle: picked.subtitle, call: call, limits: limits)
        let lists = trackLists(
            media: media, part: part, tracks: tracks, picked: picked, call: call, limits: limits,
            base: connection?.uri, headers: library.authorizationHeaders()
        )

        let startAt = startSeconds(call, item: item, duration: duration)
        let badgeAudio = route.audio.flatMap { track in part.streams.first { $0.id == track.id } }
        let badge = BadgeWire(FormatBadge.make(video: part.videoStream, media: media, audio: badgeAudio, route: route))
        let thumbnails = part.hasSceneThumbnails
            ? connection.flatMap { PlexURL.make(base: $0.uri, path: "/library/parts/\(part.id)/indexes/sd") }
                .map { ThumbnailsWire(url: $0.absoluteString, headers: library.authorizationHeaders()) }
            : nil
        func plan(_ stream: StreamSpec?, probe: ProbeSpec? = nil) -> PlanWire {
            PlanWire(
                session: session.id, item: ItemWire(item), mediaIndex: mediaIndex, startAt: startAt, duration: duration,
                route: RouteWire(route), stream: stream, probe: probe,
                intro: item.markers(.intro).map(MarkerWire.init), credits: item.markers(.credits).map(MarkerWire.init),
                chapters: item.chapters.map(ChapterWire.init), tracks: lists, isAnime: picked.isAnime,
                quality: quality.rawValue, adapts: adaptive != nil, badge: badge, thumbnails: thumbnails
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
            await session.begin(item, tracks: tracks, adaptive: adaptive, address: base, conversion: nil)
            return plan(StreamSpec(kind: "file", url: url.absoluteString, headers: library.authorizationHeaders(), session: nil, offset: 0))
        case .serverTranscode:
            try await selectForServer(route, part: part, tracks: tracks, library: library, session: session)
            let conversion = UUID().uuidString
            let request = TranscodeRequest(item: item, mediaIndex: mediaIndex, route: route, offset: startAt, session: conversion)
            let url = try await library.transcodeURL(request)
            await session.begin(item, tracks: tracks, adaptive: adaptive, address: connection?.uri, conversion: conversion)
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
        media: PlexMedia, part: PlexPart, tracks: MediaTracks, audio: AudioTrack?, subtitle: SubtitleDecision, call: StartCall,
        limits: RouteLimits
    ) -> PlaybackRoute {
        func route(_ subtitle: SubtitleDecision) -> PlaybackRoute {
            // ExoPlayer opens HEVC in MP4 whichever sample entry tag the file carries.
            PlaybackRouteDecider.decide(
                media: media, part: part, tracks: tracks, audio: audio, subtitle: subtitle,
                capabilities: call.capabilities.capabilities, limits: limits, hevcSampleEntry: "hvc1"
            )
        }
        let decided = route(subtitle)
        guard case .burnedIn(let track) = decided.subtitle else { return decided }
        return route(SubtitleDecision(track: track, source: .rules))
    }

    /// The tracks as the menu lists them. A subtitle's delivery is how it would show on this route if it were picked,
    /// so the menu can say which ones this tablet cannot draw.
    private static func trackLists(
        media: PlexMedia, part: PlexPart, tracks: MediaTracks, picked: TrackRules.Pick, call: StartCall, limits: RouteLimits,
        base: URL?, headers: [String: String]
    ) -> PlayTracksWire {
        let audio = tracks.audio.map {
            PlayAudioWire(id: $0.id, label: $0.label, language: $0.language.code, isDefault: $0.isDefault)
        }
        let subtitles = tracks.subtitles.map { track in
            let alone = SubtitleDecision(track: track, source: .handPick)
            let route = decide(media: media, part: part, tracks: tracks, audio: picked.audio, subtitle: alone, call: call, limits: limits)
            let query = [URLQueryItem(name: "encoding", value: "utf-8"), URLQueryItem(name: "format", value: "srt")]
            let download = track.isExternal && !track.isImageBased
                ? base.flatMap { PlexURL.make(base: $0, path: "/library/streams/\(track.id)", query: query) } : nil
            return PlaySubtitleWire(
                id: track.id, label: track.label, language: track.language.code, isDefault: track.isDefault,
                isExternal: track.isExternal, isHidden: track.isHidden,
                delivery: DeliveryWire(route.subtitle).kind, url: download?.absoluteString
            )
        }
        let automatic = TrackRules.automaticSubtitle(in: tracks, audio: picked.audio, isAnime: picked.isAnime)?.id
        return PlayTracksWire(audio: audio, subtitles: subtitles, automatic: automatic, headers: headers)
    }

    /// The server streams the part's selected audio and, when it has one selected, that subtitle. This player draws its
    /// own, and Plex can burn in a selected or forced subtitle whatever the request says, so the server's subtitle is
    /// cleared for the stream and put back when it ends. The audio is told only when the route plays another.
    private static func selectForServer(
        _ route: PlaybackRoute, part: PlexPart, tracks: MediaTracks, library: PlexLibrary, session: PlaySession
    ) async throws {
        let serverAudio = tracks.audio.first(where: \.isSelectedOnServer)?.id
        let serverSubtitle = tracks.subtitles.first(where: \.isSelectedOnServer)?.id
        let audio = route.audio?.id ?? serverAudio
        guard audio != serverAudio || serverSubtitle != nil else { return }
        try await library.selectStreams(partID: part.id, audioStreamID: audio, subtitleStreamID: nil)
        if let serverSubtitle { await session.willRestore(part: part.id, audio: audio, subtitle: serverSubtitle) }
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
    private var tracks: MediaTracks?
    private var reporter: TimelineReporter?
    private var conversion: String?
    /// The subtitle the server had selected before a server stream cleared it, put back when that stream ends.
    private var restore: (part: Int, audio: Int?, subtitle: Int)?
    private var queue: PlexPlayQueue?
    private var queueKey: String?
    private var queueTask: Task<PlexPlayQueue?, Never>?
    /// The intro skip being confirmed, and the screen's taps: both are state machines in Core that need what came before.
    private var skipCheck: SkipCheck?
    private var taps = SkipTaps(mode: .doubleTap)

    init(id: String, library: PlexLibrary) {
        self.id = id
        self.library = library
    }

    /// Ends what the last stream held on the server: its conversion, and the subtitle it cleared.
    func endStream() async {
        reporter = nil
        if let restore {
            self.restore = nil
            try? await library.selectStreams(partID: restore.part, audioStreamID: restore.audio, subtitleStreamID: restore.subtitle)
        }
        guard let conversion else { return }
        self.conversion = nil
        try? await library.stopTranscode(session: conversion)
    }

    func willRestore(part: Int, audio: Int?, subtitle: Int) {
        restore = (part, audio, subtitle)
    }

    /// The server's account of the conversion playing, or nil when there is none or the server does not answer.
    func transcode() async -> PlexTranscodeSession? {
        guard let conversion else { return nil }
        return try? await library.transcodeSession(conversion)
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

    func begin(_ item: PlexMetadata, tracks: MediaTracks, adaptive: AdaptiveQuality?, address: URL?, conversion: String?) {
        self.item = item
        self.tracks = tracks
        self.adaptive = adaptive
        self.address = address
        self.conversion = conversion
        reporter = TimelineReporter(library: library, item: item, sessionIdentifier: conversion)
    }

    /// The tracks of the file playing, for a pick to be remembered against.
    func currentTracks() throws -> MediaTracks {
        guard let tracks else { throw PlayError.noSession }
        return tracks
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

    /// Starts confirming a skip to [target] seconds (`SkipCheck`).
    func beginSkip(to target: TimeInterval) {
        skipCheck = SkipCheck(target: target)
    }

    /// Judges the playhead against the skip being confirmed. A landing or a second miss ends the check.
    func checkSkip(playhead: TimeInterval, advanced: TimeInterval, pending: Bool) -> SkipVerdictWire {
        guard var check = skipCheck else { return SkipVerdictWire(none: ()) }
        let verdict = check.check(playhead: playhead, advanced: advanced, pending: pending)
        skipCheck = verdict == .landed || verdict == .missed ? nil : check
        return SkipVerdictWire(verdict, retried: check.retried)
    }

    func tap(_ side: TapSide, mode: SkipTapMode, at now: TimeInterval) -> SkipTaps.Action {
        taps.mode = mode
        return taps.tap(side, at: now)
    }

    /// Forgets a pending tap and any chain, for a tap that must only bring the controls.
    func resetTaps() {
        taps.reset()
    }
}
