import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

@Suite struct HomeLabberCallsTests {
    private static let token = String(repeating: "t", count: 24)

    private final class Server: PlexTransport, Sendable {
        private let log = Mutex<[PlexRequest]>([])
        private let scored: Bool
        private let reachable: Bool

        init(scored: Bool = true, reachable: Bool = true) {
            self.scored = scored
            self.reachable = reachable
        }

        var requests: [PlexRequest] { log.withLock { $0 } }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            log.withLock { $0.append(request) }
            guard reachable else { throw NetworkFailure.offline }
            if request.url.path.hasSuffix("/status") {
                return Self.json(["enabled": true, "scored": 120, "total": 239, "running": true])
            }
            guard scored else { return PlexHTTPResponse(status: 404, body: Data()) }
            return Self.json([
                "ratingKey": "301", "mediaId": 1,
                "tiers": ["high": ["kbps": 7400, "vmaf": 95.2, "height": 1080]],
            ])
        }

        private static func json(_ value: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: (try? JSONSerialization.data(withJSONObject: value)) ?? Data())
        }
    }

    private func call(_ name: String, _ fields: [String: String], on server: Server) async throws -> [String: Any] {
        let reply = try await HomeLabberCalls.handle(name, JSONSerialization.data(withJSONObject: fields), transport: server)
        return try #require((JSONSerialization.jsonObject(with: reply) as? [String: Any])?["ok"] as? [String: Any])
    }

    @Test func theAddressIsCheckedAndTheConnectionTestReadsStatusWithTheTokenOnOneOrigin() async throws {
        let server = Server()
        let plain = try await call("homelabber.address", ["address": "http://homelabber.example.test", "token": Self.token], on: server)
        #expect(plain["problem"] as? String == "plaintext")
        let short = try await call("homelabber.address", ["address": "https://homelabber.local", "token": "short"], on: server)
        #expect(short["problem"] as? String == "token")
        let valid = try await call("homelabber.address", ["address": "https://homelabber.local/hl", "token": Self.token], on: server)
        #expect(valid["address"] as? String == "https://homelabber.local/hl")

        let status = try await call("homelabber.status", ["address": "https://homelabber.local/hl", "token": Self.token], on: server)
        #expect(status["scored"] as? Int == 120 && status["total"] as? Int == 239 && status["running"] as? Bool == true)
        let request = try #require(server.requests.first)
        #expect(request.url.path == "/hl/api/media-quality/v1/status")
        #expect(request.headers["Authorization"] == "Bearer \(Self.token)")
        #expect(request.restrictToOrigin)

        let down = try await call("homelabber.status", ["address": "https://homelabber.local", "token": Self.token], on: Server(reachable: false))
        #expect(down["problem"] as? String == "unreachable")
    }

    @Test func aTitleIsAskedAboutOnceAndAFailurePausesTheAsking() async throws {
        let config = try #require(try? HomeLabberConfig.parse(address: "https://homelabber.local", token: Self.token).get())
        let maps = HomeLabberMaps()
        let server = Server()
        let first = await maps.entry(config: config, server: "machine-1", ratingKey: "301", transport: server)
        let again = await maps.entry(config: config, server: "machine-1", ratingKey: "301", transport: server)
        #expect(first?.mediaID == "1" && again == first)
        #expect(server.requests.count == 1)
        #expect(server.requests.first?.url.path == "/api/media-quality/v1/servers/machine-1/items/301")
        let none = await maps.entry(config: nil, server: "machine-1", ratingKey: "301", transport: server)
        #expect(none == nil && server.requests.count == 1)

        let unscored = Server(scored: false)
        let other = HomeLabberMaps()
        _ = await other.entry(config: config, server: "machine-1", ratingKey: "9", transport: unscored)
        _ = await other.entry(config: config, server: "machine-1", ratingKey: "9", transport: unscored)
        #expect(unscored.requests.count == 1)

        let down = Server(reachable: false)
        let quiet = HomeLabberMaps()
        let now = Date(timeIntervalSince1970: 1_000)
        _ = await quiet.entry(config: config, server: "machine-1", ratingKey: "1", transport: down, now: now)
        _ = await quiet.entry(config: config, server: "machine-1", ratingKey: "2", transport: down, now: now.addingTimeInterval(30))
        #expect(down.requests.count == 1)
        _ = await quiet.entry(config: config, server: "machine-1", ratingKey: "2", transport: down, now: now.addingTimeInterval(61))
        #expect(down.requests.count == 2)
    }
}
