import Foundation
import MarqueeCore
import Testing

@testable import MarqueeBridge

@Suite struct DownloadWatchSyncTests {
    @Test(arguments: [nil, "", "local://11", "plex://movie/previous"] as [String?])
    func unprovenTitleNeverReceivesWatchOrResume(guid: String?) async throws {
        let server = WatchServer()
        let reply = try await sync(try pendingRewatch(), guid: guid, on: server)
        let watch = try #require(reply["watch"] as? [String: Any])
        #expect(reply["changedServer"] as? Bool == false)
        #expect(watch["unsynced"] == nil && watch["offset"] as? Int == 48_000)
        #expect(await server.writes.isEmpty)
    }

    @Test func acknowledgedWatchDoesNotDiscardResumeOnRetry() async throws {
        let server = WatchServer(failFirstResume: true)
        let written = try await sync(try pendingRewatch(), on: server)
        let receipt = try #require(written["watch"] as? [String: Any])
        #expect(written["changedServer"] as? Bool == true)
        #expect(receipt["finishedUnsynced"] as? Bool == false)
        #expect(receipt["scrobble"] != nil && receipt["unsynced"] != nil)
        #expect(await server.writes == ["/:/scrobble"])

        await #expect(throws: PlexError.server(status: 503, message: nil)) {
            _ = try await sync(receipt, on: server)
        }
        let replay = try await sync(receipt, on: server)
        let watch = try #require(replay["watch"] as? [String: Any])
        #expect(replay["changedServer"] as? Bool == true)
        #expect(watch["unsynced"] == nil && watch["scrobble"] == nil)
        #expect(await server.writes == ["/:/scrobble", "/:/timeline", "/:/timeline"])
        #expect(await server.offset == 48_000)
    }

    @Test func newerViewingElsewhereStillWinsAfterAcknowledgment() async throws {
        let server = WatchServer()
        let written = try await sync(try pendingRewatch(), on: server)
        let receipt = try #require(written["watch"] as? [String: Any])
        await server.continueOnAnotherDevice()

        let reply = try await sync(receipt, on: server)
        let watch = try #require(reply["watch"] as? [String: Any])
        #expect(reply["changedServer"] as? Bool == false)
        #expect(watch["unsynced"] == nil && watch["offset"] as? Int == 75_000)
        #expect(await server.writes == ["/:/scrobble"])
    }

    private func pendingRewatch() throws -> [String: Any] {
        var watch = OfflineWatch()
        watch.finished(at: Date(timeIntervalSince1970: 1_700_000_000), told: false)
        watch.played(to: 48_000, at: Date(timeIntervalSince1970: 1_700_000_100))
        return try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(watch)) as? [String: Any])
    }

    private func sync(_ watch: [String: Any], guid: String? = "plex://movie/original", on transport: WatchServer) async throws -> [String: Any] {
        let server = PlexServer(
            name: "Fixture", clientIdentifier: "machine", accessToken: "fixture",
            connections: [PlexConnection(uri: URL(string: "http://plex.invalid:32400")!, isLocal: true)]
        )
        let library = PlexLibrary(
            server: server,
            identity: PlexClientIdentity(clientIdentifier: "test", version: "1", platformVersion: "14", device: "Pad", deviceName: "Pad"),
            transport: transport
        )
        var payload: [String: Any] = [
            "server": try JSONSerialization.jsonObject(with: JSONEncoder().encode(server)),
            "ratingKey": "11", "duration": 120_000, "watch": watch,
        ]
        if let guid { payload["guid"] = guid }
        let answer = try await DownloadCalls.handle("download.watch.sync", JSONSerialization.data(withJSONObject: payload)) { _ in library }
        return try #require((JSONSerialization.jsonObject(with: answer) as? [String: Any])?["ok"] as? [String: Any])
    }

    private actor WatchServer: PlexTransport {
        private(set) var writes: [String] = []
        private(set) var offset = 0
        private var viewed = 1_699_999_999
        private var count = 0
        private var failFirstResume: Bool

        init(failFirstResume: Bool = false) { self.failFirstResume = failFirstResume }

        func continueOnAnotherDevice() {
            offset = 75_000
            viewed += 1
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            switch request.url.path {
            case "/identity":
                return try answer(["machineIdentifier": "machine"])
            case "/library/metadata/11":
                return try answer(["Metadata": [[
                    "ratingKey": "11", "key": "/library/metadata/11", "guid": "plex://movie/original",
                    "type": "movie", "title": "Download", "viewOffset": offset, "viewCount": count, "lastViewedAt": viewed,
                ]]])
            case "/:/scrobble":
                writes.append(request.url.path)
                viewed = Int(Date().timeIntervalSince1970)
                count += 1
                offset = 0
                return PlexHTTPResponse(status: 200, body: Data())
            case "/:/timeline":
                writes.append(request.url.path)
                if failFirstResume {
                    failFirstResume = false
                    return PlexHTTPResponse(status: 503, body: Data())
                }
                let time = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "time" }?.value
                offset = time.flatMap(Int.init) ?? 0
                return PlexHTTPResponse(status: 200, body: Data())
            default:
                return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        private func answer(_ data: [String: Any]) throws -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try JSONSerialization.data(withJSONObject: ["MediaContainer": data]))
        }
    }
}
