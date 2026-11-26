import Foundation
import MarqueeCore

/// Android proves the tuner is on its attached LAN and reads its lineup. Core still owns the tune decision.
struct DirectLiveRoute: Decodable {
    let channel: LiveChannel
    let tunerCount: Int
    let lineup: HDHomeRunChannel?

    struct Plan: Encodable {
        enum Path: String, Encodable { case direct, plex, protected }
        let path: Path
        let reason: String
        var url: URL? = nil
        var handover: Handover? = nil
    }

    struct Handover: Encodable {
        let releaseAt: TimeInterval
        let number: String?
        let sameChannel: Bool
        let id: String
        let title: String
    }

    func plan(using live: PlexLiveTV) async throws -> Plan {
        async let dvrs = live.dvrs()
        async let schedule = live.scheduled()
        return try await plan(dvrs: dvrs, schedule: schedule)
    }

    func plan(dvrs: [LiveDVR], schedule: [ScheduledRecording], at now: Date = .now) -> Plan {
        // Never use stale guide data to tune an unmapped channel or a tuner whose address has changed.
        guard let dvr = dvrs.first(where: { $0.key == channel.dvrKey }),
              let receiver = dvr.receiver(of: channel.id), receiver.device.uuid == channel.device.uuid,
              receiver.device.uri == channel.device.uri, receiver.mapping.number == channel.number
        else { return Plan(path: .plex, reason: "mapping_changed") }

        var channel = self.channel
        channel.device = receiver.device
        channel.device.tunerCount = max(1, min(tunerCount, receiver.device.tunerCount))
        let padded = schedule.compactMap { recording -> ScheduledRecording? in
            guard recording.isUpcoming else { return nil }
            var recording = recording
            recording.start = recording.start.addingTimeInterval(-Double(dvr.startOffsetMinutes) * 60)
            recording.end = recording.end.addingTimeInterval(Double(dvr.endOffsetMinutes) * 60)
            return recording.end > now ? recording : nil
        }
        let context = RouteContext(isOnHomeNetwork: true, tunerReachable: true, lineup: lineup,
                                   dvr: dvr, recordings: padded, now: now)
        switch LiveRouteDecider().route(channel, context) {
        case .direct(let url):
            return Plan(path: .direct, reason: "eligible", url: url,
                        handover: Self.handover(on: channel, dvr: dvr, schedule: padded, now: now))
        case .plex(.recording):
            return Plan(path: .plex, reason: "recording",
                        handover: Self.handover(on: channel, dvr: dvr, schedule: padded, now: now))
        case .plex(.unsupportedCodec): return Plan(path: .plex, reason: "unsupported_codec")
        case .plex: return Plan(path: .plex, reason: "ineligible")
        case .protectedChannel: return Plan(path: .protected, reason: "protected")
        }
    }

    /// While playing direct, allow Plex a five-second head start at the first overlapping grab that fills the device.
    private static func handover(on channel: LiveChannel, dvr: LiveDVR, schedule: [ScheduledRecording], now: Date) -> Handover? {
        let onDevice = schedule.filter { recording in
            recording.channelKey.map { dvr.device(channel.device, carries: $0) } ?? true
        }.sorted { $0.start < $1.start }
        for recording in onDevice {
            let at = max(now, recording.start)
            var busy = 0
            var sameChannel = false
            for other in onDevice {
                if other.start > at { break }
                if other.end <= at { continue }
                busy += 1
                sameChannel = sameChannel || other.channelKey == channel.id
            }
            guard busy >= channel.device.tunerCount else { continue }
            return Handover(releaseAt: recording.start.timeIntervalSince1970 - 5,
                            number: recording.channelNumber, sameChannel: sameChannel, id: recording.id, title: recording.title)
        }
        return nil
    }
}
