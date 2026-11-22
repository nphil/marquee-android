import Foundation
import MarqueeCore
import Synchronization

/// The guide ships its first window immediately. The second uses the same Core lineup and merges into that window.
/// A tune's handle keeps the original library alive until its grab has been released, even after a profile change.
final class LiveCalls: Sendable {
    private static let methods: Set<String> = [
        "live.cached", "live.guide", "live.extend", "live.genre", "live.direct", "live.open", "live.report", "live.close",
    ]

    static func handles(_ method: String) -> Bool { methods.contains(method) || DvrCalls.handles(method) }

    private struct GuideRun: Sendable {
        let id = UUID().uuidString
        let server: String
        let dvr: LiveDVR
        let guide: LiveGuide
        let filters: [LiveChannelFilter]
    }

    private let pending = Mutex<GuideRun?>(nil)
    private let streams = Mutex<[String: Stream]>([:])

    func handle(_ method: String, _ payload: Data, library: (ServerCall) throws -> PlexLibrary) async throws -> Data {
        let decoder = JSONDecoder()
        switch method {
        case "live.cached":
            let server = try decoder.decode(ServerCall.self, from: payload).server.id
            let call = try decoder.decode(GuideCall.self, from: payload)
            let saved = await call.cache.load(server: server, dvr: "lineup")
            return try Reply.ok(saved.map { GuideWire($0) })
        case "live.guide":
            let live = PlexLiveTV(library: try library(decoder.decode(ServerCall.self, from: payload)))
            let call = try decoder.decode(GuideCall.self, from: payload)
            return try await guide(live, at: call.now)
        case "live.extend":
            let live = PlexLiveTV(library: try library(decoder.decode(ServerCall.self, from: payload)))
            let call = try decoder.decode(GuideCall.self, from: payload)
            guard let run = pending.withLock({ pending -> GuideRun? in
                guard let run = pending, call.run == run.id, run.server == live.library.server.id else { return nil }
                pending = nil
                return run
            }) else { return try Reply.ok(nil as GuideWire?) }
            let middle = run.guide.to
            let end = middle.addingTimeInterval(21 * 3600)
            let rest = try await live.guide(run.dvr, channels: run.guide.channels, from: middle, to: end)
            try Task.checkCancellation()
            let full = run.guide.merging(rest, from: middle, to: end)
            await call.cache.save(full, server: run.server, dvr: "lineup")
            return try Reply.ok(GuideWire(full, filters: run.filters, tunerOffline: offline(run.dvr)))
        case "live.genre":
            let live = PlexLiveTV(library: try library(decoder.decode(ServerCall.self, from: payload)))
            let call = try decoder.decode(GenreCall.self, from: payload)
            return try Reply.ok(try await live.channelKeys(in: call.filter).sorted())
        case "live.direct":
            let live = PlexLiveTV(library: try library(decoder.decode(ServerCall.self, from: payload)))
            let call = try decoder.decode(DirectLiveRoute.self, from: payload)
            return try await Reply.ok(call.plan(using: live))
        case "live.open":
            let live = PlexLiveTV(library: try library(decoder.decode(ServerCall.self, from: payload)))
            let call = try decoder.decode(TuneCall.self, from: payload)
            return try await open(call.channel, through: live)
        case "live.report":
            let call = try decoder.decode(StreamCall.self, from: payload)
            guard let raw = call.state, let state = PlaybackState(rawValue: raw), state != .stopped else {
                throw BridgeError.unknownMethod(method)
            }
            let stream = streams.withLock { $0[call.session] }
            try await stream?.report(state)
            return try Reply.ok(Empty())
        case "live.close":
            let call = try decoder.decode(StreamCall.self, from: payload)
            let stream = streams.withLock { $0.removeValue(forKey: call.session) }
            try await stream?.finish()
            return try Reply.ok(Empty())
        case _ where DvrCalls.handles(method):
            let live = PlexLiveTV(library: try library(decoder.decode(ServerCall.self, from: payload)))
            return try await DvrCalls.handle(method, payload, through: live)
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    private func guide(_ live: PlexLiveTV, at now: TimeInterval) async throws -> Data {
        guard let dvr = try await live.dvrs().first else { return try Reply.ok(nil as GuideWire?) }
        async let categories = try? live.channelFilters(dvr)
        let channels = try await live.channels(dvr)
        let date = Date(timeIntervalSince1970: now)
        let from = date.addingTimeInterval(-GuideCache.history)
        let to = date.addingTimeInterval(3 * 3600)
        let airings = channels.isEmpty ? [] : try await live.guide(dvr, channels: channels, from: from, to: to)
        let guide = LiveGuide(channels: channels, airings: airings, from: from, to: to)
        let filters = await categories ?? []
        try Task.checkCancellation()
        if guide.isEmpty { return try Reply.ok(GuideWire(guide, tunerOffline: offline(dvr))) }
        let run = GuideRun(server: live.library.server.id, dvr: dvr, guide: guide, filters: filters)
        pending.withLock { $0 = run }
        return try Reply.ok(GuideWire(guide, filters: filters, tunerOffline: offline(dvr), run: run.id))
    }

    private func offline(_ dvr: LiveDVR) -> Bool {
        !dvr.devices.isEmpty && dvr.devices.allSatisfy { !$0.isAlive }
    }

    private func open(_ channel: LiveChannel, through live: PlexLiveTV) async throws -> Data {
        // A cancelled POST can still hold the tuner. Let the tune answer so there is a grab to release.
        let work = Task {
            let started = ContinuousClock.now
            let session = try await live.tune(channel)
            let stream = Stream(live: live, session: session, started: started)
            do {
                let url = try await live.streamURL(TranscodeRequest(live: session))
                guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
                    throw PlexError.decoding("bad live stream path")
                }
                parts.percentEncodedQueryItems = parts.percentEncodedQueryItems?.filter { $0.name != PlexHeader.token }
                guard let playlist = parts.url else { throw PlexError.decoding("bad live stream path") }
                let reply = try Reply.ok(LiveStreamWire(
                    session: session.sessionIdentifier, url: playlist, headers: live.library.authorizationHeaders()
                ))
                return (stream, reply)
            } catch {
                try? await stream.finish()
                throw error
            }
        }
        let (stream, reply) = try await work.value
        if Task.isCancelled {
            _ = await Task { try? await stream.finish() }.value
            throw CancellationError()
        }
        streams.withLock { $0[stream.session.sessionIdentifier] = stream }
        return reply
    }

    private struct Stream: Sendable {
        let live: PlexLiveTV
        let session: PlexLiveSession
        let started: ContinuousClock.Instant

        func report(_ state: PlaybackState) async throws {
            let time = Int(started.duration(to: .now) / .milliseconds(1))
            try await live.reportTimeline(LiveTimelineUpdate(session: session, state: state, time: time))
        }

        func finish() async throws {
            try? await report(.stopped)
            try await live.stopStream(session)
        }
    }
}

private struct GuideCall: Decodable {
    let directory: String
    let now: TimeInterval
    let run: String?

    var cache: GuideCache {
        let date = Date(timeIntervalSince1970: now)
        return GuideCache(directory: URL(fileURLWithPath: directory, isDirectory: true), now: { date })
    }
}

private struct GuideWire: Encodable {
    let channels: [LiveChannel]
    let airings: [String: [AiringWire]]
    let from: TimeInterval
    let to: TimeInterval
    let filters: [LiveChannelFilter]
    let tunerOffline: Bool
    let run: String?

    init(_ guide: LiveGuide, filters: [LiveChannelFilter] = [], tunerOffline: Bool? = nil, run: String? = nil) {
        channels = guide.channels
        airings = guide.airings.mapValues { $0.map(AiringWire.init) }
        from = guide.from.timeIntervalSince1970
        to = guide.to.timeIntervalSince1970
        self.filters = filters
        self.tunerOffline = tunerOffline ?? (!channels.isEmpty && channels.allSatisfy { !$0.device.isAlive })
        self.run = run
    }
}

private struct AiringWire: Encodable {
    let id: String
    let guid: String
    let title: String
    let episodeCode: String?
    let episodeTitle: String?
    let summary: String?
    let start: TimeInterval
    let end: TimeInterval
    let isNew: Bool
    let isHD: Bool
    let thumb: String?
    let art: String?
    let contentRating: String?

    init(_ airing: LiveAiring) {
        id = airing.id
        guid = airing.guid
        title = airing.title
        episodeCode = airing.episodeCode
        episodeTitle = airing.episodeTitle
        summary = airing.summary
        start = airing.start.timeIntervalSince1970
        end = airing.end.timeIntervalSince1970
        isNew = airing.isNew
        isHD = airing.isHD
        thumb = airing.thumb
        art = airing.art
        contentRating = airing.contentRating
    }
}

private struct GenreCall: Decodable { let filter: LiveChannelFilter }
private struct TuneCall: Decodable { let channel: LiveChannel }
private struct StreamCall: Decodable { let session: String; let state: String? }
private struct LiveStreamWire: Encodable { let session: String; let url: URL; let headers: [String: String] }
