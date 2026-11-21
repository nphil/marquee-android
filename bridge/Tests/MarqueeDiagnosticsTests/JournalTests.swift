import Foundation
import MarqueeCore
import Testing

@testable import MarqueeDiagnostics

extension Diagnostics {
    /// The log on the device (DESIGN §8.12): what a line looks like, how files rotate and expire, and what the
    /// next launch reports about the one before it.
    @Suite struct Journal {
        private func names(_ folder: URL) -> [String] {
            ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []).sorted()
        }

        private func size(_ folder: URL, _ name: String) -> Int {
            ((try? FileManager.default.attributesOfItem(atPath: folder.appendingPathComponent(name).path))?[.size] as? NSNumber)?.intValue ?? 0
        }

        @Test func aLineCarriesTheDeviceClockUptimeLevelCategoryNameAndFields() async {
            let h = Harness(offset: 3600)
            h.executor.advance(by: 12.5)
            h.note("open", .notice, .net, ["status": 200, "ok": true, "path": "/library/sections", "who": "Isabel Moon", "gone": nil])
            #expect(await h.journalLines() == [
                "23:13:32.500 +12.5s N net open status=200 ok=yes path=/library/sections who=\"Isabel Moon\" gone=-",
            ])
        }

        @Test func theFileIsNamedForTheLocalStartAndLaunchAndBeginsWithAHeader() async {
            let h = Harness(offset: 3600)
            h.note("one")
            let text = String(decoding: await h.log.journalData(), as: UTF8.self)
            #expect(text.hasPrefix("# Marquee 0.1.1 (7) abc1234 launch=aaaaaa part=1\n"))
            #expect(names(h.folder).contains("20231114-231320-aaaaaa-01.log"))
        }

        @Test func theClockFollowsTheDevicesOffset() async {
            let h = Harness()
            h.note("utc")
            h.log.setUTCOffset(7200)
            h.note("plus-two")
            let lines = await h.journalLines()
            #expect(lines[0].hasPrefix("22:13:20.000 "))
            #expect(lines[1].hasPrefix("00:13:20.000 "))
        }

        @Test func aFileIsCutAtItsLimitAndTheOldestGoWhenTheTotalIsPast() async {
            let policy = DiagJournalPolicy(fileBytes: 600, totalBytes: 1_500, maxAge: 24 * 3600)
            let h = Harness(policy: policy)
            for index in 0..<80 { h.note("rotate", .info, .app, ["n": index]) }
            let logs = names(h.folder).filter { $0.hasSuffix(".log") }
            #expect(logs.count > 1)
            #expect(!logs.contains { $0.hasSuffix("-01.log") })
            #expect(logs.allSatisfy { size(h.folder, $0) <= 600 })
            #expect(logs.reduce(0) { $0 + size(h.folder, $1) } <= 1_500 + 600)
            let lines = await h.journalLines()
            #expect(lines.last?.hasSuffix(" app rotate n=79") == true)
            #expect(!lines.contains { $0.hasSuffix(" app rotate n=0") })
        }

        @Test func filesOlderThanADayAreRemovedWhenTheLogOpens() throws {
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            let executor = ManualExecutor()
            for (name, age) in [("20231112-000000-old000-01.log", 25.0 * 3600), ("20231114-210000-new000-01.log", 3600)] {
                let url = folder.appendingPathComponent(name)
                try Data("# old\nline\n".utf8).write(to: url)
                try FileManager.default.setAttributes([.modificationDate: executor.now.addingTimeInterval(-age)], ofItemAtPath: url.path)
            }
            let h = Harness(folder: folder, executor: executor)
            #expect(names(h.folder).contains("20231114-210000-new000-01.log"))
            #expect(!names(h.folder).contains("20231112-000000-old000-01.log"))
        }

        @Test func eraseEmptiesTheJournalAndItKeepsWriting() async {
            let h = Harness()
            h.note("before")
            await h.log.erase()
            #expect(await h.journalLines().isEmpty)
            h.note("after")
            #expect(await h.journalLines().count == 1)
        }

        @Test func repeatedLinesFoldIntoOneWithACountOnceTheRunEnds() async {
            let h = Harness()
            for _ in 0..<3 { h.note("again") }
            h.note("other")
            let lines = await h.journalLines()
            #expect(lines.count == 3)
            #expect(lines[0].hasSuffix(" app again"))
            #expect(lines[1].hasSuffix(" app again ×2"))
            #expect(lines[2].hasSuffix(" app other"))
        }

        @Test func aRunStillGoingIsWrittenOutAfterFiveSeconds() async {
            let h = Harness()
            for _ in 0..<3 { h.note("again") }
            h.executor.advance(by: 5)
            let lines = await h.journalLines()
            #expect(lines.count == 2)
            #expect(lines[1].hasSuffix(" app again ×2"))
            #expect(h.executor.scheduled == 0)
        }

        @Test func logcatGetsEachEventOnceAsItHappensWithItsLevel() async {
            let h = Harness()
            h.note("a", .notice)
            h.note("b", .warning, .net)
            h.note("c", .error, .plex)
            h.note("c", .error, .plex)
            let logcat = h.logcat.all
            #expect(logcat.map(\.level) == [.notice, .warning, .error, .error])
            #expect(logcat.map(\.line).prefix(3).elementsEqual(await h.journalLines().prefix(3)))
        }

        @Test func aPlaybackTagsPlayerEventsUntilItEnds() async {
            let h = Harness()
            h.log.beginPlayback("p3")
            h.note("seek", .info, .player)
            h.note("tap", .info, .ui)
            h.log.endPlayback()
            h.note("stop", .info, .player)
            let lines = await h.journalLines()
            #expect(lines[0].hasSuffix(" player seek id=p3"))
            #expect(lines[1].hasSuffix(" ui tap"))
            #expect(lines[2].hasSuffix(" player stop"))
        }
    }

    /// How the launch before this one ended, from the marker it left.
    @Suite struct PreviousSession {
        /// What the next launch wrote, after the lines the first one left in the folder.
        private func next(after phase: DiagJournal.Phase?, playback: String? = nil) async -> [String] {
            let first = Harness(launch: "aaaaaa")
            first.note("one")
            first.note("two")
            if let playback { first.log.beginPlayback(playback) }
            switch phase {
            case .ended?: await first.log.end()
            case let phase?: first.log.setPhase(phase)
            case nil: break
            }
            let before = await first.journalLines().count
            let second = Harness(launch: "bbbbbb", folder: first.folder)
            let lines = Array(await second.journalLines().dropFirst(before))
            // `first` owns the folder; keep it alive until the lines are read.
            withExtendedLifetime(first) {}
            return lines
        }

        @Test(arguments: [nil, DiagJournal.Phase.foreground])
        func aLaunchThatNeverLeftTheForegroundEndedUnexpectedlyAndLeavesItsLastLines(phase: DiagJournal.Phase?) async {
            let lines = await next(after: phase)
            #expect(lines.count == 3)
            #expect(lines[0].contains(" W app previous-session-ended-unexpectedly launch=aaaaaa version=0.1.1 phase="))
            #expect(lines[0].hasSuffix(" lines=2"))
            #expect(lines[1].hasPrefix("| ") && lines[1].hasSuffix(" app one"))
            #expect(lines[2].hasPrefix("| ") && lines[2].hasSuffix(" app two"))
        }

        @Test func aLaunchLeftInTheBackgroundEndedOnItsOwn() async {
            let lines = await next(after: .background)
            #expect(lines.count == 1)
            #expect(lines[0].contains(" app previous-session launch=aaaaaa version=0.1.1 phase=background playback=- "))
            #expect(!lines[0].contains(" N app "))
        }

        @Test func aLaunchLeftInTheBackgroundDuringPlaybackMayHaveBeenKilledForMemory() async {
            let lines = await next(after: .background, playback: "p2")
            #expect(lines.count == 3)
            #expect(lines[0].contains(" N app previous-session-ended-in-background launch=aaaaaa "))
            #expect(lines[0].contains(" playback=p2 "))
            #expect(lines[1].hasPrefix("| "))
        }

        @Test func aLaunchThatEndedCleanlyIsOnlyNoted() async {
            let lines = await next(after: .ended)
            #expect(lines.count == 1)
            #expect(lines[0].contains(" app previous-session launch=aaaaaa version=0.1.1 phase=ended "))
        }

        @Test func theFirstLaunchHasNoPreviousSession() async {
            let h = Harness()
            #expect(await h.journalLines().isEmpty)
        }

        @Test func theTailIsTheLastSixtyLinesWithoutTheHeaders() async {
            let first = Harness(launch: "aaaaaa")
            for index in 0..<100 { first.note("n", .info, .app, ["i": index]) }
            let before = await first.journalLines().count
            let second = Harness(launch: "bbbbbb", folder: first.folder)
            let lines = Array(await second.journalLines().dropFirst(before))
            withExtendedLifetime(first) {}
            #expect(lines.count == 61)
            #expect(lines[0].hasSuffix(" lines=60"))
            #expect(lines[1].hasSuffix(" app n i=40"))
            #expect(lines[60].hasSuffix(" app n i=99"))
        }
    }
}
