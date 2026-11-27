import Foundation
import MarqueeCore
import Synchronization

/// The calls behind Home, the library grids, Search and the Watchlist's list. Names match exactly, so a sibling
/// namespace such as `watchlist.add` stays with the file that owns it.
///
/// Every call but `watchlist.items` and `snapshot.remove` carries the usual server parameters. Payloads and replies
/// are in `BrowseWire.swift`; in short:
///
/// - `home.load {libraries?, hidden, dismissed?, mode: live|saved, snapshot?, showing?}` answers `{rows, late, run?,
///   savedAt?}`. Home shows the `saved` answer at once, then the `live` one. A live load still waiting on the shows
///   watched again answers `late` with a `run`; `home.follow {run, dismissed?}` then answers the finished rows, or none
///   when a newer load replaced the run.
/// - `home.catalogue` answers every row with its first three titles, for the Home Rows editor.
/// - `home.dismissRewatch` and `home.restoreRewatch {dismissed?, card}` answer the new `RewatchDismissals`, which the
///   app keeps per profile.
/// - `library.items {section, query, start, size?}` answers `{items, total}`; `library.alphabet {section, query}` the
///   letters with their counts and offsets; `library.genres`, `library.recommended`, `library.collections` and
///   `library.list {key, start, size?}` answer a section's genres, hubs, collections and a server list.
/// - `library.refetch {ratingKeys}` answers those items again, for a grid after a watched change.
/// - `library.removeFromContinueWatching {ratingKeys}` takes the first one off the row.
/// - `search.hubs {query, limit?}` answers hubs.
/// - `watchlist.items {token, start, size?, snapshot?, mode?}` answers `{items, total}` from plex.tv.
/// - `watchlist.released {token}` answers catalogue GUIDs after every released page succeeds.
/// - `snapshot.remove {directory, profile?}` forgets one profile's saved answers, or every profile's.
enum BrowseCalls {
    private static let names: Set<String> = [
        "home.load", "home.follow", "home.catalogue", "home.dismissRewatch", "home.restoreRewatch",
        "library.items", "library.alphabet", "library.genres", "library.collections", "library.recommended",
        "library.list", "library.refetch", "library.removeFromContinueWatching",
        "search.hubs", "watchlist.items", "watchlist.released",
        "snapshot.remove",
    ]

    /// Home's rows hold a hub per library; a row's few titles are enough to show it in the editor.
    private static let previewTitles = 3

    /// Shows the iPhone app lists first on Home: the watchlist shelf is its first twenty.
    private static let pageSize = 60

    static func handles(_ method: String) -> Bool {
        names.contains(method)
    }

    static func handle(_ method: String, _ payload: Data, using services: BrowseServices) async throws -> Data {
        switch method {
        case "home.load":
            return try await loadHome(decode(HomeLoad.self, payload), library: services.library(decode(ServerCall.self, payload)))
        case "home.follow":
            let call = try decode(HomeFollow.self, payload)
            guard let pending = homeRuns.take(call.run) else { return try Reply.ok(FollowReply(rows: nil)) }
            let rows = await HomeRows.following(
                pending.rows, through: pending.run, dismissed: call.dismissed ?? RewatchDismissals()
            ) ?? pending.rows
            return try Reply.ok(FollowReply(rows: rows.map { HomeRowWire($0) }))
        case "home.catalogue":
            let library = try services.library(decode(ServerCall.self, payload))
            let rows = try await HomeRows.load(from: library, libraries: nil)
            return try Reply.ok(rows.map { HomeRowWire($0, itemLimit: previewTitles) })
        case "home.dismissRewatch", "home.restoreRewatch":
            let call = try decode(RewatchChange.self, payload)
            let server = try decode(ServerCall.self, payload).server.clientIdentifier
            var dismissed = call.dismissed ?? RewatchDismissals()
            if let card = call.card.metadata {
                if method == "home.dismissRewatch" {
                    dismissed.dismiss(card, on: server)
                } else {
                    dismissed.restore(card, on: server)
                }
            }
            return try Reply.ok(dismissed)
        case "library.items":
            let library = try services.library(decode(ServerCall.self, payload))
            let call = try decode(ItemsCall.self, payload)
            let page = try await library.items(in: call.section, query: call.query.sectionQuery, start: call.start, size: call.size ?? pageSize)
            return try Reply.ok(PageWire(page))
        case "library.alphabet":
            let library = try services.library(decode(ServerCall.self, payload))
            let call = try decode(AlphabetCall.self, payload)
            return try Reply.ok(try await library.alphabetIndex(in: call.section, query: call.query.sectionQuery).map(LetterWire.init))
        case "library.genres":
            let library = try services.library(decode(ServerCall.self, payload))
            return try Reply.ok(try await library.genres(in: decode(SectionRef.self, payload).section).map(DirectoryWire.init))
        case "library.collections":
            let library = try services.library(decode(ServerCall.self, payload))
            let call = try decode(PagedSectionCall.self, payload)
            return try Reply.ok(PageWire(try await library.collections(in: call.section, start: call.start, size: call.size ?? pageSize)))
        case "library.recommended":
            let library = try services.library(decode(ServerCall.self, payload))
            return try Reply.ok(try await library.recommendedHubs(in: decode(SectionRef.self, payload).section).map(HubWire.init))
        case "library.list":
            let library = try services.library(decode(ServerCall.self, payload))
            let call = try decode(ListCall.self, payload)
            return try Reply.ok(PageWire(try await library.list(call.key, start: call.start, size: call.size ?? pageSize)))
        case "library.refetch":
            let library = try services.library(decode(ServerCall.self, payload))
            return try Reply.ok(await refetch(decode(RefetchCall.self, payload).ratingKeys, from: library).map(ItemWire.init))
        case "library.removeFromContinueWatching":
            let library = try services.library(decode(ServerCall.self, payload))
            try await library.removeFromContinueWatching(decode(RefetchCall.self, payload).ratingKeys.first ?? "")
            return try Reply.ok(Empty())
        case "search.hubs":
            let library = try services.library(decode(ServerCall.self, payload))
            let call = try decode(SearchCall.self, payload)
            return try Reply.ok(try await library.search(call.query, limit: call.limit ?? 10).map(HubWire.init))
        case "watchlist.released":
            let watchlist = try services.watchlist(decode(Account.self, payload).token)
            return try Reply.ok(try await releasedWatchlist(watchlist))
        case "watchlist.items":
            let call = try decode(WatchlistPageCall.self, payload)
            var watchlist = try services.watchlist(call.token)
            if let snapshot = call.snapshot {
                let mode: ResponseSnapshot.Mode = call.mode == .saved ? .replay : .record
                watchlist = watchlist.snapshotting(ResponseSnapshot(store: store(at: snapshot.directory), scope: snapshot.scope, mode: mode))
            }
            return try Reply.ok(PageWire(try await watchlist.items(start: call.start, size: call.size ?? pageSize)))
        case "snapshot.remove":
            let call = try decode(SnapshotRemoval.self, payload)
            if let profile = call.profile {
                store(at: call.directory).remove(profile: profile)
            } else {
                store(at: call.directory).removeAll()
            }
            return try Reply.ok(Empty())
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    /// Home's rows. A live load asks the server and records its answers; a saved one answers from the last
    /// recording alone, with nothing in the reply when none was made.
    private static func loadHome(_ call: HomeLoad, library: PlexLibrary) async throws -> Data {
        let libraries = call.libraries.map(Set.init)
        let hidden = Set(call.hidden)
        let dismissed = call.dismissed ?? RewatchDismissals()
        let spec = call.snapshot
        switch call.mode {
        case .saved:
            guard let spec else { return try Reply.ok(HomeReply(rows: [])) }
            let saved = ResponseSnapshot(store: store(at: spec.directory), scope: spec.scope, mode: .replay)
            let replay = library.snapshotting(saved)
            guard let plex = try? await HomeRows.load(from: replay, libraries: libraries, hidden: hidden),
                  let savedAt = saved.savedAt else { return try Reply.ok(HomeReply(rows: [])) }
            let rows = await HomeRows.following(plex, through: RewatchRun(library: replay, hidden: hidden), dismissed: dismissed) ?? plex
            return try Reply.ok(HomeReply(rows: rows.map { HomeRowWire($0) }, savedAt: savedAt.timeIntervalSince1970))
        case .live:
            homeRuns.cancel()
            let recording = spec.map {
                library.snapshotting(ResponseSnapshot(store: store(at: $0.directory), scope: $0.scope, mode: .record))
            } ?? library
            let run = RewatchRun(library: recording, hidden: hidden)
            let plex: [HomeRow]
            do {
                plex = try await HomeRows.load(from: recording, libraries: libraries, hidden: hidden)
            } catch {
                run.cancel()
                throw error
            }
            // The first look waits briefly for the shows watched again, so Home settles in one step; when
            // rows are on screen already the wait is longer, because the swap is then a visible change.
            let wait: Duration = call.showing == true ? .seconds(2) : .milliseconds(400)
            if let followed = await HomeRows.following(plex, through: run, dismissed: dismissed, within: wait) {
                return try Reply.ok(HomeReply(rows: followed.map { HomeRowWire($0) }))
            }
            let id = homeRuns.keep(run, rows: plex)
            return try Reply.ok(HomeReply(rows: plex.map { HomeRowWire($0) }, late: true, run: id))
        }
    }

    /// The titles' current records, in their order; a title the server no longer has is left out.
    private static func refetch(_ keys: [String], from library: PlexLibrary) async -> [PlexMetadata] {
        await withTaskGroup(of: (Int, PlexMetadata?).self) { group in
            for (index, key) in keys.enumerated() {
                group.addTask { (index, try? await library.metadata(key)) }
            }
            var items = [PlexMetadata?](repeating: nil, count: keys.count)
            for await (index, item) in group { items[index] = item }
            return items.compactMap { $0 }
        }
    }

    /// One store per folder, so a recording and the reads after it share their bookkeeping.
    private static func store(at directory: String) -> SnapshotStore {
        stores.withLock { stores in
            if let store = stores[directory] { return store }
            let store = SnapshotStore(directory: URL(fileURLWithPath: directory, isDirectory: true))
            stores[directory] = store
            return store
        }
    }

    private static let stores = Mutex<[String: SnapshotStore]>([:])
    private static let homeRuns = HomeRuns()

    private static func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }
}

/// What the browse calls reach: a server's library, and the account's watchlist by its plex.tv token.
struct BrowseServices: Sendable {
    let library: @Sendable (ServerCall) throws -> PlexLibrary
    let watchlist: @Sendable (String) throws -> PlexWatchlist
}

extension Methods {
    var browseServices: BrowseServices {
        BrowseServices(library: { try self.library(for: $0) }, watchlist: { try self.watchlist(token: $0) })
    }
}

/// The shows-watched-again read that outlasted Home's wait, kept for `home.follow`. Home is one screen, so one
/// run is pending at a time; a newer load ends the older.
private final class HomeRuns: Sendable {
    struct Pending: Sendable {
        let id: String
        let run: RewatchRun
        let rows: [HomeRow]
    }

    private let pending = Mutex<Pending?>(nil)

    func keep(_ run: RewatchRun, rows: [HomeRow]) -> String {
        let next = Pending(id: UUID().uuidString, run: run, rows: rows)
        pending.withLock { $0 }?.run.cancel()
        pending.withLock { $0 = next }
        return next.id
    }

    func take(_ id: String) -> Pending? {
        pending.withLock { held in
            guard held?.id == id else { return nil }
            defer { held = nil }
            return held
        }
    }

    func cancel() {
        pending.withLock { held in
            held?.run.cancel()
            held = nil
        }
    }
}
