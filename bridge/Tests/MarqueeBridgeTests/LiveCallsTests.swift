import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeBridge

@Suite struct LiveCallsTests {
    private static let provider = "/tv.plex.providers.epg.cloud"
    private static let tune = "/livetv/dvrs/12/channels/ch-0201/tune"
    private static let universal = "/video/:/transcode/universal"
    private static let grab = "/media/grabbers/operations/ch-0201-uitest"
    private static let now: TimeInterval = 1_800_002_460

    @Test func guideWidensUsingCoreAndReadsOnlyTheSameScopesCache() async throws {
        let server = Server()
        let cache = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: cache) }
        let directory = cache.appendingPathComponent("scope-1").path
        let fields: [String: Any] = ["now": Self.now, "directory": directory]
        let bridge = LiveCalls()
        let first: Guide = try await server.call(bridge, "live.guide", fields)
        let run = try #require(first.run)
        #expect(first.channels.prefix(2).map(\.number) == ["2.1", "4.1"])
        let current = try #require(first.airings["ch-0201"]?.first { $0.start <= Self.now && Self.now < $0.end })
        #expect(current.title == "The Lighthouse Keepers")
        #expect(current.episodeTitle == "Fog Season")
        #expect(first.to == Self.now + 3 * 3600)
        #expect(first.from == Self.now - 2 * 3600)
        #expect(first.tunerOffline == false)
        let genre = try #require(first.filters.first { $0.title == "News" })
        let keys: [String] = try await server.call(bridge, "live.genre", ["filter": jsonObject(genre)])
        #expect(keys == ["ch-0701", "ch-1301"])

        let full: Guide = try await server.call(bridge, "live.extend", fields.merging(["run": run]) { $1 })
        #expect(full.to == Self.now + 24 * 3600)
        let shows = try #require(full.airings["ch-0201"])
        #expect(shows.count > (first.airings["ch-0201"]?.count ?? 0))
        #expect(Set(shows.map(\.id)).count == shows.count)
        #expect(zip(shows, shows.dropFirst()).allSatisfy { $0.0.end <= $0.1.start })
        let windows = server.requests.filter { $0.url.path == Self.provider + "/grid" }
        #expect(windows.count == 2)
        #expect(windows.last?.url.query("endsAt>") == String(Int(first.to)))

        let requestCount = server.requests.count
        let cached: Guide? = try await server.call(LiveCalls(), "live.cached", fields)
        #expect(cached?.airings["ch-0201"]?.map(\.id) == shows.map(\.id))
        let other: Guide? = try await server.call(LiveCalls(), "live.cached", ["now": Self.now, "directory": cache.appendingPathComponent("scope-2").path])
        #expect(other == nil)
        let expired: Guide? = try await server.call(LiveCalls(), "live.cached", ["now": full.to, "directory": directory])
        #expect(expired == nil)
        #expect(server.requests.count == requestCount)
    }

    @Test func unavailableGuideIsNotAnEmptySuccessfulLineup() async throws {
        let empty = Server(dvrStatus: 200, noDvr: true)
        let fields: [String: Any] = ["now": Self.now, "directory": "/unused"]
        let missing: Guide? = try await empty.call(LiveCalls(), "live.guide", fields)
        #expect(missing == nil)
        #expect(!empty.requests.contains { $0.url.path.hasPrefix(Self.provider) })
        let denied = Server(dvrStatus: 403)
        do {
            let _: Guide? = try await denied.call(LiveCalls(), "live.guide", fields)
            Issue.record("a denied profile should not look like an unconfigured DVR")
        } catch let PlexError.server(status, _) {
            #expect(status == 403)
        }
    }

    @Test func tuneReportsThenReleasesTheExactGrabOnce() async throws {
        let server = Server()
        let bridge = LiveCalls()
        let channel = try await server.channel(bridge)
        let stream: Stream = try await server.call(bridge, "live.open", ["channel": jsonObject(channel)])
        #expect(stream.url.path == Self.universal + "/start.m3u8")
        #expect(stream.url.query("path")?.hasPrefix("/livetv/sessions/") == true)
        #expect(stream.url.query("session") == stream.session)
        #expect(stream.url.query(PlexHeader.token) == nil)
        #expect(stream.headers[PlexHeader.token] == "fixture-token")
        let _: EmptyReply = try await server.call(bridge, "live.report", ["session": stream.session, "state": "playing"])
        let _: EmptyReply = try await server.call(bridge, "live.report", ["session": stream.session, "state": "paused"])
        let _: EmptyReply = try await server.call(bridge, "live.close", ["session": stream.session])
        let _: EmptyReply = try await server.call(bridge, "live.close", ["session": stream.session])
        let playback = server.playbackRequests
        #expect(playback.map { $0.url.path } == [Self.tune, Self.universal + "/decision", "/:/timeline", "/:/timeline", "/:/timeline", Self.universal + "/stop", Self.grab])
        #expect(playback.first?.method == .post)
        #expect(playback.first?.headers[PlexHeader.sessionIdentifier] == stream.session)
        #expect(playback.last?.method == .delete)
        #expect(playback.filter { $0.url.path == "/:/timeline" }.compactMap { $0.url.query("state") } == ["playing", "paused", "stopped"])
        #expect(playback.filter { $0.url.path == "/:/timeline" }.allSatisfy { $0.headers[PlexHeader.sessionIdentifier] == stream.session })
    }

    @Test func failedDecisionStillReleasesTheGrab() async throws {
        let server = Server(decisionStatus: 503)
        let bridge = LiveCalls()
        let channel = try await server.channel(bridge)
        do {
            let _: Stream = try await server.call(bridge, "live.open", ["channel": jsonObject(channel)])
            Issue.record("a rejected conversion must not return a playlist")
        } catch let PlexError.server(status, _) {
            #expect(status == 503)
        }
        #expect(server.playbackRequests.last?.url.path == Self.grab)
        #expect(server.playbackRequests.last?.method == .delete)
    }

    @Test func closingAStreamPlexAlreadyEndedIsNotAFailure() async throws {
        let server = Server(releaseStatus: 404)
        let bridge = LiveCalls()
        let channel = try await server.channel(bridge)
        let stream: Stream = try await server.call(bridge, "live.open", ["channel": jsonObject(channel)])
        let _: EmptyReply = try await server.call(bridge, "live.close", ["session": stream.session])
        #expect(server.playbackRequests.last?.url.path == Self.grab)
        #expect(server.playbackRequests.last?.method == .delete)
    }

    @Test func changingScopePreviewsTheRuleWithoutWritingUntilSaved() async throws {
        struct Form: Decodable { let scope: String; let summary: String }
        let server = Server()
        let bridge = LiveCalls()
        let params: [String: Any] = [
            "airing": ["id": "one", "guid": "plex://episode/template", "title": "City Desk", "start": Self.now, "end": Self.now + 3600] as [String: Any],
            "steps": [["scope": "new"]]
        ]
        let form: Form = try await server.call(bridge, "live.dvr.sheet", params)
        #expect(form.scope == "new")
        #expect(form.summary.contains("new episodes of City Desk"))
        #expect(!server.requests.contains { $0.method == .post })
        let _: EmptyReply = try await server.call(bridge, "live.dvr.save", params)
        let write = try #require(server.requests.last { $0.method == .post })
        #expect(write.url.path == "/media/subscriptions")
        #expect(write.url.query("type") == "2")
    }

    @Test func guideProgrammeCreatesPlexEpisodeAndExplicitSeriesRules() async throws {
        let server = Server()
        let bridge = LiveCalls()
        let guide: Guide = try await server.call(bridge, "live.guide", ["now": Self.now, "directory": "/unused"])
        let airing = try #require(guide.airings["ch-0201"]?.first { $0.start <= Self.now && Self.now < $0.end })
        let options: [Option] = try await server.call(bridge, "live.dvr.options", ["guid": airing.guid])
        #expect(options.map(\.type) == [4, 2])
        #expect(options.first?.settings.map(\.id) == ["startOffsetMinutes", "endOffsetMinutes"])

        let _: EmptyReply = try await server.call(bridge, "live.dvr.record", [
            "guid": airing.guid, "type": 4, "values": ["startOffsetMinutes": "5", "oneShot": "false"],
        ])
        let _: EmptyReply = try await server.call(bridge, "live.dvr.record", [
            "guid": airing.guid, "type": 2, "values": ["onlyNewAirings": "0"],
        ])
        let templates = server.requests.filter { $0.url.path == "/media/subscriptions/template" }
        #expect(templates.count == 3)
        #expect(templates.allSatisfy { $0.method == .get && $0.url.query("guid") == airing.guid })
        let writes = server.requests.filter { $0.method == .post && $0.url.path == "/media/subscriptions" }
        #expect(writes.count == 2)
        let episode = try #require(writes.first)
        #expect(!episode.isIdempotent)
        #expect(episode.url.query("type") == "4")
        #expect(episode.url.query("hints[guid]") == "plex://episode/template")
        #expect(episode.url.query("params[mediaProviderID]") == "13")
        #expect(episode.url.query("targetLibrarySectionID") == "2")
        #expect(episode.url.query("targetSectionLocationID") == "3")
        #expect(episode.url.query("prefs[startOffsetMinutes]") == "5")
        #expect(episode.url.query("prefs[endOffsetMinutes]") == "2")
        #expect(episode.url.query("prefs[oneShot]") == "true")
        let series = try #require(writes.last)
        #expect(series.url.query("type") == "2")
        #expect(series.url.query("hints[guid]") == "plex://show/template")
        #expect(series.url.query("prefs[onlyNewAirings]") == "0")
        #expect(series.url.query("prefs[oneShot]") == "false")
    }

    @Test func scheduleUpdatesSeriesAndCancelsTheRightPlexObject() async throws {
        let server = Server()
        let bridge = LiveCalls()
        let schedule: Schedule = try await server.call(bridge, "live.dvr.schedule", [:])
        #expect(schedule.scheduled.first { $0.id == "grab-42" }?.mark == "series")
        #expect(schedule.scheduled.first { $0.id == "grab-41" }?.mark == "recording")
        #expect(schedule.scheduled.first { $0.id == "grab-40" }?.mark == "failed")
        let _: EmptyReply = try await server.call(bridge, "live.dvr.update", [
            "id": "42", "values": ["endOffsetMinutes": "10", "onlyNewAirings": "0", "oneShot": "true"],
        ])
        for (grab, running) in [("grab-42", false), ("grab-43", false), ("grab-41", true)] {
            let _: EmptyReply = try await server.call(bridge, "live.dvr.unschedule", [
                "id": grab, "isRecording": running, "deletingSeries": false,
            ])
        }
        let _: EmptyReply = try await server.call(bridge, "live.dvr.delete", ["id": "42"])
        let update = try #require(server.requests.first { $0.method == .put })
        #expect(update.url.path == "/media/subscriptions/42")
        #expect(update.url.query("prefs[endOffsetMinutes]") == "10")
        #expect(update.url.query("prefs[onlyNewAirings]") == "0")
        #expect(update.url.query("prefs[oneShot]") == nil)
        #expect(server.requests.filter { $0.method == .delete }.map(\.url.path) == [
            "/media/grabbers/operations/grab-42", // Skip an airing, not the series.
            "/media/subscriptions/43", // Delete the one-off so it doesn't come back.
            "/media/grabbers/operations/grab-41", "/media/subscriptions/41", // Free the tuner first.
            "/media/subscriptions/42",
        ])
    }

    @Test func staleSkipCannotStopAnAiringThatHasStarted() async throws {
        let server = Server()
        do {
            let _: EmptyReply = try await server.call(LiveCalls(), "live.dvr.unschedule", [
                "id": "grab-41", "isRecording": false, "deletingSeries": false,
            ])
            Issue.record("A skip must not stop a grab the user did not agree to stop")
        } catch let PlexError.server(status, _) {
            #expect(status == 409)
        }
        #expect(!server.requests.contains { $0.method == .delete })
        do {
            let _: EmptyReply = try await server.call(LiveCalls(), "live.dvr.skipUpcoming", ["id": "grab-41"])
            Issue.record("The live viewer's skip must not stop a recording already in progress")
        } catch let PlexError.server(status, _) {
            #expect(status == 409)
        }
        #expect(!server.requests.contains { $0.method == .delete })
    }

    @Test func recordedListOpensTheNewestCompletedVersionAfterPlexReordersMedia() async throws {
        let server = Server()
        let items: [Recorded] = try await server.call(LiveCalls(), "live.dvr.recorded", [:])
        #expect(items.map(\.ratingKey) == ["2101", "2102", "2103", "2104", "2105"])
        let item = try #require(items.first)
        #expect(item.mediaID == 21011)
        #expect(item.recordedAt == 1_799_922_000)
        #expect(!server.requests.contains { $0.url.path.hasPrefix("/library/sections/1/") })
        let plan: RecordingPlan = try await server.play(ratingKey: item.ratingKey, mediaID: item.mediaID)
        #expect(plan.mediaIndex == 2)
        #expect(plan.stream.url.path == "/library/parts/21011/file.mp4")
        await #expect(throws: PlayError.self) {
            let _: RecordingPlan = try await server.play(ratingKey: item.ratingKey, mediaID: 999)
        }
    }

    private struct Guide: Decodable {
        let channels: [LiveChannel]
        let airings: [String: [Programme]]
        let from: TimeInterval
        let to: TimeInterval
        let filters: [LiveChannelFilter]
        let tunerOffline: Bool
        let run: String?
    }

    private struct Programme: Decodable {
        let id: String
        let guid: String
        let title: String
        let episodeTitle: String?
        let start: TimeInterval
        let end: TimeInterval
    }

    private struct Stream: Decodable {
        let url: URL
        let session: String
        let headers: [String: String]
    }

    private struct Option: Decodable {
        struct Setting: Decodable { let id: String }
        let type: Int
        let settings: [Setting]
    }

    private struct Schedule: Decodable {
        struct Airing: Decodable { let id: String; let mark: String }
        let scheduled: [Airing]
    }

    private struct Recorded: Decodable {
        let ratingKey: String
        let mediaID: Int
        let recordedAt: TimeInterval
    }

    private struct RecordingPlan: Decodable {
        struct File: Decodable { let url: URL }
        let mediaIndex: Int
        let stream: File
    }

    private struct Envelope<T: Decodable>: Decodable { let ok: T }
    private struct EmptyReply: Decodable {}

    private final class Server: PlexTransport, Sendable {
        private static let fixtures = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("core/UITests/FakePlex/pms")
        private static let sample = PlexServer(name: "Fixture Plex", clientIdentifier: "live-fixture", accessToken: "fixture-token", connections: [
            PlexConnection(uri: URL(string: "http://192.0.2.10:32400")!, isLocal: true),
        ])
        private static let identity = PlexClientIdentity(clientIdentifier: "fixture-android", version: "1", platformVersion: "14", device: "Pad", deviceName: "Pad")
        private let recorded = Mutex<[PlexRequest]>([])
        private let dvrStatus: Int
        private let noDvr: Bool
        private let decisionStatus: Int
        private let releaseStatus: Int

        init(dvrStatus: Int = 200, noDvr: Bool = false, decisionStatus: Int = 200, releaseStatus: Int = 200) {
            self.dvrStatus = dvrStatus
            self.noDvr = noDvr
            self.decisionStatus = decisionStatus
            self.releaseStatus = releaseStatus
        }

        var requests: [PlexRequest] { recorded.withLock { $0 } }
        var playbackRequests: [PlexRequest] {
            requests.filter {
                [LiveCallsTests.tune, LiveCallsTests.universal + "/decision", LiveCallsTests.universal + "/stop", "/:/timeline", LiveCallsTests.grab].contains($0.url.path)
            }
        }

        func channel(_ bridge: LiveCalls) async throws -> LiveChannel {
            let guide: Guide = try await call(bridge, "live.guide", ["now": LiveCallsTests.now, "directory": "/unused"])
            return try #require(guide.channels.first { $0.id == "ch-0201" })
        }

        func call<T: Decodable>(_ bridge: LiveCalls, _ method: String, _ fields: [String: Any]) async throws -> T {
            var payload = fields
            payload["server"] = try jsonObject(Self.sample)
            let library = PlexLibrary(server: Self.sample, identity: Self.identity, transport: self)
            let reply = try await bridge.handle(method, JSONSerialization.data(withJSONObject: payload)) { _ in library }
            return try JSONDecoder().decode(Envelope<T>.self, from: reply).ok
        }


        func play<T: Decodable>(ratingKey: String, mediaID: Int) async throws -> T {
            let payload: [String: Any] = [
                "server": try jsonObject(Self.sample), "ratingKey": ratingKey, "recordingMediaID": mediaID,
                "quality": 0, "metered": false, "savesData": false, "directPlay": "auto", "network": 1, "now": 100.0,
                "capabilities": [
                    "hevc": true, "hevcMain10": true, "av1": false, "hdrDisplay": false,
                    "dolbyVisionProfiles": [Int](), "nativeAudioCodecs": ["aac"], "directContainers": ["mp4"],
                ],
            ]
            let library = PlexLibrary(server: Self.sample, identity: Self.identity, transport: self)
            let services = PlayServices(library: { _ in library }, sessions: PlaySessions(), links: LinkStore(), anime: AnimeLookup())
            let reply = try await PlayCalls.handle("play.start", JSONSerialization.data(withJSONObject: payload), using: services)
            return try JSONDecoder().decode(Envelope<T>.self, from: reply).ok
        }
        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            recorded.withLock { $0.append(request) }
            let url = request.url
            let provider = LiveCallsTests.provider
            let universal = LiveCallsTests.universal
            switch url.path {
            case "/identity": return answer(#"{"MediaContainer":{"machineIdentifier":"live-fixture"}}"#)
            case "/livetv/dvrs":
                if dvrStatus != 200 { return PlexHTTPResponse(status: dvrStatus, body: Data()) }
                if noDvr { return answer(#"{"MediaContainer":{"size":0}}"#) }
                return try fixture("livetv/dvrs.json")
            case "/media/providers": return try fixture("media/providers.json")
            case provider + "/lineups/dvr/channels":
                let genre = url.query("genre").map { "@genre=\($0)" } ?? ""
                return try fixture("\(provider.dropFirst())/lineups/dvr/channels\(genre).json")
            case provider + "/grid":
                let source = try Data(contentsOf: Self.fixtures.appendingPathComponent("\(provider.dropFirst())/grid.json"))
                let decoded = try JSONSerialization.jsonObject(with: source) as? [String: Any]
                let from = try #require(url.query("endsAt>").flatMap(Double.init))
                let to = try #require(url.query("beginsAt<").flatMap(Double.init))
                var container = try #require(decoded?["MediaContainer"] as? [String: Any])
                let metadata = try #require(container["Metadata"] as? [[String: Any]])
                container["Metadata"] = metadata.filter { item in
                    (item["Media"] as? [[String: Any]])?.contains {
                        ($0["beginsAt"] as? Double ?? .infinity) < to && ($0["endsAt"] as? Double ?? -.infinity) > from
                    } == true
                }
                return PlexHTTPResponse(status: 200, body: try JSONSerialization.data(withJSONObject: ["MediaContainer": container]))
            case LiveCallsTests.tune: return try fixture("livetv/tune.json")
            case "/media/subscriptions/template": return try fixture("media/subscriptions/template.json")
            case "/media/subscriptions/scheduled": return try fixture("media/subscriptions/scheduled.json")
            case "/media/subscriptions":
                return try fixture("media/subscriptions.json")
            case "/media/subscriptions/41", "/media/subscriptions/42", "/media/subscriptions/43",
                 "/media/grabbers/operations/grab-41", "/media/grabbers/operations/grab-42":
                return PlexHTTPResponse(status: 200, body: Data())
            case "/library/sections": return try fixture("library/sections.json")
            case "/library/sections/2/all" where url.query("type") == "4":
                return try recordingRows()
            case "/library/metadata/2101":
                return try recordingDetails()
            case universal + "/decision": return PlexHTTPResponse(status: decisionStatus, body: Data())
            case "/:/timeline": return PlexHTTPResponse(status: 200, body: Data())
            case universal + "/stop", LiveCallsTests.grab: return PlexHTTPResponse(status: releaseStatus, body: Data())
            default: return PlexHTTPResponse(status: 404, body: Data())
            }
        }

        private func recordingRows() throws -> PlexHTTPResponse {
            var listing = try fixtureObject("library/sections/2/all@type=4.json")
            var rows = try #require(listing["Metadata"] as? [[String: Any]])
            let latest = try #require(rows[0]["Media"] as? [[String: Any]])
            rows[0]["Media"] = latest + [["id": 800, "origin": "dvr", "mediaGrabStatus": "complete", "mediaGrabBeginsAt": 1_799_000_000]]
            rows.append(["ratingKey": "700", "type": "episode", "Media": [["id": 701, "origin": "dvr", "mediaGrabStatus": "recording"]]])
            listing["Metadata"] = rows
            listing["totalSize"] = rows.count
            return try response(listing)
        }

        private func recordingDetails() throws -> PlexHTTPResponse {
            let listing = try fixtureObject("library/sections/2/all@type=4.json")
            let rows = try #require(listing["Metadata"] as? [[String: Any]])
            var item = rows[0]
            func version(_ id: Int, origin: String) -> [String: Any] {
                [
                    "id": id, "origin": origin, "mediaGrabStatus": "complete", "duration": 1_800_000,
                    "container": "mp4", "videoCodec": "h264", "audioCodec": "aac", "width": 720, "height": 480,
                    "Part": [["id": id, "key": "/library/parts/\(id)/file.mp4", "container": "mp4", "Stream": [
                        ["id": 1, "streamType": 1, "codec": "h264", "width": 720, "height": 480],
                        ["id": 2, "streamType": 2, "codec": "aac", "channels": 2, "selected": true],
                    ]]],
                ]
            }
            item["Media"] = [version(900, origin: "library"), version(800, origin: "dvr"), version(21011, origin: "dvr")]
            return try response(["Metadata": [item]])
        }

        private func fixtureObject(_ path: String) throws -> [String: Any] {
            let object = try JSONSerialization.jsonObject(with: fixture(path).body) as? [String: Any]
            return try #require(object?["MediaContainer"] as? [String: Any])
        }

        private func response(_ fields: [String: Any]) throws -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try JSONSerialization.data(withJSONObject: ["MediaContainer": fields]))
        }

        private func fixture(_ path: String) throws -> PlexHTTPResponse {
            PlexHTTPResponse(status: 200, body: try Data(contentsOf: Self.fixtures.appendingPathComponent(path)))
        }

        private func answer(_ json: String) -> PlexHTTPResponse { PlexHTTPResponse(status: 200, body: Data(json.utf8)) }
    }
}

private func jsonObject(_ value: some Encodable) throws -> Any {
    try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
}

private extension URL {
    func query(_ key: String) -> String? {
        URLComponents(url: self, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == key }?.value
    }
}
