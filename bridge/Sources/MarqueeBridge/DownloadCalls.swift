import Foundation
import MarqueeCore

/// Android owns the files and scheduling; Core owns Plex's download protocol and quality/watch rules.
enum DownloadCalls {
    static func handle(_ method: String, _ payload: Data, library makeLibrary: (ServerCall) throws -> PlexLibrary) async throws -> Data {
        if method == "download.watch.update" {
            let request = try decode(WatchUpdate.self, payload)
            var watch = request.watch
            let at = Date(timeIntervalSince1970: request.at)
            if request.finished {
                watch.progress.finished(at: at, told: false)
                watch.scrobble = nil
            } else {
                watch.progress.played(to: max(0, request.position), at: at)
            }
            return try Reply.ok(watch)
        }
        let library = try makeLibrary(decode(ServerCall.self, payload))
        switch method {
        case "download.choices":
            let request = try decode(Choices.self, payload)
            let snapshot = try await library.metadataSnapshot(request.ratingKey)
            let item = snapshot.item
            guard item.media.indices.contains(request.mediaIndex), let part = item.media[request.mediaIndex].parts.first else {
                throw DownloadError("There is no file to download for this version.")
            }
            let media = item.media[request.mediaIndex]
            let refusal = media.parts.count != 1
                ? "This version has more than one file. Choose a converted quality."
                : originalRefusal(media, part, capabilities: request.capabilities.capabilities)
            let show = if let show = item.grandparentRatingKey, item.type == .episode {
                try? await library.metadata(show)
            } else { nil as PlexMetadata? }
            // The queue's MP4 profile offers H.264, not the streamed HEVC option on iPhone.
            let plan = DownloadQualityPlan(
                media: media, animated: DownloadQualityPlan.isAnimated(genres: show?.genres ?? item.genres),
                hevc: false, originalPlays: refusal == nil, map: nil
            )
            let tier = DownloadTier(rawValue: request.quality)
            return try Reply.ok(ChoicesWire(
                title: TitleWire(item, duration: [item.duration, media.duration, part.duration].first { $0 > 0 } ?? 0),
                options: plan.options.map { ChoiceWire($0, refusal: refusal) },
                preferred: plan.option(for: tier.map(DownloadQuality.tier) ?? .original)?.id,
                snapshot: String(decoding: snapshot.data, as: UTF8.self),
                isAnime: item.isAnime || show?.isAnime == true,
                countries: item.countries.isEmpty ? show?.countries ?? [] : item.countries,
                watch: OfflineWatch(offset: item.viewOffset, watched: item.isWatched)
            ))
        case "download.episodes":
            let request = try decode(Episodes.self, payload)
            let items = if request.show {
                try await library.allLeaves(of: request.ratingKey)
            } else {
                try await library.children(of: request.ratingKey)
            }
            return try Reply.ok(items.filter { $0.type == .episode }.map(\.ratingKey))
        case "download.source":
            let request = try decode(Source.self, payload)
            let item = try PlexMetadata.fromSnapshot(Data(contentsOf: URL(fileURLWithPath: request.snapshot)))
            guard item.media.indices.contains(request.mediaIndex), let part = item.media[request.mediaIndex].parts.first else {
                throw DownloadError("The saved media description is missing.")
            }
            let url = if let conversion = request.conversion {
                try await library.downloadURL(queue: conversion.queue, item: conversion.item)
            } else {
                try await library.downloadURL(part: part)
            }
            let base = try await library.connection.connection().uri
            let thumb = item.type == .episode ? item.grandparentThumb ?? item.thumb : item.thumb
            let poster = if request.assets, let thumb {
                try? await library.imageURL(thumb, width: 240, height: 360, scale: 1)?.absoluteString
            } else { nil as String? }
            let query = [URLQueryItem(name: "encoding", value: "utf-8"), URLQueryItem(name: "format", value: "srt")]
            let tracks = MediaTracks(part: part)
            let subtitles = request.assets ? tracks.subtitles.compactMap { track -> SidecarWire? in
                guard track.isExternal, !track.isImageBased,
                      let url = PlexURL.make(base: base, path: "/library/streams/\(track.id)", query: query) else { return nil }
                return SidecarWire(id: track.id, url: url.absoluteString)
            } : []
            return try Reply.ok(SourceWire(url: url.absoluteString, headers: library.authorizationHeaders(), poster: poster, subtitles: subtitles))
        case "download.source.failed":
            await library.connection.reportFailure(at: try decode(Address.self, payload).url)
            return try Reply.ok(Empty())
        case "download.convert.queue":
            return try await Reply.ok(library.downloadQueue())
        case "download.convert.add":
            let request = try decode(Convert.self, payload)
            let item = try await library.addToDownloadQueue(request.queue, ratingKey: request.ratingKey, mediaIndex: request.mediaIndex, target: request.target)
            return try Reply.ok(Conversion(queue: request.queue, item: item))
        case "download.convert.status":
            let request = try decode(Conversion.self, payload)
            let state = try await library.downloadQueueItem(request.queue, item: request.item)
            return try Reply.ok(ConversionWire(status: state.status, fraction: state.progress))
        case "download.convert.decision":
            let request = try decode(Conversion.self, payload)
            let decision = try await library.downloadDecision(request.queue, item: request.item)
            guard decision.item.media.first?.parts.first != nil else {
                throw DownloadError("The server did not describe the converted file.")
            }
            return try Reply.ok(String(decoding: decision.data, as: UTF8.self))
        case "download.convert.restart":
            let request = try decode(Conversion.self, payload)
            try await library.restartDownloadQueueItem(request.queue, item: request.item)
            return try Reply.ok(Empty())
        case "download.convert.remove":
            let request = try decode(Conversion.self, payload)
            try await library.removeFromDownloadQueue(request.queue, item: request.item)
            return try Reply.ok(Empty())
        case "download.watch.sync":
            let request = try decode(WatchSync.self, payload)
            let result = try await sync(request, in: library)
            return try Reply.ok(result)
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    private static func sync(_ request: WatchSync, in library: PlexLibrary) async throws -> WatchSyncWire {
        var watch = request.watch
        guard let guid = stableGUID(request.guid), !request.ratingKey.isEmpty else {
            watch.synced()
            return WatchSyncWire(watch: watch, changedServer: false)
        }
        let item = try await library.metadata(request.ratingKey)
        guard item.ratingKey == request.ratingKey, item.guid == guid else {
            watch.synced()
            return WatchSyncWire(watch: watch, changedServer: false)
        }
        let viewed = watch.scrobble?.matches(item) == true ? nil : item.lastViewedAt
        var sent = false
        for action in watch.progress.sync(serverLastViewed: viewed) {
            switch action {
            case .watched:
                try await library.markWatched(request.ratingKey)
                if watch.progress.offset > 0 {
                    // Commit the scrobble receipt to disk before trying the separate resume write.
                    watch.progress.finishedUnsynced = false
                    watch.scrobble = ScrobbleReceipt(previousViewCount: item.viewCount, receivedAt: Date())
                    return WatchSyncWire(watch: watch, changedServer: true)
                }
            case .resume(let offset):
                try await library.reportTimeline(TimelineUpdate(item: item, state: .stopped, time: offset, duration: request.duration))
            }
            sent = true
        }
        if let viewed, let local = watch.progress.unsynced, viewed > local {
            watch.progress.offset = item.viewOffset
            watch.progress.watched = item.isWatched
        }
        watch.synced()
        return WatchSyncWire(watch: watch, changedServer: sent)
    }

    private static func stableGUID(_ guid: String?) -> String? {
        guard let guid, let url = URLComponents(string: guid), let scheme = url.scheme?.lowercased(),
              let host = url.host, !host.isEmpty else { return nil }
        switch scheme {
        case "plex":
            return url.path.contains { $0 != "/" } ? guid : nil
        case "com.plexapp.agents.imdb", "com.plexapp.agents.themoviedb", "com.plexapp.agents.thetvdb":
            return guid
        default:
            // Local identifiers can be generated from the same server row we're trying to verify.
            return nil
        }
    }

    private static func originalRefusal(_ media: PlexMedia, _ part: PlexPart, capabilities: DeviceCapabilities) -> String? {
        let tracks = MediaTracks(part: part)
        let route = PlaybackRouteDecider.decide(
            media: media, part: part, tracks: tracks, audio: tracks.audio.first,
            subtitle: SubtitleDecision(track: nil, source: .rules), capabilities: capabilities, hevcSampleEntry: "hvc1"
        )
        if route.kind == .directPlay { return nil }
        // Unlike iPhone, the Android player opens a file directly and does not remux it first.
        return "This device can't play the original file offline. Choose a converted quality."
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        try JSONDecoder().decode(type, from: data)
    }

    private struct Choices: Decodable {
        let ratingKey: String
        let mediaIndex: Int
        let quality: String
        let capabilities: CapabilitiesWire
    }

    private struct Episodes: Decodable {
        let ratingKey: String
        let show: Bool
    }

    private struct Address: Decodable { let url: URL }

    private struct Source: Decodable {
        let snapshot: String
        let mediaIndex: Int
        let conversion: Conversion?
        let assets: Bool
    }

    private struct Convert: Decodable {
        let queue: Int
        let ratingKey: String
        let mediaIndex: Int
        let target: DownloadTarget
    }

    private struct Conversion: Codable {
        let queue: Int
        let item: Int
    }

    private struct ConversionWire: Encodable {
        let status: PlexDownloadQueueItem.Status
        let fraction: Double?
    }

    private struct SourceWire: Encodable {
        let url: String
        let headers: [String: String]
        let poster: String?
        let subtitles: [SidecarWire]
    }

    private struct SidecarWire: Encodable {
        let id: Int
        let url: String
    }

    private struct ChoicesWire: Encodable {
        let title: TitleWire
        let options: [ChoiceWire]
        let preferred: String?
        let snapshot: String
        let isAnime: Bool
        let countries: [String]
        let watch: OfflineWatch
    }

    private struct ChoiceWire: Encodable {
        let id: String
        let label: String
        let resolution: String?
        let bytes: Int64?
        let target: DownloadTarget?
        let refusal: String?

        init(_ option: DownloadQualityPlan.Option, refusal: String?) {
            id = option.id
            label = option.quality.title
            resolution = option.resolution
            bytes = option.bytes
            target = option.target
            self.refusal = target == nil ? refusal : nil
        }
    }

    private struct TitleWire: Encodable {
        let ratingKey: String
        let guid: String?
        let title: String
        let year: Int?
        let show: String?
        let showKey: String?
        let seasonKey: String?
        let season: Int?
        let episode: Int?
        let duration: Int

        init(_ item: PlexMetadata, duration: Int) {
            ratingKey = item.ratingKey
            guid = DownloadCalls.stableGUID(item.guid)
            title = item.title
            year = item.year
            show = item.grandparentTitle
            showKey = item.grandparentRatingKey
            seasonKey = item.parentRatingKey
            season = item.parentIndex
            episode = item.index
            self.duration = duration
        }
    }

    private struct WatchUpdate: Decodable {
        let watch: SavedWatch
        let position: Int
        let finished: Bool
        let at: Double
    }

    private struct WatchSync: Decodable {
        let ratingKey: String
        let guid: String?
        let duration: Int
        let watch: SavedWatch
    }

    private struct WatchSyncWire: Encodable {
        let watch: SavedWatch
        let changedServer: Bool
    }

    private struct SavedWatch: Codable {
        var progress: OfflineWatch
        var scrobble: ScrobbleReceipt?

        private enum CodingKeys: String, CodingKey { case scrobble }

        init(from decoder: any Decoder) throws {
            progress = try OfflineWatch(from: decoder)
            scrobble = try decoder.container(keyedBy: CodingKeys.self).decodeIfPresent(ScrobbleReceipt.self, forKey: .scrobble)
        }

        func encode(to encoder: any Encoder) throws {
            try progress.encode(to: encoder)
            var values = encoder.container(keyedBy: CodingKeys.self)
            try values.encodeIfPresent(scrobble, forKey: .scrobble)
        }

        mutating func synced() {
            progress.synced()
            scrobble = nil
        }
    }

    private struct ScrobbleReceipt: Codable {
        let previousViewCount: Int
        let receivedAt: Date

        func matches(_ item: PlexMetadata) -> Bool {
            let (expected, overflow) = previousViewCount.addingReportingOverflow(1)
            return !overflow && item.viewCount == expected && item.viewOffset == 0 &&
                (item.lastViewedAt.map { $0 <= receivedAt } ?? true)
        }
    }
}

struct DownloadError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) { self.description = description }
}
