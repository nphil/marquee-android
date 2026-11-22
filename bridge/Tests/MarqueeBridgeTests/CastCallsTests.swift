import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

/// The Chromecast calls on a scripted server: what a receiver is sent for a file it plays and for one the server
/// converts, how a subtitle shows on it, and how the receiver's statuses read.
@Suite struct CastingCalls {
    /// Item 1 is an H.264 1080p MP4 with AAC and a text subtitle; item 2 is 4K HEVC in Matroska with DTS, a text
    /// subtitle and a picture subtitle.
    private final class Server: PlexTransport, Sendable {
        private let log = Mutex<[String]>([])
        private let seen = Mutex<[String: URL]>([:])
        private let token: String

        init(token: String = "cast-token") {
            self.token = token
        }

        func count(_ path: String) -> Int {
            log.withLock { $0 }.filter { $0.contains(path) }.count
        }

        func lastURL(_ path: String) -> URL? {
            seen.withLock { $0[path] }
        }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            log.withLock { $0.append("\(request.method) \(request.url.path)") }
            seen.withLock { $0[request.url.path] = request.url }
            switch request.url.path {
            case "/identity":
                return Self.answer(["machineIdentifier": "machine-1"])
            case "/library/metadata/1":
                return Self.answer(["Metadata": [Self.film()]])
            case "/library/metadata/2":
                return Self.answer(["Metadata": [Self.film(key: "2", hevc: true)]])
            case "/library/metadata/3":
                var item = Self.film(key: "3")
                let media = item["Media"] as! [[String: Any]]
                var recording = media[0]
                recording["id"] = 11
                var parts = recording["Part"] as! [[String: Any]]
                parts[0]["key"] = "/library/parts/11/1/recording.mp4"
                recording["Part"] = parts
                item["Media"] = media + [recording]
                return Self.answer(["Metadata": [item]])
            case "/security/token":
                return Self.answer(["token": token])
            case "/library/parts/9", "/video/:/transcode/universal/stop":
                return Self.answer([:])
            default:
                return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        static func answer(_ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }

        static func film(key: String = "1", hevc: Bool = false) -> [String: Any] {
            var streams: [[String: Any]] = [
                hevc
                    ? ["id": 1, "streamType": 1, "codec": "hevc", "bitDepth": 10, "width": 3840, "height": 2160, "chromaSubsampling": "4:2:0", "frameRate": 23.976, "level": 153]
                    : ["id": 1, "streamType": 1, "codec": "h264", "bitDepth": 8, "width": 1920, "height": 1080, "chromaSubsampling": "4:2:0", "frameRate": 23.976, "level": 41],
                [
                    "id": 2, "streamType": 2, "codec": hevc ? "dca" : "aac", "language": "English", "languageCode": "eng", "channels": 2,
                    "selected": true, "default": true,
                ],
                ["id": 4, "streamType": 3, "codec": "srt", "language": "English", "languageCode": "eng"],
            ]
            if hevc { streams.append(["id": 5, "streamType": 3, "codec": "pgs", "language": "French", "languageCode": "fra"]) }
            let container = hevc ? "mkv" : "mp4"
            return [
                "ratingKey": key, "key": "/library/metadata/\(key)", "type": "movie", "title": "Film \(key)",
                "thumb": "/library/metadata/\(key)/thumb/1", "duration": 7_200_000, "viewOffset": 600_000, "librarySectionID": 1,
                "Media": [[
                    "id": 7, "duration": 7_200_000, "bitrate": 8000, "width": hevc ? 3840 : 1920, "height": hevc ? 2160 : 1080,
                    "videoCodec": hevc ? "hevc" : "h264", "audioCodec": hevc ? "dca" : "aac", "container": container,
                    "Part": [["id": 9, "key": "/library/parts/9/1/file.\(container)", "duration": 7_200_000, "container": container, "Stream": streams]],
                ]],
            ]
        }
    }

    private static let plexServer = PlexServer(
        name: "Home", clientIdentifier: "machine-1", accessToken: "token",
        connections: [PlexConnection(uri: URL(string: "http://192.0.2.10:32400")!, isLocal: true)]
    )

    private static let identity = PlexClientIdentity(
        clientIdentifier: "client-1", version: "1.0", platformVersion: "14", device: "Pad", deviceName: "Pad"
    )

    private func services(_ server: Server) -> CastServices {
        let library = PlexLibrary(server: Self.plexServer, identity: Self.identity, transport: server)
        let play = PlayServices(library: { _ in library }, sessions: PlaySessions(), links: LinkStore(), anime: AnimeLookup())
        return CastServices(play: play, identity: { Self.identity }, progress: CastProgressStore())
    }

    private func call(_ method: String, _ services: CastServices, _ fields: [String: Any]) async throws -> [String: Any] {
        var payload = fields
        payload["server"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(Self.plexServer))
        let reply = try await CastCalls.handle(method, JSONSerialization.data(withJSONObject: payload), using: services)
        let object = try #require(JSONSerialization.jsonObject(with: reply) as? [String: Any])
        return try #require(object["ok"] as? [String: Any])
    }

    private func start(_ services: CastServices, _ fields: [String: Any] = [:]) async throws -> [String: Any] {
        var payload: [String: Any] = ["ratingKey": "1", "quality": -1, "directPlay": "auto", "now": 100.0, "receiverModel": "Chromecast"]
        payload.merge(fields) { $1 }
        return try await call("cast.start", services, payload)
    }

    private func query(_ url: URL?, _ name: String) -> String? {
        url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?.queryItems?.first { $0.name == name }?.value
    }

    private func stream(_ reply: [String: Any]) throws -> [String: Any] {
        let plan = try #require(reply["plan"] as? [String: Any])
        return try #require(plan["stream"] as? [String: Any])
    }

    private func route(_ reply: [String: Any]) throws -> [String: Any] {
        let plan = try #require(reply["plan"] as? [String: Any])
        return try #require(plan["route"] as? [String: Any])
    }

    @Test func anH264FileInMP4IsHandedToTheReceiverWithATransientToken() async throws {
        let server = Server()
        let reply = try await start(services(server))

        let stream = try stream(reply)
        #expect(try route(reply)["kind"] as? String == "directPlay")
        #expect(stream["kind"] as? String == "file")
        #expect(stream["session"] == nil)
        #expect(reply["contentType"] as? String == "video/mp4")
        let url = try #require((stream["url"] as? String).flatMap(URL.init(string:)))
        #expect(url.path == "/library/parts/9/1/file.mp4")
        #expect(query(url, "X-Plex-Token") == "cast-token")
        let poster = try #require((reply["poster"] as? String).flatMap(URL.init(string:)))
        #expect(poster.path == "/library/metadata/1/thumb/1")
        #expect(query(poster, "X-Plex-Token") == "cast-token")
        #expect((reply["plan"] as? [String: Any])?["startAt"] as? Double == 600)
        #expect(server.count("/transcode/") == 0)
    }

    @Test(arguments: [false, true]) func aRecordedVersionTakesPrecedenceOverTheMediaIndex(airPlay: Bool) async throws {
        let reply = try await start(services(Server()), [
            "ratingKey": "3", "mediaIndex": 0, "recordingMediaID": 11, "airPlay": airPlay,
        ])
        let media = try stream(reply)
        let url = try #require((media["url"] as? String).flatMap(URL.init(string:)))
        #expect(url.path == "/library/parts/11/1/recording.mp4")
        #expect((reply["plan"] as? [String: Any])?["mediaIndex"] as? Int == 1)
    }

    @Test func airPlayHandsOffOnlyTheMP4AndItsDelegationToken() async throws {
        let server = Server()
        let reply = try await start(services(server), ["airPlay": true])
        let media = try stream(reply)
        let url = try #require((media["url"] as? String).flatMap(URL.init(string:)))
        #expect(media["kind"] as? String == "file")
        #expect(query(url, "X-Plex-Token") == "cast-token")
        #expect((media["headers"] as? [String: String])?.isEmpty == true)
        #expect(reply["poster"] == nil)
    }

    @Test func airPlayRejectsTitlesThatNeedHLSBeforeRequestingCredentials() async {
        let server = Server()
        await #expect(throws: AirPlayMediaError.self) {
            try await start(services(server), ["airPlay": true, "ratingKey": "2"])
        }
        #expect(server.count("/security/token") == 0)
        #expect(server.count("/transcode/") == 0)
    }

    @Test(arguments: [false, true]) func neitherReceiverReceivesTheAccountToken(airPlay: Bool) async throws {
        let server = Server(token: try #require(Self.plexServer.accessToken))
        await #expect(throws: PlexError.self) {
            try await start(services(server), ["airPlay": airPlay])
        }
        #expect(server.count("/security/token") == 1)
    }

    @Test func aMatroskaFileIsConvertedByTheServerFromTheStartAsAChromecast() async throws {
        let server = Server()
        let reply = try await start(services(server), ["ratingKey": "2"])

        let stream = try stream(reply)
        #expect(try route(reply)["kind"] as? String == "serverTranscode")
        #expect(stream["kind"] as? String == "hls")
        #expect(stream["offset"] as? Double == 0)
        #expect(reply["contentType"] as? String == "application/x-mpegURL")
        let url = try #require((stream["url"] as? String).flatMap(URL.init(string:)))
        #expect(url.path == "/video/:/transcode/universal/start.m3u8")
        #expect(query(url, "X-Plex-Platform") == "Chromecast")
        #expect(query(url, "X-Plex-Token") == "cast-token")
        #expect(query(url, "session") == stream["session"] as? String)
        #expect((reply["plan"] as? [String: Any])?["startAt"] as? Double == 600)
    }

    @Test func aFileThePhoneWouldPlayStillGoesThroughTheServerWhenTheReceiverFailedOnIt() async throws {
        let reply = try await start(services(Server()), ["receiverFailed": true])

        #expect(try route(reply)["kind"] as? String == "serverTranscode")
        #expect(try stream(reply)["kind"] as? String == "hls")
    }

    @Test func aTextSubtitleIsNotShownAndAPictureOneIsBurnedInOnlyWhenPicked() async throws {
        let server = Server()
        let casting = services(server)

        let text = try await start(casting, ["ratingKey": "2", "subtitle": ["kind": "track", "id": 4]])
        let shown = try #require(try route(text)["subtitle"] as? [String: Any])
        #expect(shown["kind"] as? String == "notShown")
        let tracks = try #require((text["plan"] as? [String: Any])?["tracks"] as? [String: Any])
        let deliveries = try #require(tracks["subtitles"] as? [[String: Any]]).reduce(into: [Int: String]()) {
            $0[$1["id"] as! Int] = $1["delivery"] as? String
        }
        #expect(deliveries == [4: "notShown", 5: "burnedIn"])
        #expect(server.count("PUT /library/parts/9") == 0)

        let picture = try await start(casting, ["ratingKey": "2", "subtitle": ["kind": "track", "id": 5]])
        let burned = try #require(try route(picture)["subtitle"] as? [String: Any])
        #expect(burned["kind"] as? String == "burnedIn")
        #expect(burned["track"] as? Int == 5)
        #expect(query(server.lastURL("/library/parts/9"), "subtitleStreamID") == "5")
    }

    @Test func theReceiversStatusesReadAgainstTheLoadTheyBelongTo() async throws {
        let casting = services(Server())
        let reply = try await start(casting, ["replacing": 4])
        let session = try #require((reply["plan"] as? [String: Any])?["session"] as? String)

        func progress(_ now: Double, _ status: [String: Any]? = nil) async throws -> [String: Any] {
            var fields: [String: Any] = ["session": session, "now": now]
            fields["status"] = status
            return try await call("cast.progress", casting, fields)
        }
        func status(_ id: Int, _ player: String, _ position: Double = 0, idle: String? = nil) -> [String: Any] {
            var fields: [String: Any] = ["mediaSessionId": id, "player": player, "position": position, "rate": 1.0]
            fields["idle"] = idle
            return fields
        }

        // The item this load replaced goes idle after the load was sent; the cast is not over.
        let stale = try await progress(101, status(4, "idle", idle: "cancelled"))
        #expect(stale["state"] as? String == "buffering")
        #expect(stale["end"] == nil)

        let playing = try await progress(102, status(5, "playing", 600))
        #expect(playing["state"] as? String == "playing")
        #expect(try await progress(104)["position"] as? Double == 602)

        let paused = try await progress(105, status(5, "paused", 603))
        #expect(paused["state"] as? String == "paused")
        #expect(paused["position"] as? Double == 603)

        #expect(try await progress(106, status(5, "idle", 7200, idle: "finished"))["end"] as? String == "finished")
        #expect(try await progress(107, status(5, "idle", idle: "error"))["end"] as? String == "failed")
        #expect(try await progress(108, status(5, "idle", idle: "interrupted"))["end"] as? String == "stopped")

        _ = try await call("cast.end", casting, ["session": session])
        await #expect(throws: PlayError.self) { try await progress(109) }
    }
}
