import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

@Suite struct DownloadCallsTests {
    @Test func localPlanUsesTheConvertedFileSavedCaptionsAndOfflineOffset() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let snapshot = directory.appendingPathComponent("metadata.json")
        let decision = directory.appendingPathComponent("decision.json")
        let subtitle = directory.appendingPathComponent("saved subtitle.srt")
        let movie = directory.appendingPathComponent("media")
        try Self.snapshot(container: "mkv", streamID: 42, external: true).write(to: snapshot)
        try Self.snapshot(container: "mp4", streamID: 73, external: false).write(to: decision)

        let services = PlayServices(
            library: { _ in throw BridgeError.notConfigured },
            sessions: PlaySessions(), links: LinkStore(), anime: AnimeLookup()
        )
        let answer = try await PlayCalls.handle("play.local", JSONSerialization.data(withJSONObject: [
            "ratingKey": "11", "snapshot": snapshot.path, "decision": decision.path, "file": movie.path,
            "version": 0, "isAnime": false, "countries": ["US"], "localOffset": 45_000,
            "sidecars": ["42": subtitle.path], "subtitle": ["kind": "track", "id": 42],
            "quality": VideoQuality.mbps2.rawValue, "savesData": true, "metered": true, "directPlay": "disabled", "network": 0, "now": 0,
            "capabilities": [
                "hevc": false, "hevcMain10": false, "av1": false, "hdrDisplay": false, "dolbyVisionProfiles": [],
                "nativeAudioCodecs": ["aac"], "directContainers": ["mp4"],
            ],
        ]), using: services)
        let wire = try #require((JSONSerialization.jsonObject(with: answer) as? [String: Any])?["ok"] as? [String: Any])
        let stream = try #require(wire["stream"] as? [String: Any])
        let route = try #require(wire["route"] as? [String: Any])
        let tracks = try #require(wire["tracks"] as? [String: Any])
        let captions = try #require(tracks["subtitles"] as? [[String: Any]])
        let saved = try #require(captions.first { $0["id"] as? Int == 42 })
        #expect(wire["startAt"] as? Double == 45)
        #expect(wire["probe"] == nil && wire["thumbnails"] == nil)
        #expect(route["kind"] as? String == "directPlay")
        #expect(stream["url"] as? String == movie.absoluteString)
        #expect(stream["headers"] as? [String: String] == [:])
        #expect(saved["url"] as? String == subtitle.absoluteString)
        #expect(saved["delivery"] as? String == "sidecar")
        #expect(captions.first { $0["id"] as? Int == 73 }?["delivery"] as? String == "inStream")
        #expect(!captions.contains { $0["id"] as? Int == 99 })
    }

    /// A Plex server with one 1080p H.264 film (version 12, 6 Mbit/s) and, beside it, HomeLabber's answer for it.
    private final class Servers: PlexTransport, Sendable {
        private let log = Mutex<[String]>([])
        private let homeLabber: PlexHTTPResponse

        init(homeLabber: PlexHTTPResponse) { self.homeLabber = homeLabber }

        var asked: [String] { log.withLock { $0 } }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            switch request.url.path {
            case "/identity":
                return Self.plex(["machineIdentifier": "machine-1"])
            case "/library/metadata/11":
                return Self.plex(["Metadata": [[
                    "ratingKey": "11", "type": "movie", "title": "Film", "duration": 3_600_000,
                    "Media": [[
                        "id": 12, "container": "mp4", "bitrate": 6000, "width": 1920, "height": 1080, "videoCodec": "h264", "audioCodec": "aac",
                        "Part": [["id": 13, "container": "mp4", "Stream": [
                            ["id": 3, "streamType": 1, "codec": "h264", "width": 1920, "height": 1080, "bitrate": 5800],
                            ["id": 4, "streamType": 2, "codec": "aac", "languageCode": "eng", "channels": 2],
                        ]]],
                    ]],
                ]]])
            default:
                log.withLock { $0.append(request.url.path) }
                return homeLabber
            }
        }

        static func plex(_ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }

        static func json(_ status: Int, _ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: status, body: try! JSONSerialization.data(withJSONObject: fields))
        }
    }

    private func choices(_ servers: Servers, screen: String?) async throws -> [[String: Any]] {
        let server = PlexServer(
            name: "Home", clientIdentifier: "machine-1", accessToken: "token",
            connections: [PlexConnection(uri: URL(string: "http://192.0.2.10:32400")!, isLocal: true)]
        )
        let library = PlexLibrary(
            server: server,
            identity: PlexClientIdentity(clientIdentifier: "client-1", version: "1.0", platformVersion: "14", device: "Pad", deviceName: "Pad"),
            transport: servers
        )
        var payload: [String: Any] = [
            "server": try JSONSerialization.jsonObject(with: JSONEncoder().encode(server)),
            "ratingKey": "11", "mediaIndex": 0, "quality": "medium",
            "homelabber": ["address": "https://homelabber.local", "token": HomeLabberConfig.tokenPrefix + String(repeating: "t", count: 24)],
            "capabilities": [
                "hevc": false, "hevcMain10": false, "av1": false, "hdrDisplay": false, "dolbyVisionProfiles": [],
                "nativeAudioCodecs": ["aac"], "directContainers": ["mp4"],
            ],
        ]
        if let screen { payload["screen"] = screen }
        let reply = try await DownloadCalls.handle(
            "download.choices", JSONSerialization.data(withJSONObject: payload), library: { _ in library },
            qualityMaps: QualityMapCache(), transport: servers
        )
        let wire = try #require((JSONSerialization.jsonObject(with: reply) as? [String: Any])?["ok"] as? [String: Any])
        return try #require(wire["options"] as? [[String: Any]])
    }

    @Test func aPhoneAsksForThePhoneScoresAndATabletForTheGeneralOnes() async throws {
        let scored = Servers.json(200, [
            "ratingKey": "11", "mediaId": 12,
            "tiers": ["medium": ["kbps": 2000, "vmaf": 90.0, "height": 480]],
            "phoneTiers": ["medium": ["kbps": 1100, "vmaf": 90.0, "height": 480]],
        ])
        func medium(_ options: [[String: Any]]) throws -> [String: Any] {
            try #require(options.first { $0["label"] as? String == "Medium" })
        }
        let phone = try medium(try await choices(Servers(homeLabber: scored), screen: "phone"))
        #expect(phone["basis"] as? String == "measured")
        #expect((phone["target"] as? [String: Any])?["videoKbps"] as? Int == 1100)
        let tablet = try medium(try await choices(Servers(homeLabber: scored), screen: "tablet"))
        #expect((tablet["target"] as? [String: Any])?["videoKbps"] as? Int == 2000)
        // The app that does not say is a phone.
        let unsaid = try medium(try await choices(Servers(homeLabber: scored), screen: nil))
        #expect((unsaid["target"] as? [String: Any])?["videoKbps"] as? Int == 1100)
    }

    @Test func aFileSmallerThanAnyConversionIsOriginalOnly() async throws {
        let servers = Servers(homeLabber: Servers.json(404, ["error": "Smaller than any transcode", "useOriginal": true, "sourceKbps": 6000]))
        let options = try await choices(servers, screen: "phone")
        #expect(options.map { $0["label"] as? String } == ["Original"])
        #expect(servers.asked == ["/api/media-quality/v1/servers/machine-1/items/11"])
    }

    @Test func finishingOfflineIsPersistedWithoutPlexCredentials() async throws {
        let reply = try await DownloadCalls.handle("download.watch.update", Data(#"{"watch":{"offset":14000,"watched":false},"position":0,"finished":true,"at":1700000000}"#.utf8)) { _ in
            throw BridgeError.notConfigured
        }
        struct Envelope: Decodable { let ok: OfflineWatch }
        let watch = try JSONDecoder().decode(Envelope.self, from: reply).ok
        #expect(watch.offset == 0 && watch.watched && watch.finishedHere)
        #expect(watch.sync(serverLastViewed: nil) == [.watched])
        #expect(watch.sync(serverLastViewed: Date(timeIntervalSince1970: 1_700_000_001)).isEmpty)
    }

    private static func snapshot(container: String, streamID: Int, external: Bool) throws -> Data {
        var stream: [String: Any] = ["id": streamID, "streamType": 3, "codec": "srt", "languageCode": "eng"]
        if external { stream["key"] = "/library/streams/\(streamID)" }
        let missing: [String: Any] = ["id": 99, "streamType": 3, "codec": "srt", "key": "/library/streams/99"]
        let tracks: [[String: Any]] = [
            ["id": 3, "streamType": 1, "codec": "h264", "width": 1920, "height": 1080],
            ["id": 4, "streamType": 2, "codec": "aac", "languageCode": "eng", "channels": 2],
            stream, missing,
        ]
        return try JSONSerialization.data(withJSONObject: ["MediaContainer": ["Metadata": [[
            "ratingKey": "11", "type": "movie", "title": "Download", "viewOffset": 9000, "duration": 100_000,
            "Media": [["id": 12, "container": container, "Part": [["id": 13, "container": container, "Stream": tracks]]]],
        ]]]])
    }
}
