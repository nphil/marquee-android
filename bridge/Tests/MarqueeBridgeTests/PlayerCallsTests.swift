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
        private let seen = Mutex<[String: URL]>([:])
        /// The id of the server's conversion that `/transcode/sessions` reports.
        let conversion = Mutex<String>("")

        var requests: [String] {
            log.withLock { $0 }
        }

        func count(_ path: String) -> Int {
            requests.filter { $0.contains(path) }.count
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
            case "/library/metadata/1", "/library/metadata/2", "/library/metadata/3":
                return Self.answer(["Metadata": [Self.movie(String(request.url.path.last!))]])
            case "/library/metadata/4":
                return Self.answer(["Metadata": [Self.movie("4", extra: Self.moreTracks)]])
            case "/playQueues":
                return Self.answer([
                    "playQueueID": 7, "playQueueSelectedItemID": 71,
                    "Metadata": [Self.movie("1", queueItem: 71), Self.movie("2", queueItem: 72), Self.movie("3", queueItem: 73)],
                ])
            case "/:/timeline", "/library/parts/9", "/video/:/transcode/universal/decision", "/video/:/transcode/universal/stop":
                return Self.answer([:])
            case "/library/metadata/14831":
                return Self.answer(["Metadata": [Self.sharedEpisode()]])
            case "/transcode/sessions":
                return Self.answer(["TranscodeSession": [[
                    "key": "/transcode/sessions/\(conversion.withLock { $0 })", "speed": 0.7, "throttled": false,
                    "videoDecision": "transcode", "audioDecision": "transcode", "subtitleDecision": "burn",
                    "transcodeHwFullPipeline": false, "transcodeHwDecoding": "qsv", "transcodeHwEncoding": "qsv",
                ]]])
            default:
                return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        static func answer(_ fields: [String: Any]) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }

        static func movie(_ key: String, queueItem: Int? = nil, extra: [[String: Any]] = []) -> [String: Any] {
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
                    "Part": [["id": 9, "key": "/library/parts/9/1/file.mkv", "duration": 7_200_000, "container": "mkv", "Stream": streams + extra]],
                ]],
            ]
            if let queueItem { item["playQueueItemID"] = queueItem }
            return item
        }

        /// The stream set of report r2795: HEVC Main 10 1080p SDR in Matroska, AC3 5.1, a forced English subtitle inside
        /// the file and a separate English one chosen on the server, and a thumbnail index on the part.
        static func sharedEpisode() -> [String: Any] {
            let streams: [[String: Any]] = [
                [
                    "id": 82676, "streamType": 1, "codec": "hevc", "bitDepth": 10, "width": 1920, "height": 1080,
                    "chromaSubsampling": "4:2:0", "colorPrimaries": "bt709", "colorTrc": "bt709", "colorSpace": "bt709",
                    "frameRate": 23.976, "level": 120, "bitrate": 3860,
                ],
                [
                    "id": 82677, "streamType": 2, "codec": "ac3", "language": "English", "languageCode": "eng", "channels": 6,
                    "bitrate": 448, "selected": true, "default": true,
                ],
                ["id": 82678, "streamType": 3, "codec": "srt", "language": "English", "languageCode": "eng", "forced": true, "default": true],
                [
                    "id": 82772, "streamType": 3, "codec": "srt", "format": "srt", "language": "English", "languageCode": "eng",
                    "key": "/library/streams/82772", "selected": true,
                ],
            ]
            return [
                "ratingKey": "14831", "key": "/library/metadata/14831", "type": "movie", "title": "Introduction to Finality",
                "duration": 2_700_000, "librarySectionID": 1,
                "Media": [[
                    "id": 7, "duration": 2_700_000, "bitrate": 4300, "width": 1920, "height": 1080, "videoCodec": "hevc",
                    "audioCodec": "ac3", "container": "mkv",
                    "Part": [[
                        "id": 9, "key": "/library/parts/9/1/file.mkv", "duration": 2_700_000, "container": "mkv", "indexes": "sd",
                        "Stream": streams,
                    ]],
                ]],
            ]
        }

        /// A second audio track, English text inside the file, and a separate Spanish file.
        static var moreTracks: [[String: Any]] {
            [
                ["id": 3, "streamType": 2, "codec": "aac", "language": "Japanese", "languageCode": "jpn", "channels": 2],
                ["id": 4, "streamType": 3, "codec": "srt", "language": "English", "languageCode": "eng"],
                [
                    "id": 6, "streamType": 3, "codec": "srt", "format": "srt", "language": "Spanish", "languageCode": "spa",
                    "key": "/library/streams/6",
                ],
            ]
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

    /// The same tablet with no AC3 decoder.
    private static var tabletWithoutAC3: [String: Any] {
        var capabilities = tablet
        capabilities["nativeAudioCodecs"] = ["aac"]
        return capabilities
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

    private func query(_ url: URL?, _ name: String) -> String? {
        url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?.queryItems?.first { $0.name == name }?.value
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

    @Test func theTracksMenuSaysHowEachSubtitleShowsOnThisRoute() async throws {
        let plan = try await start(services(Server()), ["ratingKey": "4"])

        let tracks = try #require(plan["tracks"] as? [String: Any])
        let audio = try #require(tracks["audio"] as? [[String: Any]])
        #expect(audio.map { $0["id"] as? Int } == [2, 3])
        #expect(audio.first?["label"] as? String == "English")

        let subtitles = try #require(tracks["subtitles"] as? [[String: Any]])
        func track(_ id: Int) -> [String: Any]? { subtitles.first { $0["id"] as? Int == id } }
        // Text inside the file the tablet plays itself, a picture it cannot draw, and a separate file it loads.
        #expect(track(4)?["delivery"] as? String == "inStream")
        #expect(track(5)?["delivery"] as? String == "notShown")
        #expect(track(6)?["delivery"] as? String == "sidecar")
        #expect(track(6)?["url"] as? String == "http://192.0.2.10:32400/library/streams/6?encoding=utf-8&format=srt")
        #expect(track(4)?["url"] == nil)
        #expect(tracks["automatic"] as? Int == 4)
        #expect((tracks["headers"] as? [String: String])?["X-Plex-Token"] == "token")
    }

    @Test func aPickIsKeptForTheNextTimeTheTitlePlays() async throws {
        let services = services(Server())
        let session = try #require(try await start(services, ["ratingKey": "4"])["session"] as? String)

        let memory = try await call("play.remember", services, ["session": session, "audio": 3, "subtitle": ["kind": "off"]])

        let next = try await start(services, ["ratingKey": "4", "memory": memory])
        let route = try route(next)
        #expect(route["audio"] as? Int == 3)
        #expect((route["subtitle"] as? [String: Any])?["kind"] as? String == "none")
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

    @Test func discardingAStagedStreamReleasesItWithoutChangingTheTimeline() async throws {
        let server = Server()
        let services = services(server)
        let session = try #require(try await start(services, ["quality": 4000, "probe": ["bytes": 4_000_000, "seconds": 0.4]])["session"] as? String)

        _ = try await call("play.stop", services, ["session": session, "timeMs": 0, "durationMs": 0, "close": true, "report": false])

        #expect(server.count("/video/:/transcode/universal/stop") == 1)
        #expect(server.count("/:/timeline") == 0)
        #expect(services.sessions.find(session) == nil)
    }

    @Test func anAC3FilePlaysAsItIsOnATabletThatDecodesAC3() async throws {
        let server = Server()
        let services = services(server)
        let plan = try await start(services, ["ratingKey": "14831"])

        let route = try route(plan)
        #expect(route["kind"] as? String == "directPlay")
        #expect(route["reencodesVideo"] as? Bool == false)
        #expect((route["reasons"] as? [String])?.isEmpty == true)
        #expect(server.count("transcode") == 0)
        // The server's selections stay as they are, and the screen is offered the part's thumbnail index.
        #expect(server.count("/library/parts/9") == 0)
        let thumbnails = try #require(plan["thumbnails"] as? [String: Any])
        #expect(thumbnails["url"] as? String == "http://192.0.2.10:32400/library/parts/9/indexes/sd")
        #expect((thumbnails["headers"] as? [String: String])?["X-Plex-Token"] == "token")

        let session = try #require(plan["session"] as? String)
        #expect(try await call("play.transcode", services, ["session": session])["active"] as? Bool == false)
    }

    @Test func withoutAnAC3DecoderTheServerCopiesTheVideoAndHasNoSubtitleToBurnIn() async throws {
        let server = Server()
        let services = services(server)
        let plan = try await start(services, [
            "ratingKey": "14831", "capabilities": Self.tabletWithoutAC3, "probe": ["bytes": 4_000_000, "seconds": 0.4],
        ])

        let route = try route(plan)
        #expect(route["kind"] as? String == "serverTranscode")
        #expect(route["reencodesVideo"] as? Bool == false)
        #expect((route["reasons"] as? [String])?.count == 1)
        let decision = server.lastURL("/video/:/transcode/universal/decision")
        #expect(query(decision, "directStream") == "1")
        #expect(query(decision, "subtitles") == "none")

        // The server's own subtitle pick is cleared for the stream, so Plex has none to burn in beside the one the
        // screen draws; it is put back when the stream ends.
        #expect(server.count("/library/parts/9") == 1)
        #expect(query(server.lastURL("/library/parts/9"), "subtitleStreamID") == "0")
        let session = try #require(plan["session"] as? String)
        _ = try await call("play.stop", services, ["session": session, "timeMs": 700_000, "durationMs": 2_700_000, "close": true])
        #expect(server.count("/library/parts/9") == 2)
        #expect(query(server.lastURL("/library/parts/9"), "subtitleStreamID") == "82772")
    }

    @Test func aFileBelowTheQualityCapKeepsItsVideoAtEightMbpsAndOnAuto() async throws {
        for quality in [8000, -1] {
            let plan = try await start(services(Server()), [
                "ratingKey": "14831", "capabilities": Self.tabletWithoutAC3, "quality": quality,
                "view": ["width": 3200, "height": 2136], "probe": ["bytes": 1_500_000, "seconds": 1.0],
            ])

            let route = try route(plan)
            #expect(route["reasons"] as? [String] == ["AC3 audio"], "quality \(quality)")
            #expect(route["reencodesVideo"] as? Bool == false, "quality \(quality)")
        }
    }

    @Test func theServersOwnAccountOfTheConversionReachesTheScreen() async throws {
        let server = Server()
        let services = services(server)
        let plan = try await start(services, [
            "ratingKey": "14831", "capabilities": Self.tabletWithoutAC3, "probe": ["bytes": 4_000_000, "seconds": 0.4],
        ])
        let stream = try #require(plan["stream"] as? [String: Any])
        server.conversion.withLock { $0 = stream["session"] as? String ?? "" }

        let state = try await call("play.transcode", services, ["session": try #require(plan["session"] as? String)])
        #expect(state["active"] as? Bool == true)
        #expect(state["video"] as? String == "transcode")
        #expect(state["subtitle"] as? String == "burn")
        #expect(state["speed"] as? Double == 0.7)
        #expect(state["throttled"] as? Bool == false)
    }

    @Test func theQueueNamesTheNeighborsOfTheItemPlaying() async throws {
        let services = services(Server())
        let session = try #require(try await start(services, ["ratingKey": "2"])["session"] as? String)

        let neighbors = try await call("play.queue", services, ["session": session])

        #expect((neighbors["next"] as? [String: Any])?["ratingKey"] as? String == "3")
        #expect((neighbors["previous"] as? [String: Any])?["ratingKey"] as? String == "1")
    }

    @Test func autoplayFollowsTheProfilesRuleAtTheCreditsAndTheEnd() async throws {
        let services = services(Server())
        func rule(_ fields: [String: Any]) async throws -> [String: Any] {
            var payload: [String: Any] = ["enabled": true, "credits": "countdown", "delay": 5, "creditsWatched": false]
            payload.merge(fields) { $1 }
            return try await call("play.autoplay", services, payload)
        }

        let counting = try await rule([:])
        #expect(counting["atCredits"] as? String == "countdown")
        #expect(counting["seconds"] as? Int == 5)
        #expect(counting["playsNextAtEnd"] as? Bool == true)
        #expect(try await rule(["credits": "skipInstantly"])["atCredits"] as? String == "playNow")
        #expect(try await rule(["creditsWatched": true])["playsNextAtEnd"] as? Bool == false)
        let off = try await rule(["enabled": false])
        #expect(off["atCredits"] as? String == "offer")
        #expect(off["playsNextAtEnd"] as? Bool == false)
    }

    @Test func aSkipThatDoesNotLandIsTriedOnceMoreThenGivenUp() async throws {
        let services = services(Server())
        let session = try #require(try await start(services)["session"] as? String)
        func check(_ playhead: Double, advanced: Double = 1, pending: Bool = false) async throws -> String? {
            let fields: [String: Any] = ["session": session, "playhead": playhead, "advanced": advanced, "pending": pending]
            return try await call("play.skip.check", services, fields)["verdict"] as? String
        }
        #expect(try await check(10) == "none")

        _ = try await call("play.skip.begin", services, ["session": session, "target": 90.0])
        #expect(try await check(10) == "waiting")
        #expect(try await check(11) == "waiting")
        #expect(try await check(12) == "retry")
        // A seek still on its way starts the count again.
        #expect(try await check(12, pending: true) == "waiting")
        #expect(try await check(12) == "waiting")
        #expect(try await check(12) == "waiting")
        #expect(try await check(12) == "missed")
        #expect(try await check(12) == "none")

        _ = try await call("play.skip.begin", services, ["session": session, "target": 90.0])
        #expect(try await check(89.5) == "landed")
        #expect(try await check(90) == "none")
    }

    @Test func tapsAreToldApartByTheModeAndTheThirdOfThePicture() async throws {
        let services = services(Server())
        let session = try #require(try await start(services)["session"] as? String)
        func tap(_ x: Double, at: Double, mode: String) async throws -> [String: Any] {
            try await call("play.tap", services, ["session": session, "mode": mode, "x": x, "width": 900.0, "at": at])
        }

        // A tap at a side waits for a second one; the middle only brings the controls.
        let first = try await tap(100, at: 10, mode: "doubleTap")
        #expect(first["action"] as? String == "single")
        #expect(first["window"] as? Double == 0.3)
        let second = try await tap(120, at: 10.2, mode: "doubleTap")
        #expect(second["action"] as? String == "skip")
        #expect(second["side"] as? String == "back")
        #expect(try await tap(450, at: 10.6, mode: "doubleTap")["action"] as? String == "controls")

        // Chained keeps skipping on the same side for 1.5 s after a skip.
        #expect(try await tap(800, at: 20, mode: "chained")["action"] as? String == "single")
        #expect(try await tap(800, at: 20.2, mode: "chained")["side"] as? String == "forward")
        #expect(try await tap(800, at: 21, mode: "chained")["action"] as? String == "skip")
        #expect(try await tap(800, at: 23, mode: "chained")["action"] as? String == "single")

        // A reset forgets the first tap of a double-tap.
        _ = try await call("play.tap.reset", services, ["session": session])
        #expect(try await tap(800, at: 23.1, mode: "doubleTap")["action"] as? String == "single")
    }
}
