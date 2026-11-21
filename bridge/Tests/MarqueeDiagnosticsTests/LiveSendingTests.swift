import Foundation
import MarqueeCore
import Testing

@testable import MarqueeDiagnostics

extension Diagnostics {
    /// When the live log goes to the ntfy topic (DESIGN §8.12): on the clock the test moves.
    @Suite struct LiveSending {
        @Test func aQuietLogSchedulesNothing() {
            let h = Harness()
            h.configure()
            h.settle()
            #expect(h.executor.scheduled == 0)
            h.executor.advance(by: 120)
            #expect(h.ntfy.count == 0)
        }

        @Test func linesGatherForTenSecondsThenGoTogether() throws {
            let h = Harness()
            h.configure(token: "tk_0123456789abcdef")
            h.settle()
            h.note("first")
            h.executor.advance(by: 4)
            h.note("second")
            h.executor.advance(by: 5)
            h.note("third")
            #expect(h.ntfy.count == 0)
            h.executor.advance(by: 1)
            #expect(h.ntfy.count == 1)
            let request = try #require(h.ntfy.requests.first)
            #expect(request.method == .post)
            #expect(request.query("title") == Harness.title)
            #expect(request.headers["Authorization"] == "Bearer tk_0123456789abcdef")
            #expect(request.headers["Priority"] == "1")
            #expect(request.headers["Tags"] == nil)
            #expect(request.header == "launch=aaaaaa seq=2")
            #expect(request.lines.count == 3)
            #expect(request.lines[0].hasSuffix(" app first"))
            #expect(request.lines[1].hasSuffix(" app second"))
            #expect(request.lines[2].hasSuffix(" app third"))
        }

        @Test func theNextBatchWaitsAFreshTenSeconds() {
            let h = Harness()
            h.configure()
            h.settle()
            h.note("one")
            h.executor.advance(by: 10)
            #expect(h.ntfy.count == 1)
            h.note("two")
            h.executor.advance(by: 9.75)
            #expect(h.ntfy.count == 1)
            h.executor.advance(by: 0.25)
            #expect(h.ntfy.count == 2)
        }

        @Test func aWarningGoesWithinASecondAtNormalPriority() throws {
            let h = Harness()
            h.configure()
            h.settle()
            h.note("before")
            h.executor.advance(by: 3)
            h.note("slow", .warning, .net)
            h.executor.advance(by: 0.75)
            #expect(h.ntfy.count == 0)
            h.executor.advance(by: 0.25)
            #expect(h.ntfy.count == 1)
            let request = try #require(h.ntfy.requests.first)
            #expect(request.headers["Priority"] == "3")
            #expect(request.headers["Tags"] == "warning")
            #expect(request.lines.count == 2)
            #expect(request.lines[1].contains(" W net slow"))
        }

        @Test func anErrorGoesAtHighPriorityAndWinsOverAWarningInTheBatch() throws {
            let h = Harness()
            h.configure()
            h.settle()
            h.note("soft", .warning)
            h.note("hard", .error)
            h.executor.advance(by: 1)
            let request = try #require(h.ntfy.requests.first)
            #expect(request.headers["Priority"] == "4")
            #expect(request.headers["Tags"] == "rotating_light")
            #expect(request.lines.count == 2)
        }

        @Test func flushSendsAtOnceAndReturnsWhenItIsOut() async {
            let h = Harness()
            h.configure()
            h.settle()
            h.note("leaving")
            await h.log.flush()
            #expect(h.ntfy.count == 1)
            #expect(h.ntfy.requests[0].lines.last?.hasSuffix(" app leaving") == true)
            await h.log.flush()
            #expect(h.ntfy.count == 1)
        }

        @Test func oneMessageIsInFlightAtATime() {
            let h = Harness()
            h.ntfy.behave(.hold)
            h.configure()
            h.executor.advance(by: 10)
            #expect(h.ntfy.count == 1)
            h.note("later", .warning)
            h.executor.advance(by: 5)
            #expect(h.ntfy.count == 1)
            h.ntfy.behave(.answer(FakeNtfy.accepted))
            h.ntfy.respond(FakeNtfy.accepted)
            // The answer sends what gathered meanwhile, without waiting for the next tick.
            #expect(h.ntfy.count == 2)
            #expect(h.ntfy.requests[1].lines.last?.contains(" W app later") == true)
        }

        @Test func aBurstIsLimitedToThirtyMessagesThenOneEveryTenSeconds() async {
            let h = Harness()
            h.configure()
            for index in 0..<40 {
                h.note("burst", .info, .app, ["n": index])
                await h.log.flush()
            }
            #expect(h.ntfy.count == 30)
            h.executor.advance(by: 10)
            #expect(h.ntfy.count == 31)
            h.note("after")
            h.executor.advance(by: 9)
            #expect(h.ntfy.count == 31)
            h.executor.advance(by: 1)
            #expect(h.ntfy.count == 32)
        }

        @Test func aFailedSendKeepsTheLinesAndIsTriedAgainAtTheNextTick() async {
            let h = Harness()
            h.configure()
            h.settle()
            h.ntfy.answer(FakeNtfy.status(503))
            h.note("kept")
            h.executor.advance(by: 10)
            #expect(h.ntfy.count == 1)
            #expect(await h.log.status().state == "retrying")
            #expect(await h.log.status().status == 503)
            h.ntfy.answer(FakeNtfy.accepted)
            h.executor.advance(by: 9.75)
            #expect(h.ntfy.count == 1)
            h.executor.advance(by: 0.25)
            #expect(h.ntfy.count == 2)
            #expect(h.ntfy.requests[1].lines.contains { $0.hasSuffix(" app kept") })
            #expect(await h.log.status().state == "sending")
            let journal = await h.journalLines()
            #expect(journal.contains { $0.contains(" N net ntfy.state state=retrying status=503") })
            #expect(journal.contains { $0.contains(" N net ntfy.state state=sending") })
        }

        @Test func aRetryAfterHeaderSetsTheWait() {
            let h = Harness()
            h.configure()
            h.settle()
            h.ntfy.answer(FakeNtfy.status(429, retryAfter: "30"))
            h.note("kept")
            h.executor.advance(by: 10)
            #expect(h.ntfy.count == 1)
            h.ntfy.answer(FakeNtfy.accepted)
            h.executor.advance(by: 29)
            #expect(h.ntfy.count == 1)
            h.executor.advance(by: 1)
            #expect(h.ntfy.count == 2)
        }

        @Test func noAnswerCountsAsAFailureAndTheLinesStayQueued() async {
            let h = Harness()
            h.configure()
            h.settle()
            h.ntfy.answer(FakeNtfy.unreachable)
            h.note("kept")
            h.executor.advance(by: 10)
            let status = await h.log.status()
            #expect(status.state == "retrying")
            #expect(status.status == nil)
            #expect(status.waiting >= 1)
        }

        @Test(arguments: [(401, "needsToken"), (403, "refused")])
        func aRefusalStopsTheLiveLogUntilTheSettingsAreSavedAgain(code: Int, refusal: String) async {
            let h = Harness()
            h.configure()
            h.settle()
            h.ntfy.answer(FakeNtfy.status(code))
            h.note("one", .warning)
            h.executor.advance(by: 1)
            #expect(h.ntfy.count == 1)
            let status = await h.log.status()
            #expect(status.state == "refused")
            #expect(status.refusal == refusal)
            h.note("two", .error)
            h.executor.advance(by: 60)
            #expect(h.ntfy.count == 1)
            h.ntfy.answer(FakeNtfy.accepted)
            h.configure(token: "tk_fresh")
            // The state change the resume logs goes in a message of its own, right after the backlog's.
            #expect(h.ntfy.count >= 2)
            #expect(await h.log.status().state == "sending")
            #expect(h.ntfy.requests[1].lines.contains { $0.hasSuffix(" app two") })
        }

        @Test func aMessageTheServerWillNeverTakeIsDroppedAndTheRestStillGo() async {
            let h = Harness()
            h.configure()
            h.settle()
            h.ntfy.behave(.reply { request in request.text.contains("poison") ? FakeNtfy.status(413) : FakeNtfy.accepted })
            h.note("poison")
            h.executor.advance(by: 10)
            #expect(h.ntfy.count == 1)
            #expect(await h.log.status().waiting <= 1)
            h.note("fine")
            h.executor.advance(by: 10)
            let sent = h.ntfy.requests.map(\.text).joined(separator: "\n")
            #expect(sent.contains(" app fine"))
            #expect(await h.journalLines().contains { $0.contains(" N net ntfy.rejected status=413") })
        }

        @Test func aLongBacklogGoesInMessagesUnderTheLimit() {
            let h = Harness()
            h.configure()
            h.settle()
            let filler = String(repeating: "x", count: 180)
            for index in 0..<60 { h.note("bulk", .info, .app, ["n": index, "pad": filler]) }
            h.executor.advance(by: 10)
            #expect(h.ntfy.count >= 3)
            for request in h.ntfy.requests {
                #expect((request.body?.count ?? 0) <= 4096)
            }
            let lines = h.ntfy.requests.flatMap(\.lines)
            #expect(lines.count == 60)
        }

        @Test func nothingIsSentOrKeptWhileLiveLogsAreOff() async {
            let h = Harness()
            h.configure(live: false)
            h.note("quiet", .error)
            h.executor.advance(by: 120)
            #expect(h.ntfy.count == 0)
            #expect(await h.log.status().state == "off")
            #expect(await h.log.status().waiting == 0)
            #expect(h.executor.scheduled == 0)
        }

        @Test func turningLiveLogsOffDropsWhatWaited() async {
            let h = Harness()
            h.configure()
            h.settle()
            h.note("waiting")
            #expect(await h.log.status().waiting == 1)
            h.configure(live: false)
            #expect(await h.log.status().waiting == 0)
            h.executor.advance(by: 30)
            #expect(h.ntfy.count == 0)
        }

        @Test func linesWrittenBeforeTheSettingsAreKnownGoOnceTheyAre() {
            let h = Harness()
            h.note("early")
            h.executor.advance(by: 30)
            #expect(h.ntfy.count == 0)
            h.configure()
            #expect(h.ntfy.count >= 1)
            #expect(h.ntfy.requests[0].lines.first?.hasSuffix(" app early") == true)
        }

        @Test func unsentLinesSurviveARestartAndGoWithTheNextLaunchesFirstMessage() throws {
            let first = Harness(launch: "aaaaaa")
            first.ntfy.answer(FakeNtfy.unreachable)
            first.configure()
            first.note("lost-at-sea")
            first.executor.advance(by: 10)
            #expect(first.ntfy.count >= 1)

            let second = Harness(launch: "bbbbbb", folder: first.folder)
            second.configure()
            second.executor.advance(by: 10)
            let request = try #require(second.ntfy.requests.first)
            #expect(request.header == "launch=bbbbbb seq=1")
            #expect(request.lines.contains { $0.hasSuffix(" app lost-at-sea") })
        }

        @Test func pastOneMegabyteTheOldestLinesGoAndTheNextMessageSaysHowMany() throws {
            let h = Harness()
            h.configure(live: true)
            h.ntfy.behave(.hold)
            let filler = String(repeating: "y", count: 280)
            for index in 0..<4_000 { h.note("flood", .info, .app, ["n": index, "pad": filler]) }
            // About 1.2 MB was written: the oldest went, and the loss is counted.
            h.executor.advance(by: 10)
            let request = try #require(h.ntfy.requests.first)
            #expect(request.header.hasPrefix("launch=aaaaaa seq=1 dropped="))
        }
    }
}
