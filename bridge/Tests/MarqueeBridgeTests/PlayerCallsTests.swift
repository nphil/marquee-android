import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

/// The player's calls on a scripted server (DESIGN §8.6): which route an item takes on this tablet, where it
/// starts, and what the bridge keeps for the screen between calls.
@Suite struct PlayerCalls {
    /// A 4K HEVC movie in Matroska with DTS audio and a picture subtitle, 600 s in, and a play queue of three.
    private final class Server: PlexTransport, Sendable {
        private let log = Mutex<[String]>([])

        var requests: [String] {
            log.withLock { $0 }
        }

        func count(_ path: String) -> Int {
            requests.filter { $0.contains(path) }.count
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            log.withLock { $0.append("\(request.method) \(request.url.path)") }
            switch request.url.path {
            case "/identity":
                return Self.answer(["machineIdentifier": "machine-1"])
            case "/library/metadata/1", "/library/metadata/2", "/library/metadata/3":
                return Self.answer(["Metadata": [Self.movie(String(request.url.path.last!))]])
            case "/playQueues":
                return Self.answer([
                    "playQueueID": 7, "playQueueSelectedItemID": 71,
                    "Metadata": [Self.movie("1", queueItem: 71), Self.movie("2", queueItem: 72), Self.movie("3", queueItem: 73)],
                ])
            case "/:/timeline", "/library/parts/9", "/video/:/transcode/universal/decision", "/video/:/transcode/universal/stop":
                return Self.answer([:])
            default:
                return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        static func answer(_ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }

        static func movie(_ key: String, queueItem: Int? = nil) -> [String: Any] {
            let streams: [[String: Any]] = [
                [
                    "id": 1, "streamType": 1, "codec": "hevc", "bitDepth": 10, "width": 3840, "height": 2160,
                    "chromaSubsampling": "4:2:0", "colorTrc": "smpte2084", "frameRate": 23.976, "level": 153,
                ],
                [
                    "id": 2, "streamType": 2, "codec": "dca", "language": "English", "languageCode": "eng", "channels": 6,
                    "selected": true, "default": true,
                ],
                ["id": 5, "streamType": 3, "codec": "pgs", "language": "English", "languageCode": "eng"],
            ]
            var item: [String: Any] = [
                "ratingKey": key, "key": "/library/metadata/\(key)", "type": "movie", "title": "Movie \(key)",
                "duration": 7_200_000, "viewOffset": 600_000, "librarySectionID": 1,
                "Marker": [["type": "intro", "startTimeOffset": 30_000, "endTimeOffset": 90_000]],
                "Chapter": [["tag": "Opening", "index": 1, "startTimeOffset": 0, "endTimeOffset": 600_000]],
                "Media": [[
                    "id": 7, "duration": 7_200_000, "bitrate": 20_000, "width": 3840, "height": 2160, "videoCodec": "hevc",
                    "audioCodec": "dca", "container": "mkv",
                    "Part": [["id": 9, "key": "/library/parts/9/1/file.mkv", "duration": 7_200_000, "container": "mkv", "Stream": streams]],
                ]],
            ]
            if let queueItem { item["playQueueItemID"] = queueItem }
            return item
        }
    }

    private static let plexServer = PlexServer(
        name: "Home", clientIdentifier: "machine-1", accessToken: "token",
        connections: [PlexConnection(uri: URL(string: "http://192.0.2.10:32400")!, isLocal: true)]
    )

    /// What the tablet's decoders take: HEVC and DTS, with Matroska opened directly.
    private static var tablet: [String: Any] {
        [
            "hevc": true, "hevcMain10": true, "av1": false, "hdrDisplay": true, "dolbyVisionProfiles": [Int](),
            "nativeAudioCodecs": ["aac", "ac3", "eac3", "dca", "truehd"], "directContainers": ["mp4", "mkv"],
        ]
    }

    private func services(_ server: Server) -> PlayServices {
        let library = PlexLibrary(
            server: Self.plexServer,
            identity: PlexClientIdentity(clientIdentifier: "client-1", version: "1.0", platformVersion: "14", device: "Pad", deviceName: "Pad"),
            transport: server
        )
        return PlayServices(library: { _ in library }, sessions: PlaySessions(), links: LinkStore(), anime: AnimeLookup())
    }

    private func call(_ method: String, _ services: PlayServices, _ fields: [String: Any]) async throws -> [String: Any] {
        var payload = fields
        payload["server"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(Self.plexServer))
        let reply = try await PlayCalls.handle(method, JSONSerialization.data(withJSONObject: payload), using: services)
        let object = try #require(JSONSerialization.jsonObject(with: reply) as? [String: Any])
        return try #require(object["ok"] as? [String: Any])
    }

    private func start(_ services: PlayServices, _ fields: [String: Any] = [:]) async throws -> [String: Any] {
        var payload: [String: Any] = [
            "ratingKey": "1", "quality": 0, "savesData": false, "metered": false, "directPlay": "auto",
            "capabilities": Self.tablet, "network": 1, "now": 100.0,
        ]
        payload.merge(fields) { $1 }
        return try await call("play.start", services, payload)
    }

    private func route(_ plan: [String: Any]) throws -> [String: Any] {
        try #require(plan["route"] as? [String: Any])
    }

    @Test func aMatroskaFileWithDTSPlaysDirectlyFromWhereItLeftOff() async throws {
        let server = Server()
        let plan = try await start(services(server))

        let route = try route(plan)
        #expect(route["kind"] as? String == "directPlay")
        #expect(route["audio"] as? Int == 2)
        #expect(plan["startAt"] as? Double == 600)
        #expect(plan["duration"] as? Double == 7200)
        #expect(plan["probe"] == nil)

        // The file opens with the token in a header; the address that gets logged carries none.
        let stream = try #require(plan["stream"] as? [String: Any])
        #expect(stream["kind"] as? String == "file")
        #expect(stream["url"] as? String == "http://192.0.2.10:32400/library/parts/9/1/file.mkv")
        #expect((stream["headers"] as? [String: String])?["X-Plex-Token"] == "token")
        #expect(server.count("transcode") == 0)

        #expect((plan["intro"] as? [[String: Any]])?.first?["start"] as? Int == 30_000)
        #expect((plan["chapters"] as? [[String: Any]])?.first?["title"] as? String == "Opening")
    }

    @Test func aQualityBelowTheFileStreamsFromTheServerFromTheStart() async throws {
        let server = Server()
        // A fixed quality still reads the link once before the server converts anything: 4 MB in 0.4 s.
        let plan = try await start(services(server), ["quality": 4000, "startAt": 120.0, "probe": ["bytes": 4_000_000, "seconds": 0.4]])

        let route = try route(plan)
        #expect(route["kind"] as? String == "serverTranscode")
        #expect(route["reencodesVideo"] as? Bool == true)
        #expect(route["maxVideoBitrateKbps"] as? Int == 4000)

        let stream = try #require(plan["stream"] as? [String: Any])
        #expect(stream["kind"] as? String == "hls")
        #expect(stream["offset"] as? Double == 120)
        #expect((stream["url"] as? String)?.contains("start.m3u8") == true)
        #expect(stream["session"] as? String != nil)
        #expect(server.count("/video/:/transcode/universal/decision") == 1)
    }

    @Test func autoReadsTheLinkBeforeTheServerConvertsAnything() async throws {
        let server = Server()
        let services = services(server)
        let auto: [String: Any] = ["quality": -1, "view": ["width": 3200, "height": 2136]]

        let asking = try await start(services, auto)
        let probe = try #require(asking["probe"] as? [String: Any])
        #expect(asking["stream"] == nil)
        #expect(probe["url"] as? String == "http://192.0.2.10:32400/library/parts/9/1/file.mkv")
        #expect(server.count("transcode") == 0)

        // 4 MB in 0.4 s is 80 Mbit/s: the file fits, so it plays as it is and Auto keeps watching.
        var measured = auto
        measured["session"] = asking["session"]
        measured["probe"] = ["bytes": 4_000_000, "seconds": 0.4]
        let plan = try await start(services, measured)
        #expect(plan["session"] as? String == asking["session"] as? String)
        #expect(try route(plan)["kind"] as? String == "directPlay")
        #expect(plan["adapts"] as? Bool == true)
        #expect(plan["quality"] as? Int == 0)
    }

    @Test func aPictureSubtitleChosenByHandIsNeverBurnedIn() async throws {
        let plan = try await start(services(Server()), ["subtitle": ["kind": "track", "id": 5]])

        let route = try route(plan)
        #expect(route["kind"] as? String == "directPlay")
        #expect(route["reencodesVideo"] as? Bool == false)
        #expect((route["subtitle"] as? [String: Any])?["kind"] as? String == "notShown")
    }

    @Test func reportsGoOutOnTheCadenceCoreSets() async throws {
        let server = Server()
        let services = services(server)
        let session = try #require(try await start(services)["session"] as? String)

        func report(_ state: String, _ timeMs: Int) async throws -> Bool? {
            try await call("play.report", services, ["session": session, "state": state, "timeMs": timeMs, "durationMs": 7_200_000])["took"] as? Bool
        }
        #expect(try await report("playing", 600_000) == true)
        #expect(try await report("playing", 601_000) == false)
        #expect(try await report("paused", 602_000) == true)
        #expect(server.count("/:/timeline") == 2)
    }

    @Test func closingTheScreenStopsTheServersConversion() async throws {
        let server = Server()
        let services = services(server)
        let session = try #require(try await start(services, ["quality": 4000, "probe": ["bytes": 4_000_000, "seconds": 0.4]])["session"] as? String)

        _ = try await call("play.stop", services, ["session": session, "timeMs": 700_000, "durationMs": 7_200_000, "close": true])

        #expect(server.count("/video/:/transcode/universal/stop") == 1)
        #expect(server.count("/:/timeline") == 1)
        await #expect(throws: PlayError.self) {
            try await call("play.report", services, ["session": session, "state": "playing", "timeMs": 1, "durationMs": 1])
        }
    }

    @Test func theQueueNamesTheNeighborsOfTheItemPlaying() async throws {
        let services = services(Server())
        let session = try #require(try await start(services, ["ratingKey": "2"])["session"] as? String)

        let neighbors = try await call("play.queue", services, ["session": session])

        #expect((neighbors["next"] as? [String: Any])?["ratingKey"] as? String == "3")
        #expect((neighbors["previous"] as? [String: Any])?["ratingKey"] as? String == "1")
    }
}
