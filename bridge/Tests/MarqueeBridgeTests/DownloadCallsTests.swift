import Foundation
import MarqueeCore
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
