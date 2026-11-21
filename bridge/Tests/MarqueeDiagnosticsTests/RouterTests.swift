import Foundation
import MarqueeCore
import Testing

@testable import MarqueeDiagnostics

/// Kotlin's side of the `diag.*` calls, as JSON, against the router the bridge uses: the same decoding, the
/// same `DiagHub` door and the same pipeline behind it.
final class RouterHarness: @unchecked Sendable {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")
    let executor = ManualExecutor()
    let ntfy = FakeNtfy()
    let logcat = LogcatRecorder()
    let router: DiagRouter

    init() {
        DiagHub.setThreshold(.info)
        router = DiagRouter(executor: executor, send: ntfy.send, logcat: logcat.sink)
    }

    deinit {
        DiagHub.install(nil)
        DiagHub.setThreshold(.info)
        try? FileManager.default.removeItem(at: folder)
    }

    /// 2023-11-14 22:13:20 UTC, in milliseconds.
    static let born = 1_700_000_000_000.0

    @discardableResult
    func call(_ method: String, _ payload: [String: Any] = [:]) async throws -> [String: Any] {
        let reply = try await router.handle(method, try JSONSerialization.data(withJSONObject: payload))
        return try JSONSerialization.jsonObject(with: JSONEncoder().encode(reply)) as? [String: Any] ?? [:]
    }

    @discardableResult
    func start(offset: Int = 0) async throws -> [String: Any] {
        try await call("diag.start", [
            "folder": folder.path, "version": "0.1.1", "build": "7", "commit": "abc1234", "born": Self.born, "offset": offset,
        ])
    }

    @discardableResult
    func configure(
        live: Bool = true, detailed: Bool = false, server: String? = "http://127.0.0.1:2586", topic: String? = "marquee-test",
        token: String? = nil
    ) async throws -> [String: Any] {
        var payload: [String: Any] = ["live": live, "detailed": detailed, "title": Harness.title, "device": Harness.device]
        if let server { payload["server"] = server }
        if let topic { payload["topic"] = topic }
        if let token { payload["token"] = token }
        return try await call("diag.configure", payload)
    }

    func events(_ ops: [[String: Any]], offset: Int? = nil) async throws {
        var payload: [String: Any] = ["ops": ops]
        if let offset { payload["offset"] = offset }
        try await call("diag.events", payload)
    }

    /// An event as Kotlin writes it: `t` in milliseconds, `l` the level's number, fields as `[key, value]`.
    static func event(
        _ name: String, level: Int = 1, category: String = "app", at millis: Double = born, _ fields: [[Any]] = []
    ) -> [String: Any] {
        ["o": "e", "t": millis, "l": level, "c": category, "n": name, "f": fields]
    }

    func journal(last: Int? = nil) async throws -> [String] {
        let reply = try await call("diag.journal", last.map { ["maxBytes": $0] } ?? [:])
        return ((reply["text"] as? String) ?? "").split(separator: "\n").map(String.init).filter { !$0.hasPrefix("#") }
    }
}

extension Diagnostics {
    @Suite struct Router {
        @Test func callsBeforeStartAreRefused() async {
            let r = RouterHarness()
            await #expect(throws: DiagRouter.Failure.notStarted) { try await r.events([]) }
            await #expect(throws: DiagRouter.Failure.notStarted) { try await r.call("diag.state") }
            await #expect(throws: DiagRouter.Failure.unknownMethod("diag.nonsense")) { try await r.call("diag.nonsense") }
        }

        @Test func startReturnsTheLaunchIdAndIsIdempotent() async throws {
            let r = RouterHarness()
            let first = try await r.start()
            let launch = try #require(first["launch"] as? String)
            #expect(launch.count == 6)
            #expect(launch.allSatisfy { $0.isNumber || ($0.isLowercase && $0.isASCII) })
            #expect(try await r.start(offset: 7200)["launch"] as? String == launch)
        }

        @Test func eventsKeepKotlinsClockAndTheirValuesAreWrittenAsCoresOwnAre() async throws {
            let r = RouterHarness()
            try await r.start(offset: 3600)
            try await r.events([RouterHarness.event(
                "http", level: 2, category: "net", at: RouterHarness.born + 12_500,
                [["status", 200], ["ok", true], ["ms", 12.5], ["note", NSNull()], ["path", "/library/sections"], ["who", "Isabel Moon"]]
            )])
            #expect(try await r.journal() == [
                "23:13:32.500 +12.5s N net http status=200 ok=yes ms=12.5 note=- path=/library/sections who=\"Isabel Moon\"",
            ])
        }

        @Test func theOffsetRidesAlongWithEachBatch() async throws {
            let r = RouterHarness()
            try await r.start(offset: 0)
            try await r.events([RouterHarness.event("a")], offset: 7200)
            #expect(try await r.journal().first?.hasPrefix("00:13:20.000 ") == true)
        }

        @Test func eventsThatNameNoKnownLevelCategoryOrNameAreDropped() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.events([
                RouterHarness.event("bad-level", level: 9),
                RouterHarness.event("bad-category", category: "weather"),
                ["o": "e", "l": 1, "c": "app"],
                ["o": "?"],
                RouterHarness.event("good"),
            ])
            let lines = try await r.journal()
            #expect(lines.count == 1)
            #expect(lines[0].hasSuffix(" app good"))
        }

        @Test func everyCategoryIsAccepted() async throws {
            let r = RouterHarness()
            try await r.start()
            let names = DiagCategory.allCases.map(\.rawValue)
            try await r.events(names.enumerated().map { RouterHarness.event("e\($0.offset)", category: $0.element) })
            let lines = try await r.journal()
            #expect(lines.count == names.count)
            for (index, name) in names.enumerated() { #expect(lines[index].contains(" \(name) e\(index)")) }
        }

        @Test func aSecretBearingLineIsRedactedByTheTimeItIsInAnyPlace() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure(token: "tk_0123456789abcdef")
            try await r.events([RouterHarness.event("request", level: 3, category: "net", [
                ["url", "https://plex.example:32400/library/sections?X-Plex-Token=SUPERSECRETPLEXTOKEN&X-Plex-Client-Identifier=abc"],
                ["accessToken", "tk_0123456789abcdef"],
                ["error", "GET https://plex.example/a?X-Plex-Token=SUPERSECRETPLEXTOKEN failed; Authorization: Bearer bearerSecret123"],
                ["status", 502],
            ])])
            r.executor.advance(by: 1)
            let journal = try await r.journal().joined(separator: "\n")
            let topic = r.ntfy.requests.map { $0.text + $0.url.absoluteString }.joined(separator: "\n")
            let logcat = r.logcat.all.map(\.line).joined(separator: "\n")
            for secret in ["SUPERSECRETPLEXTOKEN", "bearerSecret123", "tk_0123456789abcdef"] {
                #expect(!journal.contains(secret))
                #expect(!topic.contains(secret))
                #expect(!logcat.contains(secret))
            }
            #expect(journal.contains("X-Plex-Token=…"))
            #expect(topic.contains("X-Plex-Token=…"))
            #expect(journal.contains(" accessToken=…"))
            #expect(journal.contains(" status=502"))
        }

        @Test func debugEventsAreKeptOnlyUnderTheDetailedSetting() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure(live: false, detailed: false, server: nil, topic: nil)
            try await r.events([RouterHarness.event("quiet-tap", level: 0), RouterHarness.event("info")])
            try await r.configure(live: false, detailed: true, server: nil, topic: nil)
            try await r.events([RouterHarness.event("detailed-tap", level: 0)])
            let lines = try await r.journal()
            #expect(lines.count == 2)
            #expect(lines[0].hasSuffix(" app info"))
            #expect(lines[1].hasSuffix(" D app detailed-tap"))
        }

        @Test func playbackMarksTagTheEventsBetweenThem() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.events([
                ["o": "b", "id": "p1"],
                RouterHarness.event("seek", category: "player"),
                RouterHarness.event("tap", category: "ui"),
                ["o": "x"],
                RouterHarness.event("stop", category: "player"),
            ])
            let lines = try await r.journal()
            #expect(lines[0].hasSuffix(" player seek id=p1"))
            #expect(lines[1].hasSuffix(" ui tap"))
            #expect(lines[2].hasSuffix(" player stop"))
        }

        @Test func configureSaysWhetherAServerIsSetUpAndStateSaysHowSendingGoes() async throws {
            let r = RouterHarness()
            try await r.start()
            #expect(try await r.configure(live: true, server: nil, topic: nil)["configured"] as? Bool == false)
            #expect(try await r.call("diag.state")["state"] as? String == "off")
            #expect(try await r.configure()["configured"] as? Bool == true)
            try await r.call("diag.flush")
            let state = try await r.call("diag.state")
            #expect(state["state"] as? String == "sending")
            #expect(state["waiting"] as? Int == 0)
            #expect((state["lastSent"] as? Double ?? 0) >= 1_700_000_000_000)
            #expect(r.ntfy.count == 1)
        }

        @Test func parseNamesTheOneProblemOrTheHostTheReportGoesTo() async throws {
            let r = RouterHarness()
            try await r.start()
            func parse(_ server: String, _ topic: String = "marquee-logs", _ token: String = "") async throws -> (problem: String?, host: String?) {
                let reply = try await r.call("diag.parse", ["server": server, "topic": topic, "token": token])
                return (reply["problem"] as? String, reply["host"] as? String)
            }
            #expect(try await parse("https://ntfy.example.com/").host == "ntfy.example.com")
            #expect(try await parse("https://ntfy.example.com/").problem == nil)
            #expect(try await parse("http://192.168.1.5:8080").problem == nil)
            #expect(try await parse("ntfy.example.com").problem == "scheme")
            #expect(try await parse("ftp://ntfy.example.com").problem == "scheme")
            #expect(try await parse("http://ntfy.example.com").problem == "plaintext")
            #expect(try await parse("https://ntfy.example.com", "no spaces").problem == "topic")
            #expect(try await parse("https://ntfy.example.com", "").problem == "topic")
            #expect(try await parse("https://ntfy.example.com", "marquee-logs", "has space").problem == "token")
        }

        @Test func theJournalCanBeLimitedToItsLastBytes() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.events((0..<50).map { RouterHarness.event("line", [["n", $0]]) })
            let all = try await r.journal()
            let tail = try await r.journal(last: 200)
            #expect(all.count == 50)
            #expect(tail.count < all.count)
            #expect(tail.last == all.last)
        }

        @Test func aReportCarriesTheScreenshotInBase64AndSaysWhatWasSent() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure(live: false)
            let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02, 0x03, 0xFF, 0xD9])
            let reply = try await r.call("diag.report", [
                "note": "It froze", "source": "settings", "details": "Phone: Pixel Tablet", "screen": "tab=home",
                "screenshot": jpeg.base64EncodedString(),
            ])
            #expect(reply["sent"] as? Bool == true)
            #expect(reply["attached"] as? Bool == true)
            let id = try #require(reply["id"] as? String)
            #expect(r.ntfy.requests.count == 3)
            #expect(r.ntfy.requests[1].body == jpeg)
            #expect(r.ntfy.requests[2].text.hasPrefix("Report \(id) from Isabel’s tablet\nNote: It froze\nScreen: tab=home\n"))
        }

        @Test func aReportThatCannotGoReturnsWhatToTellThePerson() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure()
            r.ntfy.answer(FakeNtfy.status(403))
            let reply = try await r.call("diag.report", ["note": "", "source": "button", "details": "", "screen": "-"])
            #expect(reply["sent"] as? Bool == false)
            #expect((reply["text"] as? String)?.hasPrefix("The server refused to let this device write to the topic.") == true)
        }

        @Test func aTestSaysWhatCameOfIt() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure()
            #expect(try await r.call("diag.test")["result"] as? String == "sent")
            r.ntfy.answer(FakeNtfy.status(500))
            let failed = try await r.call("diag.test")
            #expect(failed["result"] as? String == "failed")
            #expect(failed["status"] as? Int == 500)
            r.ntfy.answer(FakeNtfy.status(401))
            #expect(try await r.call("diag.test")["result"] as? String == "needsToken")
            r.ntfy.answer(FakeNtfy.unreachable)
            #expect(try await r.call("diag.test")["result"] as? String == "unreachable")
        }

        @Test func aTestWithNoServerFails() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure(live: false, server: nil, topic: nil)
            await #expect(throws: DiagRouter.Failure.notSetUp) { try await r.call("diag.test") }
        }

        @Test func aCrashSendsTheEventAndTheJournalBeforeItReturns() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure()
            r.executor.advance(by: 10)
            r.ntfy.reset()
            try await r.call("diag.crash", ["ops": [RouterHarness.event(
                "crash", level: 4, [["thread", "main"], ["exception", "java.lang.IllegalStateException"]]
            )]])
            #expect(r.ntfy.count == 1)
            #expect(r.ntfy.requests[0].text.contains(" E app crash thread=main exception=java.lang.IllegalStateException"))
            #expect(r.ntfy.requests[0].headers["Priority"] == "4")
            let file = try #require(try FileManager.default.contentsOfDirectory(atPath: r.folder.path).first { $0.hasSuffix(".log") })
            #expect(try String(contentsOf: r.folder.appendingPathComponent(file), encoding: .utf8).contains(" E app crash thread=main"))
        }

        @Test func theSessionMarkerFollowsThePhaseKotlinReports() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.call("diag.phase", ["phase": "foreground"])
            try await r.call("diag.phase", ["phase": "background"])
            try await r.call("diag.end")
            let marker = try Data(contentsOf: r.folder.appendingPathComponent("session.json"))
            #expect(String(decoding: marker, as: UTF8.self).contains("\"phase\":\"ended\""))
        }

        @Test func eraseEmptiesTheJournalAndWhatWaitsToBeSent() async throws {
            let r = RouterHarness()
            try await r.start()
            try await r.configure()
            try await r.events([RouterHarness.event("waiting")])
            try await r.call("diag.erase")
            #expect(try await r.journal().isEmpty)
            #expect(try await r.call("diag.state")["waiting"] as? Int == 0)
        }
    }
}
