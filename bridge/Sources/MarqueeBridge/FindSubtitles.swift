import Foundation
import MarqueeCore

struct FindSubtitlesCall: Decodable {
    struct Bazarr: Decodable {
        let address: String
        let apiKey: String
    }

    let session: String
    let ratingKey: String
    let partId: Int
    let request: String
    let bazarr: Bazarr?
    var checkOnly: Bool?
    var choice: String?
}

struct FindProgressCall: Decodable {
    let session: String
    let request: String
}

/// The file actually playing, not another version the server happens to list first.
struct PlayingSubtitleFile: Sendable {
    let id = UUID()
    let item: PlexMetadata
    var media: PlexMedia
    let partId: Int
    let audio: Int?
    var tracks: MediaTracks
    let menu: @Sendable (PlexMedia, PlexPart, MediaTracks) -> PlayTracksWire

    var key: FileKey { FileKey(ratingKey: item.ratingKey, partId: partId) }

    struct FileKey: Equatable, Sendable {
        let ratingKey: String
        let partId: Int
    }

    func lookup(in library: PlexLibrary) async -> BazarrLookup {
        let file = media.parts.first?.file
        if item.type == .episode {
            let ids: [String]
            if let show = item.grandparentRatingKey { ids = (try? await library.externalIDs(show)) ?? [] } else { ids = [] }
            return BazarrLookup(
                title: item.grandparentTitle ?? item.title, year: nil, externalIDs: ids,
                season: item.parentIndex, episode: item.index, file: file
            )
        }
        let ids = (try? await library.externalIDs(item.ratingKey)) ?? []
        return BazarrLookup(title: item.title, year: item.year, externalIDs: ids, file: file)
    }
}

struct SubtitleSearchState: Sendable {
    struct Pending: Sendable {
        let added: SubtitleSearch.Added
        let before: Set<Int>
        var refreshed = false
    }

    var key: PlayingSubtitleFile.FileKey?
    var address: URL?
    var target: BazarrTarget?
    var picker: (file: UUID, address: URL, target: BazarrTarget, rows: [String: BazarrSubtitle])?
    var tried = SubtitleSearch.Tried()
    var pending: Pending?
    var progress: (request: String, banner: FindBanner)?

    func banner(for request: String) -> FindBanner? {
        progress?.request == request ? progress?.banner : nil
    }
}

struct FindBanner: Encodable, Sendable {
    enum Tone: String, Encodable, Sendable { case busy, found, missing }
    let tone: Tone
    let title: String
    let detail: String

    static func busy(_ detail: String) -> Self {
        Self(tone: .busy, title: "Finding English subtitles", detail: detail)
    }

    static func reason(_ miss: SubtitleSearch.BazarrMiss) -> String {
        switch miss {
        case .slow: "Bazarr took too long"
        case .unreachable: "Bazarr can't be reached"
        case .refused: "Bazarr's address or key is wrong"
        case .unknownTitle: "Bazarr doesn't list this title"
        case .nothing: "Bazarr found nothing in English"
        case .failed: "Bazarr's search failed"
        }
    }

    static func step(_ step: SubtitleSearch.Step) -> Self {
        switch step {
        case .searching(.bazarr, _): busy("Asking Bazarr…")
        case .searching(.plex, let miss): busy(miss.map { "\(reason($0)). Asking Plex…" } ?? "Asking Plex…")
        case .downloading(.bazarr): busy("Downloading from Bazarr…")
        case .downloading(.plex): busy("Adding from Plex…")
        }
    }
}

struct FindSubtitlesReply: Encodable, Sendable {
    let banner: FindBanner
    var tracks: PlayTracksWire?
    var trackId: Int?
    var awaitingFile = false
}

struct ProviderChoice: Encodable, Sendable {
    let key: String
    let label: String
}

extension PlaySession {
    func subtitleChoices(_ call: FindSubtitlesCall, transport: any PlexTransport) async throws -> [ProviderChoice] {
        guard hasStream, let library, let file = subtitleFile, file.key == .init(ratingKey: call.ratingKey, partId: call.partId),
              let link = call.bazarr else { throw PlayError.noSession }
        let config = try BazarrConfig.parse(address: link.address, key: link.apiKey).get()
        let client = BazarrClient(config: config, transport: transport)
        let target = try await client.find(file.lookup(in: library))
        let rows = try await client.search(target).filter { $0.trackLanguage == .english }.sorted { $0.score > $1.score }
        guard subtitleFile?.id == file.id else { throw CancellationError() }
        if subtitleSearch.key != file.key { subtitleSearch = SubtitleSearchState(key: file.key) }
        var offers: [String: BazarrSubtitle] = [:]
        let choices = rows.map { row in
            let key = UUID().uuidString
            offers[key] = row
            let details = [row.providerName, "\(Int(row.score))%", row.releases.first,
                           row.isForced ? "Forced" : row.isHearingImpaired ? "SDH" : nil]
            return ProviderChoice(key: key, label: details.compactMap { $0 }.joined(separator: " · "))
        }
        subtitleSearch.picker = (file.id, config.address, target, offers)
        return choices
    }

    func findSubtitles(
        _ call: FindSubtitlesCall, transport: any PlexTransport,
        waits: [Duration] = [.seconds(1), .seconds(2), .seconds(3), .seconds(5), .seconds(8)]
    ) async throws -> FindSubtitlesReply {
        guard hasStream, let library, let file = subtitleFile, file.key == .init(ratingKey: call.ratingKey, partId: call.partId),
              subtitleSearch.progress == nil else {
            throw PlayError.noSession
        }
        let config = call.bazarr.flatMap { try? BazarrConfig.parse(address: $0.address, key: $0.apiKey).get() }
        if subtitleSearch.key != file.key { subtitleSearch = SubtitleSearchState(key: file.key) }
        if call.checkOnly != true, subtitleSearch.address != config?.address {
            subtitleSearch.address = config?.address
            subtitleSearch.target = nil
        }
        let request = call.request
        let checking = subtitleSearch.pending != nil
        subtitleSearch.progress = (request, .busy(checking ? "Checking Plex for the last file…" : config == nil ? "Asking Plex…" : "Asking Bazarr…"))
        defer { if subtitleSearch.progress?.request == request { subtitleSearch.progress = nil } }

        if call.checkOnly == true {
            guard let pending = subtitleSearch.pending else {
                return FindSubtitlesReply(banner: .init(tone: .missing, title: "No subtitle is waiting", detail: "Find subtitles to search again."))
            }
            return try await listSubtitle(pending, in: file, request: request, waits: [.zero], refreshAllowed: false)
                ?? FindSubtitlesReply(banner: .init(tone: .missing, title: "Plex hasn't listed the new file yet", detail: "Check Plex again in a moment."), awaitingFile: true)
        }
        if let key = call.choice {
            guard let config, let picker = subtitleSearch.picker, picker.file == file.id, picker.address == config.address,
                  let row = picker.rows[key] else { throw PlayError.noFile }
            subtitleSearch.picker = nil
            let pending = SubtitleSearchState.Pending(added: .bazarr(row), before: Set(file.tracks.subtitles.map(\.id)))
            try await BazarrClient(config: config, transport: transport).download(row, for: picker.target)
            try checkSearch(file, request)
            subtitleSearch.pending = pending
            subtitleSearch.tried.insert(pending.added)
            return try await listSubtitle(pending, in: file, request: request, waits: waits)
                ?? FindSubtitlesReply(banner: .init(tone: .missing, title: "Waiting for Plex", detail: "Check Plex again in a moment."), awaitingFile: true)
        }
        if let pending = subtitleSearch.pending,
           let result = try await listSubtitle(pending, in: file, request: request, waits: [.zero]) { return result }

        let tracks = subtitleFile?.tracks ?? file.tracks
        let before = Set(tracks.subtitles.map(\.id))
        let search = SubtitleSearch(
            bazarr: config.map { BazarrClient(config: $0, transport: transport) },
            library: library, ratingKey: call.ratingKey, language: .english
        )
        let outcome = await search.run(
            lookup: { await file.lookup(in: library) }, target: subtitleSearch.target, tracks: tracks, tried: subtitleSearch.tried
        ) { step in
            await self.subtitleStep(step, request: request)
        }
        try checkSearch(file, request)
        subtitleSearch.target = outcome.target
        guard let added = outcome.added else {
            let plex = outcome.plexFailed ? "Plex's search failed." : "Plex has nothing new."
            let detail = outcome.bazarrMiss.map { "\(FindBanner.reason($0)). \(plex)" } ?? plex
            return FindSubtitlesReply(
                banner: .init(tone: .missing, title: "No English subtitles found", detail: detail), tracks: changedMenu(since: file)
            )
        }

        subtitleSearch.tried.insert(added)
        let pending = SubtitleSearchState.Pending(added: added, before: before)
        subtitleSearch.pending = pending
        subtitleSearch.progress = (request, .busy("Waiting for Plex to list it…"))
        if let result = try await listSubtitle(pending, in: file, request: request, waits: waits) {
            return result
        }
        return FindSubtitlesReply(banner: .init(
            tone: .missing, title: "Plex hasn't listed the new file yet", detail: "From \(added.source.rawValue). Find again to check."
        ), tracks: changedMenu(since: file), awaitingFile: true)
    }

    private func changedMenu(since file: PlayingSubtitleFile) -> PlayTracksWire? {
        guard let fresh = subtitleFile, fresh.tracks != file.tracks, let part = fresh.media.parts.first else { return nil }
        return fresh.menu(fresh.media, part, fresh.tracks)
    }

    private func subtitleStep(_ step: SubtitleSearch.Step, request: String) {
        if !Task.isCancelled, subtitleSearch.progress?.request == request { subtitleSearch.progress = (request, .step(step)) }
    }

    private func checkSearch(_ file: PlayingSubtitleFile, _ request: String) throws {
        try Task.checkCancellation()
        guard file.id == subtitleFile?.id, subtitleSearch.progress?.request == request else { throw CancellationError() }
    }

    private func listSubtitle(
        _ pending: SubtitleSearchState.Pending, in file: PlayingSubtitleFile, request: String, waits: [Duration], refreshAllowed: Bool = true
    ) async throws -> FindSubtitlesReply? {
        guard let library else { throw PlayError.noSession }
        var pending = pending
        do {
            if refreshAllowed, case .bazarr = pending.added, !pending.refreshed {
                try await library.refresh(file.item.ratingKey)
                try checkSearch(file, request)
                pending.refreshed = true
                subtitleSearch.pending = pending
            }
            for wait in waits {
                if wait > .zero { try await Task.sleep(for: wait) }
                try checkSearch(file, request)
                let fresh = try await library.metadata(file.item.ratingKey)
                try checkSearch(file, request)
                guard let media = fresh.media.first(where: { $0.id == file.media.id && $0.parts.first?.id == file.partId }),
                      let part = media.parts.first else { throw PlayError.noFile }
                let tracks = MediaTracks(part: part)
                subtitleFile?.media = media
                subtitleFile?.tracks = tracks
                guard let track = pending.added.track(in: tracks, since: pending.before) else { continue }
                subtitleSearch.pending = nil
                let provider: String? = switch pending.added {
                case .bazarr(let offer): offer.providerName
                case .plex(let candidate): candidate.provider
                }
                let detail = ["From \(pending.added.source.rawValue)", provider].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                return FindSubtitlesReply(
                    banner: .init(tone: .found, title: "English subtitles on", detail: detail),
                    tracks: file.menu(media, part, tracks), trackId: track.id
                )
            }
            return nil
        } catch {
            try checkSearch(file, request)
            return FindSubtitlesReply(banner: .init(
                tone: .missing, title: "Couldn't check Plex", detail: "\(pending.added.source.rawValue) sent a file. Find again to look for it."
            ), tracks: changedMenu(since: file), awaitingFile: true)
        }
    }
}
