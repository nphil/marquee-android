import Foundation
import MarqueeCore
import Testing

@testable import MarqueeBridge

@Suite struct DirectLiveRouteTests {
    private static let now = Date(timeIntervalSince1970: 1_800_002_460)
    private static let device = TunerDevice(key: "21", uuid: "device://tv.plex.grabbers.hdhomerun/0A1B2C3D",
                                            uri: URL(string: "http://192.0.2.18:5004")!, name: "LumaTV", tunerCount: 2, isAlive: true)
    private static let channel = LiveChannel(id: "station", dvrKey: "12", number: "2.1", callSign: "WKTV", title: "WKTV",
                                             logo: nil, isHD: true, device: device)
    private static let dvr = LiveDVR(key: "12", uuid: "fixture", title: "Antenna", epgIdentifier: "fixture", devices: [device],
                                     mappings: [
                                         LiveChannelMapping(channelKey: "station", deviceKey: "21", number: "2.1", isEnabled: true),
                                         LiveChannelMapping(channelKey: "other", deviceKey: "21", number: "4.1", isEnabled: true),
                                     ], startOffsetMinutes: 2, endOffsetMinutes: 1)

    @Test func sharedRuleSuppliesTheTunerURLAndHonorsLineupLimits() {
        let normal = plan()
        #expect(normal.path == .direct)
        #expect(normal.url?.absoluteString == "http://192.0.2.18:5004/auto/v2.1")
        #expect(plan(lineup: HDHomeRunChannel(number: "2.1", name: "WKTV", isProtected: true)).path == .protected)
        #expect(plan(lineup: HDHomeRunChannel(number: "2.1", name: "WKTV", videoCodec: "VP9")).path == .plex)
    }

    @Test func oneTunerIsReservedAtThePaddedStartAndThroughThePaddedEnd() {
        let soon = recording(channel: "other", start: 185, end: 3600)
        #expect(plan(schedule: [soon]).reason == "recording")
        #expect(plan(schedule: [soon], tunerCount: 2).path == .direct)
        let stillRecording = recording(channel: "other", start: -3500, end: -25, status: .inprogress)
        #expect(plan(schedule: [stillRecording]).path == .plex)
        let unrelated = recording(channel: "elsewhere", start: 30, end: 3600)
        #expect(plan(schedule: [unrelated]).path == .direct)
    }

    @Test func handoverUsesPaddedGrabTimeAndOnlySharesTheSameChannel() {
        let same = plan(schedule: [recording(channel: "station", start: 420, end: 3600)])
        #expect(same.path == .direct)
        #expect(same.handover?.releaseAt == Self.now.timeIntervalSince1970 + 295)
        #expect(same.handover?.sameChannel == true)
        let other = plan(schedule: [recording(channel: "other", start: 420, end: 3600)])
        #expect(other.handover?.number == "4.1")
        #expect(other.handover?.sameChannel == false)
    }

    @Test func staleOrDisabledMappingNeverOpensTheCachedTuner() {
        var dvr = Self.dvr
        dvr.mappings[0].isEnabled = false
        #expect(DirectLiveRoute(channel: Self.channel, tunerCount: 1, lineup: nil)
            .plan(dvrs: [dvr], schedule: [], at: Self.now).reason == "mapping_changed")
    }

    private func plan(lineup: HDHomeRunChannel? = nil, schedule: [ScheduledRecording] = [], tunerCount: Int = 1) -> DirectLiveRoute.Plan {
        DirectLiveRoute(channel: Self.channel, tunerCount: tunerCount, lineup: lineup)
            .plan(dvrs: [Self.dvr], schedule: schedule, at: Self.now)
    }

    private func recording(channel: String, start: TimeInterval, end: TimeInterval,
                           status: ScheduledRecording.Status = .scheduled) -> ScheduledRecording {
        ScheduledRecording(id: channel, status: status, channelKey: channel,
                           channelNumber: channel == "station" ? "2.1" : "4.1", title: "Fixture airing",
                           start: Self.now.addingTimeInterval(start), end: Self.now.addingTimeInterval(end))
    }
}
