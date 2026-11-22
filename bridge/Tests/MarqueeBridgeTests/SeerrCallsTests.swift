import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

/// The Seerr calls against the FakePlex Seerr fixtures (DESIGN §8.8): what Kotlin reads for Discover, search, a
/// title page and My Requests, and the sign-in cookie a Plex profile's first call hands back.
@Suite struct SeerrCallsTests {
    /// Serves `core/UITests/FakePlex/seerr`, with its `@query=` and `@after=` variants.
    private final class Fixtures: PlexTransport, Sendable {
        private let lastWrite = Mutex<String?>(nil)
        private let cookies: Bool
        let sent = Mutex<[PlexRequest]>([])

        init(cookies: Bool = false) {
            self.cookies = cookies
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            sent.withLock { $0.append(request) }
            let path = request.url.path
            let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appending(path: "../../../core/UITests/FakePlex/seerr")
            if request.method != .get {
                lastWrite.withLock { $0 = path.split(separator: "/").joined(separator: "_") }
            }
            var candidates: [String] = []
            if request.method == .get, let write = lastWrite.withLock({ $0 }) { candidates.append("\(path)@after=\(write).json") }
            for item in request.url.query?.split(separator: "&") ?? [] {
                candidates.append("\(path)@\(item).json")
            }
            candidates.append("\(path).json")
            // Signing in answers with the user, as `auth/me` does.
            if path == "/api/v1/auth/plex" { candidates.append("/api/v1/auth/me.json") }
            var headers: [String: String] = [:]
            if cookies, path == "/api/v1/auth/plex" { headers["Set-Cookie"] = "connect.sid=s%3Aabc; Path=/; HttpOnly" }
            for name in candidates {
                if let data = try? Data(contentsOf: root.appending(path: name)) {
                    return PlexHTTPResponse(status: 200, body: data, headers: headers)
                }
            }
            return PlexHTTPResponse(status: 404, body: Data(#"{"message":"missing"}"#.utf8))
        }
    }

    private static let key = String(repeating: "k", count: 40)

    private func link(key: String? = key, session: String? = nil) -> [String: Any] {
        var link: [String: Any] = ["address": "http://seerr.local:5055"]
        if let key { link["apiKey"] = key } else { link["plexToken"] = "plex-token" }
        if let session { link["session"] = session }
        return link
    }

    private func call(_ method: String, _ params: [String: Any] = [:], link: [String: Any]? = nil, on transport: Fixtures, clients: SeerrClients = SeerrClients()) async throws -> [String: Any] {
        let payload = try JSONSerialization.data(withJSONObject: (link ?? self.link()).merging(params) { $1 })
        let data = try await SeerrCalls.handle(method, payload, through: transport, clients: clients)
        let reply = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(reply["ok"] as? [String: Any])
    }

    @Test func parseNamesWhatIsWrongWithAnAddress() async throws {
        let transport = Fixtures()
        func problem(_ address: String, _ key: String? = nil) async throws -> String? {
            var params: [String: Any] = ["address": address]
            params["apiKey"] = key
            let payload = try JSONSerialization.data(withJSONObject: params)
            let data = try await SeerrCalls.handle("seerr.parse", payload, through: transport, clients: SeerrClients())
            return ((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["ok"] as? [String: Any])?["problem"] as? String
        }
        #expect(try await problem("seerr.local") == "scheme")
        #expect(try await problem("https://") == "host")
        #expect(try await problem("http://requests.example.com") == "plaintext")
        #expect(try await problem("http://seerr.local:5055", "short") == "key")
        #expect(try await problem("http://seerr.local:5055", Self.key) == nil)
        #expect(try await problem("https://requests.example.com") == nil)
    }

    @Test func theCatalogueListsTheDiscoverRowsInOrder() async throws {
        let payload = Data("{}".utf8)
        let data = try await SeerrCalls.handle("seerr.catalogue", payload, through: Fixtures(), clients: SeerrClients())
        let ok = try #require((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["ok"] as? [String: Any])
        let rows = try #require(ok["rows"] as? [[String: Any]])
        #expect(rows.map { $0["id"] as? String } == [
            "trending", "popularMovies", "genres.movie", "upcomingMovies", "popularSeries", "genres.tv", "upcomingSeries",
        ])
        #expect(rows[0]["title"] as? String == "Trending")
        #expect((rows[0]["feed"] as? [String: Any])?["trending"] as? Bool == true)
        #expect(((rows[3]["feed"] as? [String: Any])?["query"] as? [String: Any])?["upcoming"] as? Bool == true)
    }

    @Test func aKeySignsInAndASearchFindsRequestableTitles() async throws {
        let transport = Fixtures()
        let clients = SeerrClients()
        let user = try await call("seerr.signIn", on: transport, clients: clients)
        #expect((user["value"] as? [String: Any])?["name"] as? String == "Jordan")
        #expect(user["session"] == nil)
        let found = try await call("seerr.search", ["text": "Wing"], on: transport, clients: clients)
        let titles = try #require(found["value"] as? [[String: Any]])
        #expect(titles.first?["id"] as? String == "movie/9101")
        #expect(titles.first?["badge"] == nil)
        #expect(transport.sent.withLock { $0.allSatisfy { $0.headers["X-Api-Key"] == Self.key } })
    }

    @Test func aRequestFlipsTheTitleToPendingAndShowsInMyRequests() async throws {
        let transport = Fixtures()
        let clients = SeerrClients()
        let params: [String: Any] = ["kind": "movie", "tmdbID": 9101]
        let before = try #require(try await call("seerr.details", params, on: transport, clients: clients)["value"] as? [String: Any])
        #expect(before["canRequest"] as? Bool == true)
        #expect(before["status"] == nil)
        _ = try await call("seerr.request", ["kind": "movie", "tmdbID": 9101, "is4K": false], on: transport, clients: clients)
        let after = try #require(try await call("seerr.details", params, on: transport, clients: clients)["value"] as? [String: Any])
        #expect(((after["status"] as? [String: Any])?["badge"] as? [String: Any])?["name"] as? String == "pending")
        #expect(after["canRequest"] as? Bool == false)
        let mine = try #require(try await call("seerr.requests", on: transport, clients: clients)["value"] as? [[String: Any]])
        #expect(mine.map { $0["id"] as? Int } == [502, 501])
    }

    @Test func aPlexProfileSignsInOnceAndHandsBackItsCookie() async throws {
        let transport = Fixtures(cookies: true)
        let clients = SeerrClients()
        let first = try await call("seerr.signIn", link: link(key: nil), on: transport, clients: clients)
        #expect(first["session"] as? String == "connect.sid=s%3Aabc")
        let second = try await call("seerr.me", link: link(key: nil), on: transport, clients: clients)
        #expect(second["session"] == nil)
        #expect(transport.sent.withLock { $0.filter { $0.url.path == "/api/v1/auth/plex" }.count } == 1)
    }

    @Test func aKeptCookieIsSentWithoutSigningIn() async throws {
        let transport = Fixtures(cookies: true)
        _ = try await call("seerr.me", link: link(key: nil, session: "connect.sid=kept"), on: transport)
        #expect(transport.sent.withLock { $0.allSatisfy { $0.url.path != "/api/v1/auth/plex" && $0.headers["Cookie"] == "connect.sid=kept" } })
    }

    @Test func aRefusalReachesKotlinAsItsSeerrFailure() async throws {
        let payload = try JSONSerialization.data(withJSONObject: link().merging(["kind": "movie", "tmdbID": 1]) { $1 })
        await #expect(throws: SeerrError.notFound) {
            try await SeerrCalls.handle("seerr.details", payload, through: Fixtures(), clients: SeerrClients())
        }
        let failure = try #require(JSONSerialization.jsonObject(with: Reply.failure(SeerrError.badKey)) as? [String: Any])
        #expect((failure["error"] as? [String: Any])?["kind"] as? String == "seerr")
        #expect((failure["error"] as? [String: Any])?["failure"] as? String == "badKey")
    }
}
