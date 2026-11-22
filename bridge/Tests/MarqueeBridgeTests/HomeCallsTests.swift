import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

/// Home's calls on a scripted server (DESIGN §8.1): the rows it lists, the shows watched again among Continue
/// Watching's cards, and the copy kept on disk.
@Suite struct HomeCalls {
    /// Answers Home's reads: two libraries, a movie in progress, and a show whose episode 3·1 was watched an hour ago.
    private final class Server: PlexTransport, Sendable {
        static let viewed = Int(Date().timeIntervalSince1970) - 3600

        private let held: Gate?
        private let offline = Mutex(false)

        /// `held` keeps the read of recent views waiting until it opens.
        init(held: Gate? = nil) {
            self.held = held
        }

        func goOffline() {
            offline.withLock { $0 = true }
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            if offline.withLock({ $0 }) { throw NetworkFailure.other }
            switch request.url.path {
            case "/identity":
                return Self.answer(["machineIdentifier": "machine-1"])
            case "/library/sections":
                return Self.answer(["Directory": [
                    ["key": "1", "title": "Movies", "type": "movie", "uuid": "uuid-m"],
                    ["key": "2", "title": "Shows", "type": "show", "uuid": "uuid-s"],
                ]])
            case "/hubs/promoted":
                return Self.answer(["Hub": [Self.hub("home.recent", "Recently Added", [Self.movie("10", "Big Buck Bunny")])]])
            case "/hubs/continueWatching":
                var started = Self.movie("11", "Sintel")
                started["viewOffset"] = 1000
                started["lastViewedAt"] = Self.viewed - 7200
                var hub = Self.hub("home.continue", "Continue Watching", [started])
                hub["style"] = "hero"
                return Self.answer(["Hub": [hub]])
            case "/hubs/sections/1":
                return Self.answer(["Hub": [Self.hub("movie.toprated", "Top Rated", [Self.movie("12", "Tears of Steel")])]])
            case "/library/sections/2/all":
                await held?.wait()
                return Self.answer(["totalSize": 1, "Metadata": [Self.episode(1, viewed: true)]])
            case "/library/metadata/100-s3/children":
                return Self.answer(["Metadata": [Self.episode(1), Self.episode(2)]])
            default:
                return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        static func answer(_ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }

        static func movie(_ key: String, _ title: String) -> [String: Any] {
            ["ratingKey": key, "type": "movie", "title": title, "librarySectionID": 1]
        }

        static func hub(_ identifier: String, _ title: String, _ items: [[String: Any]]) -> [String: Any] {
            ["hubIdentifier": identifier, "title": title, "type": "movie", "size": items.count, "Metadata": items]
        }

        static func episode(_ number: Int, viewed: Bool = false) -> [String: Any] {
            var fields: [String: Any] = [
                "ratingKey": "100-3-\(number)", "type": "episode", "title": "Episode \(number)", "duration": 1_320_000,
                "grandparentRatingKey": "100", "grandparentTitle": "Show", "grandparentGuid": "plex://show/100",
                "parentRatingKey": "100-s3", "parentIndex": 3, "index": number,
            ]
            if viewed {
                fields["lastViewedAt"] = Self.viewed
                fields["viewCount"] = 1
            }
            return fields
        }
    }

    /// Opened once; everyone waiting continues.
    private actor Gate {
        private var isOpen = false
        private var waiters: [CheckedContinuation<Void, Never>] = []

        func wait() async {
            if isOpen { return }
            await withCheckedContinuation { waiters.append($0) }
        }

        func open() {
            isOpen = true
            waiters.forEach { $0.resume() }
            waiters = []
        }
    }

    private static let plexServer = PlexServer(
        name: "Home", clientIdentifier: "machine-1", accessToken: "token",
        connections: [PlexConnection(uri: URL(string: "http://192.0.2.10:32400")!, isLocal: true)]
    )

    private let snapshots = FileManager.default.temporaryDirectory.appending(path: "marquee-bridge-\(UUID().uuidString)", directoryHint: .isDirectory)

    private func services(_ server: Server) -> BrowseServices {
        let library = PlexLibrary(
            server: Self.plexServer,
            identity: PlexClientIdentity(clientIdentifier: "client-1", version: "1.0", platformVersion: "14", device: "Pad", deviceName: "Pad"),
            transport: server
        )
        return BrowseServices(library: { _ in library }, watchlist: { _ in throw BridgeError.notConfigured })
    }

    /// The reply to `method`, whose payload names the server beside `fields`.
    private func call(_ method: String, _ server: Server, _ fields: [String: Any]) async throws -> Any {
        var payload = fields
        payload["server"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(Self.plexServer))
        let reply = try await BrowseCalls.handle(method, JSONSerialization.data(withJSONObject: payload), using: services(server))
        let object = try #require(JSONSerialization.jsonObject(with: reply) as? [String: Any])
        return try #require(object["ok"])
    }

    private func load(_ server: Server, mode: String = "live", dismissed: Any? = nil, saving profile: String? = nil) async throws -> [String: Any] {
        var fields: [String: Any] = ["libraries": ["uuid-m"], "hidden": [String](), "mode": mode]
        if let profile { fields["snapshot"] = ["directory": snapshots.path, "server": "machine-1", "profile": profile] }
        if let dismissed { fields["dismissed"] = dismissed }
        return try #require(await call("home.load", server, fields) as? [String: Any])
    }

    private func titles(_ row: [String: Any]) -> [String] {
        let hub = row["hub"] as? [String: Any]
        return (hub?["items"] as? [[String: Any]] ?? []).compactMap { $0["ratingKey"] as? String }
    }

    private func rows(_ reply: [String: Any]) -> [[String: Any]] {
        reply["rows"] as? [[String: Any]] ?? []
    }

    @Test func showWatchedAgainJoinsContinueWatchingAndLibraryRowsFollow() async throws {
        let reply = try await load(Server())
        let rows = rows(reply)

        #expect(reply["late"] as? Bool != true)
        #expect(rows.map { $0["id"] as? String } == ["home.continue", "home.recent", "uuid-m/movie.toprated"])
        #expect(rows.map { $0["continueWatching"] as? Bool ?? false } == [true, false, false])
        // The episode watched an hour ago is newer than the movie started two hours before it.
        #expect(titles(rows[0]) == ["100-3-2", "11"])
        #expect(rows[2]["title"] as? String == "Top Rated in Movies")
        #expect((rows[2]["library"] as? [String: Any])?["id"] as? String == "uuid-m")
    }

    @Test func slowReadOfViewsLeavesPlexsRowAndFollowCompletesIt() async throws {
        let gate = Gate()
        let server = Server(held: gate)

        let reply = try await load(server)
        let run = try #require(reply["run"] as? String)

        #expect(reply["late"] as? Bool == true)
        #expect(titles(rows(reply)[0]) == ["11"])

        await gate.open()
        let followed = try #require(await call("home.follow", server, ["run": run]) as? [String: Any])
        #expect(titles(rows(followed)[0]) == ["100-3-2", "11"])

        // The run was taken, so a second follow has nothing to finish.
        let again = try #require(await call("home.follow", server, ["run": run]) as? [String: Any])
        #expect(again["rows"] == nil)
    }

    @Test func savedHomeAnswersFromTheLastRecordingAlone() async throws {
        let server = Server()
        let live = try await load(server, saving: "jordan")
        server.goOffline()

        // The recording is written by tasks that outlast the load.
        var saved = try await load(server, mode: "saved", saving: "jordan")
        var attempts = 0
        while rows(saved).count < rows(live).count || titles(rows(saved)[0]) != ["100-3-2", "11"], attempts < 200 {
            attempts += 1
            try await Task.sleep(for: .milliseconds(10))
            saved = try await load(server, mode: "saved", saving: "jordan")
        }

        #expect(rows(saved).map { $0["id"] as? String } == rows(live).map { $0["id"] as? String })
        #expect(titles(rows(saved)[0]) == ["100-3-2", "11"])
        #expect((saved["savedAt"] as? Double ?? 0) > 0)

        let other = try await load(server, mode: "saved", saving: "alex")
        #expect(rows(other).isEmpty && other["savedAt"] == nil)
        try? FileManager.default.removeItem(at: snapshots)
    }

    @Test func dismissedCardStaysOffTheRowUntilRestored() async throws {
        let server = Server()
        let card: [String: Any] = [
            "ratingKey": "100-3-2", "grandparentRatingKey": "100", "grandparentGuid": "plex://show/100",
            "rewatchedAt": Server.viewed,
        ]

        let dismissed = try await call("home.dismissRewatch", server, ["card": card])
        #expect(titles(rows(try await load(server, dismissed: dismissed))[0]) == ["11"])

        let restored = try await call("home.restoreRewatch", server, ["card": card, "dismissed": dismissed])
        #expect(titles(rows(try await load(server, dismissed: restored))[0]) == ["100-3-2", "11"])
    }
}
