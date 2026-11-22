import Foundation
import MarqueeCore

/// Plex owns recording rules and grabs. This adapter uses the same templates, preferences,
/// cancel/stop semantics, and recorded-version selection as the iOS client.
enum DvrCalls {
    private static let methods: Set<String> = [
        "live.dvr.schedule", "live.dvr.options", "live.dvr.record", "live.dvr.update",
        "live.dvr.unschedule", "live.dvr.delete", "live.dvr.recorded",
    ]

    static func handles(_ method: String) -> Bool { methods.contains(method) }

    static func handle(_ method: String, _ payload: Data, through live: PlexLiveTV) async throws -> Data {
        let decoder = JSONDecoder()
        switch method {
        case "live.dvr.schedule":
            return try await Reply.ok(ScheduleWire(schedule(live)))
        case "live.dvr.options":
            let call = try decoder.decode(AiringCall.self, from: payload)
            return try await Reply.ok(options(for: call.guid, through: live).map(OptionWire.init))
        case "live.dvr.record":
            let call = try decoder.decode(RecordCall.self, from: payload)
            let options = try await options(for: call.guid, through: live)
            guard let option = options.first(where: { $0.type == call.type }) else { throw changed }
            try Task.checkCancellation()
            try await live.record(option, values: prefs(call.values, for: option.settings))
        case "live.dvr.update":
            let call = try decoder.decode(RuleCall.self, from: payload)
            guard let rule = try await live.rules().first(where: { $0.id == call.id }) else { throw changed }
            let values = prefs(call.values ?? [:], for: rule.settings)
            if !values.isEmpty { try await live.update(rule, values: values) }
        case "live.dvr.unschedule":
            let call = try decoder.decode(UnscheduleCall.self, from: payload)
            let snapshot = try await schedule(live)
            guard let recording = snapshot.scheduled.first(where: { $0.id == call.id && $0.isUpcoming }),
                  (recording.status == .inprogress) == call.isRecording else { throw changed }
            if recording.status == .inprogress {
                try await live.stop(recording, deleting: snapshot.rule(deletedWhenStopping: recording, deletingSeries: call.deletingSeries))
            } else if let rule = snapshot.rule(recording.ruleID), !rule.isSeries {
                // Skipping a one-off would let it come back after Plex restarts.
                try await live.delete(rule, stopping: snapshot.running(under: rule))
            } else {
                try await live.cancel(recording)
            }
        case "live.dvr.delete":
            let call = try decoder.decode(RuleCall.self, from: payload)
            let snapshot = try await schedule(live)
            guard let rule = snapshot.rule(call.id) else { throw changed }
            try await live.delete(rule, stopping: snapshot.running(under: rule))
        case "live.dvr.recorded":
            let sections = Set(try await live.rules().compactMap(\.targetLibrarySectionID))
            let items = try await live.library.recordedItems(inSections: sections)
            return try Reply.ok(items.compactMap(RecordedWire.init))
        default:
            throw BridgeError.unknownMethod(method)
        }
        return try Reply.ok(Empty())
    }

    private static var changed: PlexError { .server(status: 409, message: "The recording schedule changed.") }

    private static func schedule(_ live: PlexLiveTV) async throws -> LiveRecordings {
        async let rules = live.rules()
        async let scheduled = live.scheduled()
        return try await LiveRecordings(rules: rules, scheduled: scheduled)
    }

    private static func options(for guid: String, through live: PlexLiveTV) async throws -> [RecordOption] {
        guard !guid.isEmpty else { return [] }
        // The Core template request only uses the guide's GUID. Keep Plex's opaque parameters
        // and hidden settings in Core; fetch the current template again for a write.
        let airing = LiveAiring(id: "", channelKey: "", guid: guid, title: "", start: .distantPast, end: .distantPast)
        let options = try await live.recordOptions(airing)
        return [options.defaultOption, options.wholeShow].compactMap { $0 }
    }

    private static func prefs(_ values: [String: String], for settings: [RecordSetting]) -> [String: String] {
        var result: [String: String] = [:]
        for setting in SettingWire.visible(settings) {
            if let value = values[setting.id], value != setting.value { result[setting.id] = value }
        }
        return result
    }
}

private struct AiringCall: Decodable { let guid: String }
private struct RecordCall: Decodable { let guid: String; let type: Int; let values: [String: String] }
private struct RuleCall: Decodable { let id: String; let values: [String: String]? }
private struct UnscheduleCall: Decodable { let id: String; let isRecording: Bool; let deletingSeries: Bool }

private struct ScheduleWire: Encodable {
    let rules: [RuleWire]
    let scheduled: [ScheduledWire]

    init(_ recordings: LiveRecordings) {
        rules = recordings.rules.map(RuleWire.init)
        scheduled = recordings.scheduled.compactMap { recording in
            recordings.mark(of: recording).map { ScheduledWire(recording, mark: $0) }
        }
    }
}

private struct RuleWire: Encodable {
    let id: String
    let title: String
    let isSeries: Bool
    let airingsType: String?
    let library: String?
    let settings: [SettingWire]

    init(_ rule: RecordingRule) {
        id = rule.id
        title = rule.title
        isSeries = rule.isSeries
        airingsType = rule.airingsType
        library = rule.librarySectionTitle
        settings = SettingWire.visible(rule.settings).map(SettingWire.init)
    }
}

private struct ScheduledWire: Encodable {
    let id: String
    let ruleID: String?
    let mark: String
    let isUpcoming: Bool
    let isRecording: Bool
    let title: String
    let episodeCode: String?
    let episodeTitle: String?
    let channelKey: String?
    let channelNumber: String?
    let start: TimeInterval

    init(_ recording: ScheduledRecording, mark: RecordingMark) {
        id = recording.id
        ruleID = recording.ruleID
        self.mark = switch mark {
        case .scheduled: "scheduled"
        case .series: "series"
        case .recording: "recording"
        case .failed: "failed"
        case .recorded: "recorded"
        }
        isUpcoming = recording.isUpcoming
        isRecording = recording.status == .inprogress
        title = recording.title
        episodeCode = recording.episodeCode
        episodeTitle = recording.episodeTitle
        channelKey = recording.channelKey
        channelNumber = recording.channelNumber
        start = recording.start.timeIntervalSince1970
    }
}

private struct OptionWire: Encodable {
    let type: Int
    let title: String
    let isSeries: Bool
    let library: String?
    let settings: [SettingWire]

    init(_ option: RecordOption) {
        type = option.type
        title = switch option.kind {
        case .episode: "Record episode"
        case .movie: "Record movie"
        case .show: "Record series"
        case nil: option.title
        }
        isSeries = option.kind == .show
        library = option.librarySectionTitle
        settings = SettingWire.visible(option.settings).map(SettingWire.init)
    }
}

private struct SettingWire: Encodable {
    struct Choice: Encodable { let value: String; let label: String }

    let id: String
    let type: String
    let value: String
    let choices: [Choice]

    init(_ setting: RecordSetting) {
        id = setting.id
        type = setting.type
        value = setting.value
        choices = setting.choices.map { Choice(value: $0.value, label: $0.label) }
    }

    // The shipped iOS editor exposes padding and repeats; every other preference stays at Plex's value.
    static func visible(_ settings: [RecordSetting]) -> [RecordSetting] {
        ["startOffsetMinutes", "endOffsetMinutes", "onlyNewAirings"].compactMap { id in
            settings.first { $0.id == id && !$0.isHidden }
        }
    }
}

private struct RecordedWire: Encodable {
    let ratingKey: String
    let titleKey: String
    let mediaID: Int
    let title: String
    let episodeCode: String?
    let episodeTitle: String?
    let year: Int?
    let thumb: String?
    let recordedAt: TimeInterval?
    let duration: Int
    let viewOffset: Int

    init?(_ item: PlexMetadata) {
        guard let index = item.recordedMediaIndex, item.media[index].id > 0 else { return nil }
        ratingKey = item.ratingKey
        titleKey = item.grandparentRatingKey ?? item.ratingKey
        mediaID = item.media[index].id
        let name = item.type == .episode ? (item.grandparentTitle ?? item.title) : item.title
        title = name
        if item.type == .episode, let season = item.parentIndex, let episode = item.index {
            episodeCode = "S\(season) · E\(episode)"
        } else {
            episodeCode = nil
        }
        episodeTitle = item.type == .episode && item.title != name ? item.title : nil
        year = item.type == .movie ? item.year : nil
        thumb = item.thumb ?? item.art ?? item.grandparentArt
        recordedAt = item.recordedAt?.timeIntervalSince1970
        duration = item.media[index].duration > 0 ? item.media[index].duration : item.duration
        viewOffset = item.viewOffset
    }
}
