import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

/// A title's delete in two calls, and the settings pages for Radarr and Sonarr, on a fake network where Plex, Radarr and
/// Seerr each answer by their address and every request is kept.
@Suite struct DeleteCallsTests {
    private final class Network: PlexTransport, Sendable {
        let sent = Mutex<[PlexRequest]>([])
        /// What Radarr answers to removing the movie.
        let radarrRemoval = Mutex(200)

        var hosts: Set<String> {
            Set(sent.withLock { $0.compactMap(\.url.host) })
        }

        func requests(to host: String, _ method: HTTPMethod) -> [PlexRequest] {
            sent.withLock { $0.filter { $0.url.host == host && $0.method == method } }
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            sent.withLock { $0.append(request) }
            switch (request.url.host, request.method, request.url.path) {
            case ("192.0.2.10", .get, "/identity"):
                return Self.container(["machineIdentifier": "machine-1"])
            case ("192.0.2.10", .get, "/library/metadata/603"):
                return Self.container(["Metadata": [[
                    "ratingKey": "603", "type": "movie", "title": "The Matrix", "year": 1999, "guid": "plex://movie/5d7768",
                    "Guid": [["id": "imdb://tt0133093"], ["id": "tmdb://603"]],
                ]]])
            case ("192.0.2.10", .delete, "/library/metadata/603"):
                return PlexHTTPResponse(status: 200, body: Data())
            case ("radarr.local", .get, "/api/v3/movie"):
                return PlexHTTPResponse(status: 200, body: try JSONSerialization.data(withJSONObject: [["id": 7]]))
            case ("radarr.local", .delete, "/api/v3/movie/7"):
                return PlexHTTPResponse(status: radarrRemoval.withLock { $0 }, body: Data())
            case ("seerr.local", .get, "/api/v1/movie/603"):
                return PlexHTTPResponse(status: 200, body: try JSONSerialization.data(withJSONObject: ["mediaInfo": ["id": 9]]))
            case ("seerr.local", .delete, "/api/v1/media/9"):
                return PlexHTTPResponse(status: 204, body: Data())
            default:
                return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        private static func container(_ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }
    }

    private static let plexServer = PlexServer(
        name: "Home", clientIdentifier: "machine-1", accessToken: "token",
        connections: [PlexConnection(uri: URL(string: "http://192.0.2.10:32400")!, isLocal: true)]
    )
    private static var radarr: [String: Any] { ["address": "http://radarr.local:7878", "apiKey": String(repeating: "r", count: 32)] }
    private static var seerr: [String: Any] { ["address": "http://seerr.local:5055", "apiKey": String(repeating: "k", count: 40)] }
    private static var item: [String: Any] {
        ["ratingKey": "603", "type": "movie", "title": "The Matrix", "year": 1999, "guid": "plex://movie/5d7768"]
    }
    private static var ticket: [String: Any] {
        ["ratingKey": "603", "kind": "movie", "title": "The Matrix", "year": 1999, "guid": "plex://movie/5d7768", "tmdbID": 603]
    }

    private func query(_ request: PlexRequest, _ name: String) -> String? {
        URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
    }

    /// The `value` of the reply to `method`, whose payload names the server beside `fields`.
    private func call(_ method: String, _ network: Network, _ fields: [String: Any]) async throws -> [String: Any] {
        var payload = fields
        payload["server"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(Self.plexServer))
        let library = PlexLibrary(
            server: Self.plexServer,
            identity: PlexClientIdentity(clientIdentifier: "client-1", version: "1.0", platformVersion: "14", device: "Pad", deviceName: "Pad"),
            transport: network
        )
        let reply = try await DeleteCalls.handle(
            method, JSONSerialization.data(withJSONObject: payload), library: { _ in library },
            watchlist: { _ in throw BridgeError.notConfigured }, through: network, clients: SeerrClients(), sleep: { _ in }
        )
        let object = try #require(JSONSerialization.jsonObject(with: reply) as? [String: Any])
        return try #require((object["ok"] as? [String: Any])?["value"] as? [String: Any])
    }

    @Test func theServerStepAsksPlexAloneAndHandsBackTheTicket() async throws {
        let network = Network()
        let value = try await call("detail.delete", network, ["item": Self.item, "radarr": Self.radarr, "seerr": Self.seerr])
        #expect(value["outcome"] as? String == "deleted")
        let ticket = try #require(value["ticket"] as? [String: Any])
        #expect(ticket["tmdbID"] as? Int == 603)
        #expect(ticket["guid"] as? String == "plex://movie/5d7768")
        #expect(network.requests(to: "192.0.2.10", .delete).count == 1)
        #expect(network.hosts == ["192.0.2.10"])
    }

    @Test func theCleanUpRemovesTheTitleFromRadarrAndSeerrAndNamesEachStep() async throws {
        let network = Network()
        let value = try await call(
            "detail.cleanUp", network, ["ticket": Self.ticket, "alreadyDeleted": ["603"], "radarr": Self.radarr, "seerr": Self.seerr])
        #expect(value["outcome"] as? String == "deleted")
        #expect(value["watchlist"] as? String == "unchanged")
        #expect(value["arr"] as? String == "removed")
        #expect(value["requests"] as? String == "removed")
        #expect(value["ticket"] == nil)
        let removal = try #require(network.requests(to: "radarr.local", .delete).first)
        #expect(query(removal, "deleteFiles") == "true")
        #expect(query(removal, "addImportExclusion") == "true")
        #expect(network.requests(to: "seerr.local", .delete).count == 1)
        #expect(!network.hosts.contains("192.0.2.10"))
    }

    @Test func aTitleWithNoServiceConnectedMakesNoRequest() async throws {
        let network = Network()
        let value = try await call("detail.cleanUp", network, ["ticket": Self.ticket, "alreadyDeleted": [String]()])
        #expect(value["arr"] as? String == "notConnected")
        #expect(value["requests"] as? String == "notConnected")
        #expect(network.sent.withLock { $0.isEmpty })
    }

    @Test func aStepThatFailedComesBackWithTheTicketAndRetryRepeatsOnlyThatStep() async throws {
        let network = Network()
        network.radarrRemoval.withLock { $0 = 500 }
        let fields: [String: Any] = ["ticket": Self.ticket, "alreadyDeleted": [String](), "radarr": Self.radarr, "seerr": Self.seerr]
        let first = try await call("detail.cleanUp", network, fields)
        #expect(first["arr"] as? String == "failed")
        #expect(first["requests"] as? String == "removed")
        let ticket = try #require(first["ticket"] as? [String: Any])

        network.radarrRemoval.withLock { $0 = 200 }
        var retry = fields
        retry["ticket"] = ticket
        retry["previous"] = ["watchlist": "unchanged", "arr": "failed", "requests": "removed"]
        let second = try await call("detail.cleanUp", network, retry)
        #expect(second["arr"] as? String == "removed")
        #expect(second["requests"] as? String == "removed")
        #expect(second["ticket"] == nil)
        #expect(network.requests(to: "seerr.local", .delete).count == 1)
    }
}

/// Radarr's and Sonarr's address rules and connection test.
@Suite struct ArrCallsTests {
    /// Answers the status call as Radarr, or as the given status.
    private final class Service: PlexTransport, Sendable {
        let status: Int
        let offline: Bool

        init(status: Int = 200, offline: Bool = false) {
            self.status = status
            self.offline = offline
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            if offline { throw NetworkFailure.other }
            return PlexHTTPResponse(status: status, body: Data(#"{"version":"5.0.1","appName":"Radarr"}"#.utf8))
        }
    }

    private static let key = String(repeating: "r", count: 32)

    private func call(_ method: String, kind: String = "radarr", address: String = "http://radarr.local:7878", key: String = key, on service: Service = Service()) async throws -> [String: Any] {
        let payload = try JSONSerialization.data(withJSONObject: ["kind": kind, "address": address, "key": key])
        let reply = try await ArrCalls.handle(method, payload, through: service)
        return try #require((try JSONSerialization.jsonObject(with: reply) as? [String: Any])?["ok"] as? [String: Any])
    }

    @Test func theAddressNamesWhatIsWrongWithIt() async throws {
        #expect(try await call("arr.address", address: "radarr.local")["problem"] as? String == "scheme")
        #expect(try await call("arr.address", address: "http://radarr.example.com")["problem"] as? String == "plaintext")
        #expect(try await call("arr.address", key: "short")["problem"] as? String == "key")
        let fine = try await call("arr.address")
        #expect(fine["problem"] == nil)
        #expect(fine["address"] as? String == "http://radarr.local:7878")
    }

    @Test func theStatusProvesTheAddressAndTheKeyTogether() async throws {
        #expect(try await call("arr.status")["version"] as? String == "5.0.1")
        #expect(try await call("arr.status", kind: "sonarr")["problem"] as? String == "notArr")
        #expect(try await call("arr.status", on: Service(status: 401))["problem"] as? String == "badKey")
        #expect(try await call("arr.status", on: Service(offline: true))["problem"] as? String == "unreachable")
    }
}
