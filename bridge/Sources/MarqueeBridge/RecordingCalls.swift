import Foundation
import MarqueeCore
import Synchronization

/// The Recordings screen and the Record sheet. Core decides what they show: the upcoming days, the conflicts,
/// History, the sheet's rows and its sentence, which changes deserve a notification. These calls fetch what Core
/// asks about and hand its answer to Kotlin as JSON, so Android and iOS cannot word or group anything differently.
/// Every call is stateless apart from the sheet memo; the journal travels as an opaque string the app keeps.
final class RecordingCalls: Sendable {
    private static let methods: Set<String> = [
        "live.dvr.recordings", "live.dvr.observe", "live.dvr.look", "live.dvr.move", "live.dvr.switch",
        "live.dvr.sheet", "live.dvr.save", "live.dvr.quick",
    ]

    static func handles(_ method: String) -> Bool { methods.contains(method) }

    private struct Memo: Sendable {
        let sheet: String
        let draft: RecordingDraft
    }

    /// The open sheets' drafts before any choice, so a tap replays on the template already fetched instead of
    /// fetching it again. A sheet that is not here (pushed out, or the process restarted) is fetched afresh.
    private let memos = Mutex<[Memo]>([])
    private static let openSheets = 8

    func handle(_ method: String, _ payload: Data, through live: PlexLiveTV) async throws -> Data {
        let decoder = JSONDecoder()
        switch method {
        case "live.dvr.recordings":
            return try await screen(decoder.decode(ScreenCall.self, from: payload), live)
        case "live.dvr.observe":
            return try await observe(decoder.decode(DVRObservation.self, from: payload), live, quietly: false)
        case "live.dvr.look":
            return try await observe(decoder.decode(DVRObservation.self, from: payload), live, quietly: true)
        case "live.dvr.move":
            return try await move(decoder.decode(MoveCall.self, from: payload), live)
        case "live.dvr.switch":
            return try await switchRecording(decoder.decode(SwitchCall.self, from: payload), live)
        case "live.dvr.sheet":
            return try await sheet(decoder.decode(SheetCall.self, from: payload), live)
        case "live.dvr.save":
            return try await save(decoder.decode(SheetCall.self, from: payload), live)
        case "live.dvr.quick":
            return try await quick(decoder.decode(QuickCall.self, from: payload), live)
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    // MARK: Screen

    private struct Snapshot {
        let recordings: LiveRecordings
        let dvr: LiveDVR?
    }

    /// The DVR's rules and airings, and the DVR itself for its tuners. A DVR that does not answer only means no
    /// conflict can be found.
    private func snapshot(_ live: PlexLiveTV) async throws -> Snapshot {
        async let rules = live.rules()
        async let scheduled = live.scheduled()
        async let dvrs = try? live.dvrs()
        let recordings = try await LiveRecordings(rules: rules, scheduled: scheduled)
        let found = await dvrs
        return Snapshot(recordings: recordings, dvr: found?.first)
    }

    private func screen(_ call: ScreenCall, _ live: PlexLiveTV) async throws -> Data {
        let now = Date(timeIntervalSince1970: call.now)
        let found = try await snapshot(live)
        let storage = try? await live.storage(for: found.recordings.rules)
        let guide = await Self.cachedGuide(call.directory, server: live.library.server.id, at: now)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: call.timeZone) ?? TimeZone(secondsFromGMT: call.utcOffset) ?? .gmt
        calendar.locale = Locale(identifier: "en_US")
        // Visiting the screen is a look at the DVR like any other: History comes from what the journal saw.
        var journal = RecordingJournal(text: call.journal)
        let events = journal.observe(found.recordings, dvr: found.dvr, now: now)
        let history = journal.entries(recordings: found.recordings, guide: guide?.allAirings ?? [], now: now)
        let plan = RecordingPlan(found.recordings, dvr: found.dvr)
        return try Reply.ok(ScreenWire(
            plan: plan, guide: guide, calendar: calendar, now: now, storage: storage?.line,
            history: history, journal: journal.text, events: events
        ))
    }

    /// The guide Live TV last saved; nothing until the guide has loaded once, and then there are no alternative
    /// airings to offer and no mismatched rules to find.
    private static func cachedGuide(_ directory: String, server: String, at date: Date) async -> LiveGuide? {
        await GuideCache(directory: URL(fileURLWithPath: directory, isDirectory: true), now: { date })
            .load(server: server, dvr: "lineup")
    }

    private func observe(_ call: DVRObservation, _ live: PlexLiveTV, quietly: Bool) async throws -> Data {
        let now = Date(timeIntervalSince1970: call.now)
        let found = try await snapshot(live)
        var journal = RecordingJournal(text: call.journal)
        if quietly {
            journal.look(found.recordings, dvr: found.dvr, now: now)
            return try Reply.ok(JournalWire(journal: journal.text))
        }
        let events = journal.observe(found.recordings, dvr: found.dvr, now: now)
        return try Reply.ok(JournalWire(journal: journal.text, events: events))
    }

    // MARK: Rules and conflicts

    private func move(_ call: MoveCall, _ live: PlexLiveTV) async throws -> Data {
        let rules = try await live.rules()
        guard rules.indices.contains(call.from), rules[call.from].id == call.id else { throw DvrCalls.changed }
        if let moved = RuleOrder.move(rules, from: call.from, to: call.to) {
            try Task.checkCancellation()
            try await live.move(rules[call.from], after: moved.after)
        }
        return try Reply.ok(Empty())
    }

    private func switchRecording(_ call: SwitchCall, _ live: PlexLiveTV) async throws -> Data {
        let now = Date(timeIntervalSince1970: call.now)
        let found = try await snapshot(live)
        guard let grab = found.recordings.scheduled.first(where: { $0.id == call.grab && $0.isUpcoming }),
              let guide = await Self.cachedGuide(call.directory, server: live.library.server.id, at: now),
              let airing = guide.allAirings.first(where: { $0.id == call.airing })
        else { throw DvrCalls.changed }
        try Task.checkCancellation()
        try await live.switchRecording(grab, to: airing, in: found.recordings)
        var journal = RecordingJournal(text: call.journal)
        journal.noteSkipped(grab, at: now)
        return try Reply.ok(JournalWire(journal: journal.text))
    }

    // MARK: Record sheet

    private func sheet(_ call: SheetCall, _ live: PlexLiveTV) async throws -> Data {
        guard var draft = try await base(call, live) else { return try Reply.ok(nil as SheetWire?) }
        try Self.replay(call.steps, on: &draft)
        return try Reply.ok(SheetWire(draft))
    }

    private func base(_ call: SheetCall, _ live: PlexLiveTV) async throws -> RecordingDraft? {
        if let sheet = call.sheet, let known = memos.withLock({ list in list.first { $0.sheet == sheet }?.draft }) {
            return known
        }
        guard let fresh = try await Self.start(call, live) else { return nil }
        if let sheet = call.sheet {
            memos.withLock { list in
                list.removeAll { $0.sheet == sheet }
                list.append(Memo(sheet: sheet, draft: fresh))
                if list.count > Self.openSheets { list.removeFirst(list.count - Self.openSheets) }
            }
        }
        return fresh
    }

    /// The draft before any choice: the rule being edited, or a new rule from Plex's record template for the
    /// airing. Nil when Plex offers no way to record the airing.
    private static func start(_ call: SheetCall, _ live: PlexLiveTV) async throws -> RecordingDraft? {
        if let id = call.rule {
            guard let rule = try await live.rules().first(where: { $0.id == id }) else { throw DvrCalls.changed }
            return RecordingDraft(editing: rule)
        }
        guard let input = call.airing else { throw BridgeError.unknownMethod("live.dvr.sheet without an airing or a rule") }
        let airing = input.airing(on: call.channel)
        async let templates = live.recordOptions(airing)
        async let saved = try? live.recordingLibraries()
        let options = try await templates
        let libraries = await saved ?? []
        return RecordingDraft(airing: airing, channel: call.channel, options: options, libraries: libraries)
    }

    private static func replay(_ steps: [Step], on draft: inout RecordingDraft) throws {
        for step in steps {
            if let scope = step.scope {
                draft.choose(scope: scope)
            } else if let name = step.row, let row = DraftRowID(rawValue: name) {
                if let on = step.on {
                    draft.set(row, on: on)
                } else if let choice = step.choice {
                    draft.choose(row, choice)
                }
            } else {
                throw PlexError.decoding("unknown record sheet step")
            }
        }
    }

    private func save(_ call: SheetCall, _ live: PlexLiveTV) async throws -> Data {
        // The template is fetched again: a write never relies on a copy that may be minutes old.
        guard var draft = try await Self.start(call, live) else { throw Self.noWayToRecord }
        try Self.replay(call.steps, on: &draft)
        guard let submission = draft.submission else { throw Self.noWayToRecord }
        try Task.checkCancellation()
        if let rule = draft.editing {
            if !submission.values.isEmpty { try await live.update(rule, values: submission.values) }
        } else {
            try await live.record(submission.option, values: submission.values)
        }
        return try Reply.ok(Empty())
    }

    /// A plain Record from the guide: this airing (or the movie) with the padding a live event gets.
    private func quick(_ call: QuickCall, _ live: PlexLiveTV) async throws -> Data {
        let airing = call.airing.airing(on: call.channel)
        guard let option = try await live.recordOptions(airing).defaultOption else { throw Self.noWayToRecord }
        try Task.checkCancellation()
        try await live.record(option, values: RecordingDraft.quickValues(for: airing, option: option))
        return try Reply.ok(Empty())
    }

    private static var noWayToRecord: PlexError { .decoding("Plex has no way to record this airing.") }
}

extension RecordingJournal {
    /// The journal as Kotlin keeps it: an opaque JSON string, since its dates use Foundation's own encoding.
    init(text: String?) {
        self = RecordingJournal.decoded(text.map { Data($0.utf8) })
    }

    var text: String? {
        encoded().flatMap { String(data: $0, encoding: .utf8) }
    }
}

extension LiveGuide {
    var allAirings: [LiveAiring] { airings.values.flatMap { $0 } }
}

// MARK: Requests

private struct ScreenCall: Decodable {
    let directory: String
    let now: TimeInterval
    /// The phone's zone, which decides what "Today" and each day's heading are.
    let timeZone: String
    let utcOffset: Int
    let journal: String?
}

private struct DVRObservation: Decodable { let now: TimeInterval; let journal: String? }
private struct MoveCall: Decodable { let id: String; let from: Int; let to: Int }
private struct SwitchCall: Decodable { let grab: String; let airing: String; let directory: String; let now: TimeInterval; let journal: String? }

/// An airing as the guide listed it, with the channel it is on.
private struct QuickCall: Decodable { let airing: AiringInput; let channel: LiveChannel? }

private struct SheetCall: Decodable {
    /// The sheet's own id, for the memo; a save has none.
    let sheet: String?
    let airing: AiringInput?
    let channel: LiveChannel?
    /// The rule being edited, in place of an airing.
    let rule: String?
    let steps: [Step]
}

/// One choice made in the sheet, replayed over Core's draft: a scope, a menu's choice or a switch.
private struct Step: Decodable {
    let scope: RecordingScope?
    let row: String?
    let choice: String?
    let on: Bool?
}

private struct AiringInput: Decodable {
    let id: String
    let guid: String
    let title: String
    let start: TimeInterval
    let end: TimeInterval
    let liveEvent: Bool?
    let movie: Bool?

    /// The airing Core's draft wants. The guide lists a live event by genre; the draft only asks whether an
    /// airing is a live event or a movie, so the guide's two flags stand in for the genres and the kind.
    func airing(on channel: LiveChannel?) -> LiveAiring {
        LiveAiring(
            id: id, channelKey: channel?.id ?? "", guid: guid, kind: movie == true ? .movie : .other, title: title,
            start: Date(timeIntervalSince1970: start), end: Date(timeIntervalSince1970: end),
            genres: liveEvent == true ? ["Live"] : []
        )
    }
}

// MARK: Replies

/// What a call that touches the journal gives back: the journal to keep, and what changed worth announcing.
private struct JournalWire: Encodable {
    let journal: String?
    var events: [EventWire] = []

    init(journal: String?, events: [RecordingEvent] = []) {
        self.journal = journal
        self.events = events.map(EventWire.init)
    }
}

private struct EventWire: Encodable {
    let id: String
    let kind: String
    let title: String
    let body: String

    init(_ event: RecordingEvent) {
        id = event.id
        kind = event.kind.rawValue
        title = event.title
        body = event.body
    }
}

private func channelLabel(_ number: String?, _ callSign: String?) -> String? {
    let text = [number, callSign].compactMap { $0 }.joined(separator: " ")
    return text.isEmpty ? nil : text
}

private struct ScreenWire: Encodable {
    struct Section: Encodable { let id: String; let title: String }

    let sections: [Section]
    let splitMinWidth: Double
    let days: [DayWire]
    let conflicts: [ConflictWire]
    let rules: [RuleRowWire]
    let history: [HistoryWire]
    let storage: String?
    let journal: String?
    let events: [EventWire]

    init(
        plan: RecordingPlan, guide: LiveGuide?, calendar: Calendar, now: Date, storage: String?,
        history: [HistoryEntry], journal: String?, events: [RecordingEvent]
    ) {
        sections = RecordingsSection.allCases.map { Section(id: $0.id, title: $0.title) }
        splitMinWidth = RecordingsLayout.splitMinWidth
        days = plan.upcoming(now: now, calendar: calendar).map { DayWire($0, plan: plan, now: now, calendar: calendar) }
        conflicts = plan.conflicts(now: now).map { ConflictWire($0, plan: plan, guide: guide, now: now) }
        rules = plan.recordings.rules.map(RuleRowWire.init)
        self.history = history.map(HistoryWire.init)
        self.storage = storage
        self.journal = journal
        self.events = events.map(EventWire.init)
    }
}

private struct DayWire: Encodable {
    let id: String
    let heading: String
    let items: [UpcomingWire]

    init(_ day: UpcomingDay, plan: RecordingPlan, now: Date, calendar: Calendar) {
        id = day.id
        heading = day.heading(now: now, calendar: calendar)
        items = day.items.map { UpcomingWire($0, plan: plan) }
    }
}

private struct UpcomingWire: Encodable {
    let id: String
    let ruleID: String?
    let title: String
    let episodeTitle: String?
    let episodeCode: String?
    let channelKey: String?
    let channel: String?
    let thumb: String?
    let start: TimeInterval
    let end: TimeInterval
    /// "NEW" or "RERUN", when Plex gave the original air date.
    let freshness: String?
    let status: String
    let isRecording: Bool
    let conflictID: String?
    let losesToConflict: Bool
    let padding: String?
    let isSeries: Bool

    init(_ item: UpcomingRecording, plan: RecordingPlan) {
        let grab = item.recording
        id = grab.id
        ruleID = grab.ruleID
        title = grab.title
        episodeTitle = grab.episodeTitle
        episodeCode = grab.episodeCode
        channelKey = grab.channelKey
        channel = channelLabel(grab.channelNumber, grab.channelCallSign)
        thumb = grab.thumb
        start = grab.start.timeIntervalSince1970
        end = grab.end.timeIntervalSince1970
        freshness = item.freshness?.badge
        status = item.status.label
        isRecording = grab.status == .inprogress
        conflictID = item.conflictID
        losesToConflict = item.losesToConflict
        padding = plan.paddingSentence(of: grab)
        isSeries = plan.recordings.rule(grab.ruleID)?.isSeries ?? false
    }
}

private struct ConflictWire: Encodable {
    struct Loser: Encodable {
        let id: String
        let title: String
        let alternatives: [Alternative]
    }

    struct Alternative: Encodable {
        let id: String
        let channelKey: String
        let channel: String?
        let title: String
        let start: TimeInterval
        let end: TimeInterval
    }

    let id: String
    let sentence: String
    let start: TimeInterval
    let end: TimeInterval
    let losers: [Loser]

    init(_ conflict: RecordingConflict, plan: RecordingPlan, guide: LiveGuide?, now: Date) {
        id = conflict.id
        sentence = conflict.sentence
        start = conflict.window.start.timeIntervalSince1970
        end = conflict.window.end.timeIntervalSince1970
        let airings = guide?.allAirings ?? []
        losers = conflict.losers.map { grab in
            Loser(
                id: grab.id, title: grab.title,
                alternatives: plan.alternatives(to: grab, in: airings, now: now).map { airing in
                    Alternative(
                        id: airing.id, channelKey: airing.channelKey, channel: guide?.channel(airing.channelKey)?.label,
                        title: airing.title, start: airing.start.timeIntervalSince1970, end: airing.end.timeIntervalSince1970
                    )
                }
            )
        }
    }
}

private struct RuleRowWire: Encodable {
    let id: String
    let title: String
    let summary: String
    let isSeries: Bool
    let thumb: String?
    let library: String?

    init(_ rule: RecordingRule) {
        id = rule.id
        title = rule.title
        summary = rule.summary
        isSeries = rule.isSeries
        thumb = rule.thumb
        library = rule.librarySectionTitle
    }
}

private struct HistoryWire: Encodable {
    let id: String
    /// "Failed", "Skipped", "Missed" or "Stopped early".
    let outcome: String
    let kind: String
    let title: String
    let episodeTitle: String?
    let episodeCode: String?
    let channel: String?
    let airedAt: TimeInterval
    let noted: TimeInterval
    let reason: String

    init(_ entry: HistoryEntry) {
        id = entry.id
        outcome = entry.outcome.label
        kind = entry.outcome.rawValue
        title = entry.title
        episodeTitle = entry.episodeTitle
        episodeCode = entry.episodeCode
        channel = entry.channel
        airedAt = entry.airedAt.timeIntervalSince1970
        noted = entry.noted.timeIntervalSince1970
        reason = entry.reason
    }
}

private struct SheetWire: Encodable {
    struct Scope: Encodable { let scope: String; let title: String; let detail: String }
    struct Choice: Encodable { let id: String; let label: String }

    struct Row: Encodable {
        let id: String
        let title: String
        /// "toggle" or "menu".
        let kind: String
        let on: Bool?
        let selected: String?
        let choices: [Choice]
        let note: String?

        init(_ row: DraftRow) {
            id = row.id.rawValue
            title = row.title
            switch row.control {
            case .toggle(let value):
                kind = "toggle"
                on = value
                selected = nil
            case .menu(let choice):
                kind = "menu"
                on = nil
                selected = choice
            }
            choices = row.choices.map { Choice(id: $0.id, label: $0.label) }
            note = row.note
        }
    }

    let title: String
    let noun: String
    let channel: String?
    let isEdit: Bool
    let scope: String
    let scopes: [Scope]
    let rows: [Row]
    let summary: String
    let canSave: Bool
    let padsLiveEvent: Bool

    init(_ draft: RecordingDraft) {
        title = draft.subject.title
        noun = switch draft.subject.noun {
        case .episode: "episode"
        case .event: "event"
        case .movie: "movie"
        }
        channel = draft.subject.channelLabel
        isEdit = draft.editing != nil
        scope = draft.scope.rawValue
        scopes = draft.scopes.map { Scope(scope: $0.scope.rawValue, title: $0.title, detail: $0.detail) }
        rows = draft.rows.map(Row.init)
        summary = draft.summary
        canSave = draft.submission != nil && (draft.editing == nil || draft.isChanged)
        padsLiveEvent = draft.padsLiveEvent
    }
}
