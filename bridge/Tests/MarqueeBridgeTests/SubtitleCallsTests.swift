import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

@Suite struct SubtitleCallsTests {
    @Test func connectionChecksUseOnlyTheServiceOrigin() async throws {
        let server = Server()
        let services = services(server)
        let rejected = try await subtitle("bazarr.address", ["address": "http://bazarr.example.test", "key": server.key], in: services, on: server)
        #expect(rejected["problem"] as? String == "plaintext")
        let address = try await subtitle("speech.address", ["address": "https://speech.example.test/path"], in: services, on: server)
        #expect(address["problem"] as? String == "notOrigin")

        let bazarr = try await subtitle("bazarr.status", ["address": "https://bazarr.example.test/tools", "key": server.key], in: services, on: server)
        let speech = try await subtitle("speech.status", ["address": "https://speech.example.test"], in: services, on: server)
        #expect(bazarr["version"] as? String == "1.5.3")
        #expect(bazarr["radarr"] as? String == "5.0")
        #expect(speech["version"] as? String == "2.0")
        let requests = server.requests
        #expect(requests.count == 2 && requests.allSatisfy { $0.method == .get && $0.restrictToOrigin })
        #expect(requests.first?.url.path == "/tools/api/system/status")
        #expect(requests.first?.headers["X-API-KEY"] == server.key)
        #expect(requests.last?.headers["X-API-KEY"] == nil)
        #expect(requests.allSatisfy { $0.headers["X-Plex-Token"] == nil })
    }

    @Test func externalRemovalRequiresCurrentFileAndSingleUseConfirmation() async throws {
        let server = Server()
        let (session, id) = try await play(in: services(server))
        let embedded = RemoveSubtitleCall(session: id, ratingKey: "7", partId: 18, stream: 2)
        do {
            _ = try await session.removeExternalSubtitle(embedded, confirm: false, transport: server)
            Issue.record("Offered to delete an audio stream")
        } catch PlayError.noFile {}
        var call = RemoveSubtitleCall(session: id, ratingKey: "7", partId: 18, stream: 30)
        let offer = try await session.removeExternalSubtitle(call, confirm: false, transport: server)
        #expect(!server.requests.contains { $0.method == .delete })
        call.ticket = try #require(offer.ticket)
        let reply = try await session.removeExternalSubtitle(call, confirm: true, transport: server)
        let tracks = try #require(reply.tracks)
        #expect(!tracks.subtitles.contains { $0.id == 30 })
        do {
            _ = try await session.removeExternalSubtitle(call, confirm: true, transport: server)
            Issue.record("Accepted a used deletion ticket")
        } catch PlayError.noFile {}
        let writes = server.requests.filter { $0.method == .delete }
        #expect(writes.map(\.url.path) == ["/library/streams/30"])
        #expect(writes.first?.restrictToOrigin == true && writes.first?.isIdempotent == false)
    }

    @Test func providerListDoesNotDownloadAndSupersededChoicesCannotWrite() async throws {
        let server = Server(listBazarrFile: false)
        let (session, id) = try await play(in: services(server))
        let bazarr = FindSubtitlesCall.Bazarr(address: "https://bazarr.example.test", apiKey: server.key)
        var call = FindSubtitlesCall(session: id, ratingKey: "7", partId: 18, request: "manual", bazarr: bazarr)
        let first = try await session.subtitleChoices(call, transport: server)
        call.choice = try #require(first.first).key
        let second = try await session.subtitleChoices(call, transport: server)
        do {
            _ = try await session.findSubtitles(call, transport: server, waits: [.zero])
            Issue.record("A superseded provider choice was accepted")
        } catch PlayError.noFile {}
        #expect(!server.requests.contains { $0.url.path == "/api/providers/movies" && $0.method == .post })
        let selected = try #require(second.first)
        #expect(selected.label.contains("OpenSubtitles"))
        call.choice = selected.key
        let result = try await session.findSubtitles(call, transport: server, waits: [.zero])
        #expect(result.awaitingFile)
        #expect(server.requests.filter { $0.url.path == "/api/providers/movies" && $0.method == .post }.count == 1)
    }

    @Test func aBazarrFileRefreshesThePlayingPartAndFindingAgainFallsBackToPlex() async throws {
        let server = Server(listBazarrFile: false)
        let services = services(server)
        let (session, id) = try await play(in: services)
        let bazarr = FindSubtitlesCall.Bazarr(address: "https://bazarr.example.test", apiKey: server.key)
        let find = FindSubtitlesCall(session: id, ratingKey: "7", partId: 18, request: "find-first", bazarr: bazarr)
        let first = try await session.findSubtitles(find, transport: server, waits: [.zero])
        #expect(first.trackId == nil)
        #expect(first.banner.title == "Plex hasn't listed the new file yet")
        #expect(first.awaitingFile)
        var retry = find
        retry.checkOnly = true
        let count = server.requests.count
        let stillWaiting = try await session.findSubtitles(retry, transport: server, waits: [.zero])
        #expect(stillWaiting.awaitingFile && stillWaiting.trackId == nil)
        #expect(server.requests.dropFirst(count).map(\.method) == [.get])
        #expect(server.requests.last?.url.path == "/library/metadata/7")

        server.listBazarrFile()
        let listed = try await session.findSubtitles(retry, transport: server, waits: [.zero])
        #expect(listed.trackId == 30 && listed.banner.detail == "From Bazarr · OpenSubtitles.com")
        let firstSubtitle = try #require(listed.tracks?.subtitles.first { $0.id == 30 })
        #expect(firstSubtitle.delivery == "sidecar")
        #expect(firstSubtitle.url?.hasSuffix("/library/streams/30?encoding=utf-8&format=srt") == true)
        #expect(server.requests.filter { $0.url.path == "/library/metadata/7/refresh" }.map(\.method) == [.put])
        #expect(server.requests.filter { $0.url.path == "/api/providers/movies" && $0.method == .post }.count == 1)

        let again = FindSubtitlesCall(session: id, ratingKey: "7", partId: 18, request: "find-again", bazarr: bazarr)
        let fallback = try await session.findSubtitles(again, transport: server, waits: [.zero])
        #expect(fallback.trackId == 31 && fallback.banner.detail == "From Plex · OpenSubtitles")
        #expect(server.requests.filter { $0.url.path == "/library/metadata/7/subtitles" && $0.method == .put }.count == 1)
        #expect(server.requests.filter { $0.url.path == "/library/metadata/7/refresh" }.count == 1)
        #expect(server.requests.filter { $0.url.path == "/api/providers/movies" && $0.method == .post }.count == 1)
    }

    @Test func alignmentSendsThePlayingFileIdentityAndReturnsWordStarts() async throws {
        let server = Server()
        let services = services(server)
        let (_, id) = try await play(in: services)
        let bytes = Data("1\n00:00:01,000 --> 00:00:03,000\nHello there\n".utf8)
        let digest = String(repeating: "a", count: 64)
        var prepare: [String: Any] = [
            "session": id, "ratingKey": "7", "partId": 99, "subtitle": 30, "playback": "play-1",
            "address": "https://speech.example.test", "digest": digest, "data": bytes.base64EncodedString(),
        ]
        let wrongFile = try await subtitle("speech.prepare", prepare, in: services, on: server)
        #expect(wrongFile["active"] as? Bool == false)
        prepare["partId"] = 18
        let ready = try await subtitle("speech.prepare", prepare, in: services, on: server)
        #expect(ready["active"] as? Bool == true && ready["nextAtMs"] as? Int == 0)

        let align: [String: Any] = ["session": id, "playback": "play-1", "dueMs": 0]
        let timed = try await subtitle("speech.align", align, in: services, on: server)
        let cue = try #require((timed["cues"] as? [[String: Any]])?.first)
        #expect(cue["startMs"] as? Int == 1_000 && cue["text"] as? String == "Hello there")
        #expect(cue["starts"] as? [Double] == [0.2, 1.05])
        let request = try #require(server.requests.first { $0.url.path == "/api/alignment/windows" })
        let body = try object(try #require(request.body))
        let identity = try #require(body["identity"] as? [String: String])
        #expect(identity["part_id"] == "18" && identity["audio_stream_id"] == "2" && identity["subtitle_stream_id"] == "30")
        #expect(body["subtitle_source_sha256"] as? String == digest)
        #expect(request.restrictToOrigin && request.headers["X-Plex-Token"] == nil && request.headers["X-API-KEY"] == nil)
        _ = try await subtitle("speech.stop", ["session": id, "playback": "play-1"], in: services, on: server)
        let stopped = try await subtitle("speech.align", align, in: services, on: server)
        #expect(stopped["active"] as? Bool == false)
        #expect(server.requests.filter { $0.url.path == "/api/alignment/windows" }.count == 1)
    }

    @Test func revealUsesAndroidOffsetsAndSharesTheFirstWordOfEachLine() throws {
        let cue = RevealCall.Line(text: "🙂 two\nLast line", durationMs: 4_000, measured: [0, 0.4, 1.1, 1.8])
        let word = cue.words(in: .word)
        #expect(word.map(\.start) == [0, 3, 7, 12])
        #expect(word.map(\.end) == [2, 6, 11, 16])
        #expect(word.map(\.atMs) == [0, 400, 1_100, 1_800])
        #expect(cue.words(in: .line).map(\.atMs) == [0, 0, 1_100, 1_100])
    }

    private static let plex = PlexServer(
        name: "Fixture", clientIdentifier: "machine-test", accessToken: "fixture-only",
        connections: [PlexConnection(uri: URL(string: "https://plex.example.test")!, isLocal: true)]
    )

    private func services(_ server: Server) -> PlayServices {
        let library = PlexLibrary(
            server: Self.plex,
            identity: PlexClientIdentity(clientIdentifier: "client-test", version: "1", platformVersion: "14", device: "Pad", deviceName: "Pad"),
            transport: server
        )
        return PlayServices(library: { _ in library }, sessions: PlaySessions(), links: LinkStore(), anime: AnimeLookup())
    }

    private func play(in services: PlayServices) async throws -> (PlaySession, String) {
        let capabilities: [String: Any] = [
            "hevc": false, "hevcMain10": false, "av1": false, "hdrDisplay": false, "dolbyVisionProfiles": [Int](),
            "nativeAudioCodecs": ["aac"], "directContainers": ["mkv"],
        ]
        let fields: [String: Any] = [
            "server": try object(JSONEncoder().encode(Self.plex)), "ratingKey": "7", "quality": 0, "savesData": false,
            "metered": false, "directPlay": "force", "network": 1, "now": 100.0, "capabilities": capabilities,
        ]
        let payload = try JSONSerialization.data(withJSONObject: fields)
        let plan = try reply(await PlayCalls.handle("play.start", payload, using: services))
        let id = try #require(plan["session"] as? String)
        return (try services.session(id), id)
    }

    private func subtitle(_ name: String, _ fields: [String: Any], in services: PlayServices, on server: Server) async throws -> [String: Any] {
        let payload = try JSONSerialization.data(withJSONObject: fields)
        return try reply(await SubtitleCalls.handle("subtitles.\(name)", payload, using: services, transport: server))
    }

    private func reply(_ data: Data) throws -> [String: Any] {
        try #require(object(data)["ok"] as? [String: Any])
    }

    private func object(_ data: Data) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private final class Server: PlexTransport, Sendable {
        private struct State {
            var requests = [PlexRequest]()
            var bazarrListed: Bool
            var plexAdded = false
        }

        let key = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        private let state: Mutex<State>
        var requests: [PlexRequest] { state.withLock { $0.requests } }

        init(listBazarrFile: Bool = true) { state = Mutex(State(bazarrListed: listBazarrFile)) }
        func listBazarrFile() { state.withLock { $0.bazarrListed = true } }

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            state.withLock { $0.requests.append(request) }
            switch (request.url.path, request.method) {
            case ("/", .get): return plex(["allowMediaDeletion": true])
            case ("/library/streams/30", .delete): return PlexHTTPResponse(status: 204, body: Data())
            case ("/tools/api/system/status", .get):
                return json(["data": ["bazarr_version": "1.5.3", "radarr_version": "5.0"]])
            case ("/health", .get): return json(["status": "ok", "version": "2.0"])
            case ("/api/system/searches", .get): return json([["radarrId": 3, "title": "Fixture Film"]])
            case ("/api/movies", .get): return json(["data": [["radarrId": 3, "title": "Fixture Film"]]])
            case ("/api/providers/movies", .get):
                return json(["data": [["provider": "opensubtitlescom", "subtitle": "regular", "language": "en", "score": 90]]])
            case ("/api/providers/movies", .post): return PlexHTTPResponse(status: 204, body: Data())
            case ("/identity", .get): return plex(["machineIdentifier": "machine-test"])
            case ("/library/metadata/7", .get): return plex(["Metadata": [movie()]])
            case ("/library/metadata/7/refresh", .put), ("/playQueues", .post): return plex([:])
            case ("/library/metadata/7/subtitles", .get):
                return plex(["Stream": [[
                    "streamType": 3, "key": "plex-offer", "languageCode": "eng", "providerTitle": "OpenSubtitles", "title": "Plex Release",
                ]]])
            case ("/library/metadata/7/subtitles", .put):
                state.withLock { $0.plexAdded = true }
                return plex([:])
            case ("/api/alignment/windows", .post):
                let body = try JSONSerialization.jsonObject(with: request.body ?? Data()) as? [String: Any] ?? [:]
                return json([
                    "request_id": body["request_id"] ?? "", "generation": body["generation"] ?? 0, "source_revision": "fixture",
                    "cues": [["cue_id": "0", "status": "aligned", "words": [
                        ["start_byte": 0, "end_byte": 5, "start_ms": 1_200, "end_ms": 1_500],
                        ["start_byte": 6, "end_byte": 11, "start_ms": 2_050, "end_ms": 2_350],
                    ]]],
                ])
            default: return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        private func movie() -> [String: Any] {
            var streams: [[String: Any]] = [
                ["id": 1, "streamType": 1, "codec": "h264", "width": 1280, "height": 720],
                ["id": 2, "streamType": 2, "codec": "aac", "languageCode": "eng", "selected": true, "default": true],
            ]
            let (bazarr, plex) = state.withLock { ($0.bazarrListed, $0.plexAdded) }
            if bazarr { streams.append(sidecar(30, "Bazarr Release")) }
            if plex { streams.append(sidecar(31, "Plex Release")) }
            return ["ratingKey": "7", "type": "movie", "title": "Fixture Film", "duration": 60_000, "Media": [[
                "id": 8, "container": "mkv", "videoCodec": "h264", "audioCodec": "aac", "Part": [[
                    "id": 18, "key": "/library/parts/18/file.mkv", "file": "/fixtures/film.mkv", "Stream": streams,
                ]],
            ]]]
        }

        private func sidecar(_ id: Int, _ title: String) -> [String: Any] {
            ["id": id, "streamType": 3, "codec": "srt", "languageCode": "eng", "key": "/library/streams/\(id)", "title": title]
        }

        private func plex(_ fields: [String: Any]) -> PlexHTTPResponse { json(["MediaContainer": fields]) }
        private func json(_ value: Any) -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: value))
        }
    }
}
