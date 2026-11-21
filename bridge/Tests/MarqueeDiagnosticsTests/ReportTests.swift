import Foundation
import MarqueeCore
import Testing

@testable import MarqueeDiagnostics

extension Diagnostics {
    /// Report a Problem and Test Connection (DESIGN §8.12), against a stand-in for the ntfy server.
    @Suite struct Reports {
        private static let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0xFF, 0xD9])

        private func report(note: String = "Stuck on the spinner after tapping Play", screenshot: Data? = Reports.jpeg) -> DiagReport {
            DiagReport(
                note: note, source: "button", details: "Phone: Pixel Tablet · Android 15\nScreen: Home\nServer: Plex on 192.168.1.10",
                screenshot: screenshot, screen: "tab=home path=- sheets=-"
            )
        }

        @Test func aReportSendsTheJournalAsAnAttachmentThenTheScreenshotThenASummary() async throws {
            let h = Harness()
            h.configure(token: "tk_0123456789abcdef")
            h.note("opened", .info, .nav, ["url": URL(string: "https://plex.example/library?X-Plex-Token=SUPERSECRETPLEXTOKEN")!])
            let outcome = await h.log.report(report())
            guard case .sent(let id, let attached) = outcome else {
                Issue.record("not sent: \(outcome)")
                return
            }
            #expect(attached)
            let requests = h.ntfy.requests
            #expect(requests.count == 3)

            let log = requests[0]
            let logName = NtfyConfig.reportFilename(device: Harness.device, at: h.executor.now, calendar: h.log.calendar)
            #expect(log.method == .put)
            #expect(log.query("filename") == logName)
            #expect(log.query("title") == Harness.title)
            #expect(log.query("message") == "Report \(id): log")
            #expect(log.headers["Content-Type"] == "text/plain; charset=utf-8")
            #expect(log.headers["Priority"] == "1")
            #expect(log.headers["Authorization"] == "Bearer tk_0123456789abcdef")
            #expect(log.text.hasPrefix("Marquee report \(id) · 2023-11-14T22:13:20Z\n"))
            #expect(log.text.contains("\nFrom: Isabel’s tablet · button\n"))
            #expect(log.text.contains("\nBuild: 0.1.1 (7) abc1234 · launch aaaaaa\n"))
            #expect(log.text.contains("\nScreen: Home\nServer: Plex on 192.168.1.10\n"))
            #expect(log.text.contains("\n\n==== Journal ====\n# Marquee 0.1.1 (7) abc1234 launch=aaaaaa part=1\n"))
            #expect(log.text.contains(" nav opened url=\"https://plex.example/library?X-Plex-Token=…\""))
            #expect(!log.text.contains("SUPERSECRETPLEXTOKEN"))

            let shot = requests[1]
            let shotName = NtfyConfig.reportFilename(device: Harness.device, at: h.executor.now, ext: "jpg", calendar: h.log.calendar)
            #expect(shot.method == .put)
            #expect(shot.headers["Content-Type"] == "image/jpeg")
            #expect(shot.query("filename") == shotName)
            #expect(shot.body == Self.jpeg)

            let summary = requests[2]
            #expect(summary.method == .post)
            #expect(summary.query("title") == Harness.title + " · Report")
            #expect(summary.headers["Priority"] == "4")
            #expect(summary.headers["Tags"] == "memo")
            #expect(summary.text.split(separator: "\n").map(String.init) == [
                "Report \(id) from Isabel’s tablet",
                "Note: Stuck on the spinner after tapping Play",
                "Screen: tab=home path=- sheets=-",
                "Log: \(logName)",
                "Screenshot: \(shotName)",
            ])

            let journal = await h.journalLines()
            #expect(journal.contains { $0.contains(" N app report.send report=\(id) from=button note=yes screenshot=yes") })
            #expect(journal.contains { $0.contains(" N app report.sent report=\(id) attached=yes ") })
        }

        @Test func aReportWithoutANoteOrScreenshotSaysSo() async throws {
            let h = Harness()
            h.configure()
            let outcome = await h.log.report(report(note: "  \n ", screenshot: nil))
            guard case .sent(let id, _) = outcome else {
                Issue.record("not sent: \(outcome)")
                return
            }
            #expect(h.ntfy.count == 2)
            let lines = h.ntfy.requests[1].text.split(separator: "\n").map(String.init)
            #expect(lines[1] == "No note.")
            #expect(!lines.contains { $0.hasPrefix("Screenshot:") })
            #expect(lines[0] == "Report \(id) from Isabel’s tablet")
        }

        @Test func aNoteIsRedactedLikeEverythingElse() async throws {
            let h = Harness()
            h.configure()
            _ = await h.log.report(report(note: "It said Authorization: Bearer abcdef123456 and https://x.example/a?X-Plex-Token=SECRETSECRET"))
            let sent = h.ntfy.requests.map(\.text).joined(separator: "\n")
            #expect(!sent.contains("abcdef123456"))
            #expect(!sent.contains("SECRETSECRET"))
            #expect(sent.contains("Note: It said Authorization: Bearer …"))
        }

        @Test func aServerThatRefusesAttachmentsGetsTheJournalsEndAsMessagesInstead() async throws {
            let h = Harness()
            h.configure()
            for index in 0..<600 { h.note("filler", .info, .app, ["n": index, "pad": String(repeating: "z", count: 60)]) }
            h.ntfy.behave(.reply { request in request.method == .put ? FakeNtfy.status(413) : FakeNtfy.accepted })
            let outcome = await h.log.report(report())
            guard case .sent(let id, let attached) = outcome else {
                Issue.record("not sent: \(outcome)")
                return
            }
            #expect(!attached)
            let requests = h.ntfy.requests
            // The refused upload, the parts, and the summary: the screenshot is not tried.
            #expect(requests[0].method == .put)
            #expect(requests.dropFirst().allSatisfy { $0.method == .post })
            let parts = requests.dropFirst().dropLast()
            #expect(parts.count >= 8)
            for (index, part) in parts.enumerated() {
                #expect(part.text.hasPrefix("Report \(id) part \(index + 1)/\(parts.count)\n"))
                #expect((part.body?.count ?? 0) <= 4096)
                #expect(part.headers["Priority"] == "1")
            }
            let all = parts.map(\.text).joined(separator: "\n")
            #expect(all.contains("Marquee report \(id)"))
            #expect(all.contains("==== Journal (last part) ===="))
            #expect(all.contains(" app filler n=599 "))
            #expect(!all.contains(" app filler n=0 "))
            let summary = try #require(requests.last)
            #expect(summary.text.contains("Log: in the messages before this one"))
            #expect(!summary.text.contains("Screenshot:"))
        }

        @Test(arguments: [
            (401, "The server wants an access token, or does not know this one. Check it in Diagnostic Logs."),
            (403, "The server refused to let this device write to the topic. Check it in Diagnostic Logs."),
            (429, "The server is limiting how fast this device sends. Try again in a minute."),
            (502, "The server answered with an error (502). Try again later."),
            (418, "The server answered with an error (418). Try again later."),
        ])
        func aRefusedReportSaysWhyAndIsLogged(status: Int, text: String) async {
            let h = Harness()
            h.configure()
            h.ntfy.answer(FakeNtfy.status(status))
            let outcome = await h.log.report(report())
            #expect(outcome == .failed(text))
            #expect(h.ntfy.count == 1)
            #expect(await h.journalLines().contains { $0.contains(" W app report.failed report=r") && $0.contains(" status=\(status) ") })
        }

        @Test func aServerThatCannotBeReachedIsSaidSo() async {
            let h = Harness()
            h.configure()
            h.ntfy.answer(FakeNtfy.unreachable)
            let outcome = await h.log.report(report())
            #expect(outcome == .failed("Can't reach the server. Check the address and that this device is online."))
            #expect(await h.journalLines().contains { $0.contains(" W app report.failed ") && $0.contains(" error=cannotConnect") })
        }

        @Test func withNoServerSavedThereIsNothingToSendTo() async {
            let h = Harness()
            h.configure(saved: false)
            #expect(await h.log.report(report()) == .failed("Diagnostic Logs is not set up."))
            #expect(await h.log.test() == nil)
            #expect(h.ntfy.count == 0)
        }

        @Test func aReportIsSentEvenWithLiveLogsOff() async {
            let h = Harness()
            h.configure(live: false)
            let outcome = await h.log.report(report())
            #expect(outcome != .failed("Diagnostic Logs is not set up."))
            #expect(h.ntfy.count == 3)
        }
    }

    @Suite struct TestConnection {
        @Test func aSentMessageIsAPlainNoteTaggedWithACheckMark() async throws {
            let h = Harness()
            h.configure(token: "tk_0123456789abcdef")
            #expect(await h.log.test() == .sent)
            let request = try #require(h.ntfy.requests.first)
            #expect(request.method == .post)
            #expect(request.text == "Test: this device can send its diagnostic log here.")
            #expect(request.query("title") == Harness.title)
            #expect(request.headers["Tags"] == "white_check_mark")
            #expect(request.headers["Priority"] == nil)
            #expect(await h.journalLines().contains { $0.contains(" integration ntfy.test result=sent status=200 ") })
        }

        @Test(arguments: [
            (FakeNtfy.status(401), ConnectionCheck.needsToken),
            (FakeNtfy.status(403), ConnectionCheck.refused),
            (FakeNtfy.unreachable, ConnectionCheck.unreachable),
            (FakeNtfy.status(500), ConnectionCheck.failed(500)),
            (FakeNtfy.status(404), ConnectionCheck.failed(404)),
        ])
        func eachAnswerMeansOneThing(outcome: NtfyOutcome, result: ConnectionCheck) async {
            let h = Harness()
            h.configure()
            h.ntfy.answer(outcome)
            #expect(await h.log.test() == result)
            #expect(await h.journalLines().contains { $0.contains(" N integration ntfy.test result=") })
        }

        @Test func aTestThatGetsThroughResumesALiveLogARefusalHadStopped() async {
            let h = Harness()
            h.configure()
            h.settle()
            h.ntfy.answer(FakeNtfy.status(401))
            h.note("held", .warning)
            h.executor.advance(by: 1)
            #expect(await h.log.status().state == "refused")
            h.ntfy.answer(FakeNtfy.accepted)
            h.ntfy.reset()
            #expect(await h.log.test() == .sent)
            #expect(await h.log.status().state == "sending")
            let sent = h.ntfy.requests.map(\.text)
            #expect(sent.contains { $0.contains(" app held") })
        }
    }
}
