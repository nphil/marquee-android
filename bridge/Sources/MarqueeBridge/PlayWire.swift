import Foundation
import MarqueeCore

// The player's calls (PlayCalls.swift) as JSON. Kotlin mirrors them in core/PlayCore.kt. Times are seconds unless
// a name ends in `Ms`; `now` is the app's monotonic clock in seconds. Nil values are left out.

/// The profile's Direct Play setting: the tablet plays what it can, always, or never.
enum DirectPlayMode: String, Decodable {
    case auto, force, disabled
}

/// Opens an item: what to play and where, and what the profile and the tablet allow.
struct StartCall: Decodable {
    let ratingKey: String
    /// The player screen's session. A restart, a quality move and the next item keep it; nil opens one.
    let session: String?
    /// Where to start; nil resumes from the server's place.
    let startAt: Double?
    let fromStart: Bool?
    let mediaIndex: Int?
    let recordingMediaID: Int?
    let quality: QualitySetting
    let savesData: Bool
    let metered: Bool
    let directPlay: DirectPlayMode
    let capabilities: CapabilitiesWire
    /// Counts the tablet's network changes, so a link reading from another network is not reused.
    let network: Int
    let now: Double
    /// The video's frame on screen, in pixels.
    let view: PictureWire?
    /// The tablet's player failed on this item, so the server converts it.
    let onDeviceFailed: Bool?
    /// Auto moved to this rung: a restart after `play.look` named it. An index into Auto's ladder, which the bridge keeps
    /// (the rungs of a measured title are not presets).
    let move: Int?
    /// The saved HomeLabber link; nil while its plugin is off. A measured title's curve sets what Auto streams at.
    let homelabber: HomeLabberLink?
    /// The device's class, for HomeLabber's phone or tablet scores; a phone when the app does not say.
    let screen: ScreenClass?
    /// The read `probe` asked for; nil when the plan has not asked.
    let probe: ReadingWire?
    /// Hand picks: an audio stream, the subtitles menu's choice, and what the profile remembers for the item.
    let audio: Int?
    let subtitle: SubtitleChoiceWire?
    let memory: TrackMemory?
}

/// What the tablet's decoders and screen take (`DeviceCapabilities`).
struct CapabilitiesWire: Decodable {
    let hevc: Bool
    let hevcMain10: Bool
    let av1: Bool
    let hdrDisplay: Bool
    let dolbyVisionProfiles: Set<Int>
    let nativeAudioCodecs: Set<String>
    let directContainers: Set<String>

    var capabilities: DeviceCapabilities {
        DeviceCapabilities(
            hevc: hevc, hevcMain10: hevcMain10, av1: av1, hdrDisplay: hdrDisplay,
            dolbyVisionProfiles: dolbyVisionProfiles, nativeAudioCodecs: nativeAudioCodecs,
            directContainers: directContainers
        )
    }
}

struct PictureWire: Decodable {
    let width: Int
    let height: Int

    var picture: AdaptiveQuality.Picture {
        AdaptiveQuality.Picture(width: width, height: height)
    }
}

/// The app's timed read of the file: the bytes that arrived and the seconds they took, zero for a read that failed.
struct ReadingWire: Decodable {
    let bytes: Int
    let seconds: Double
}

/// The subtitles menu: `off`, `automatic` (what the rules pick), or `track` with a stream id.
struct SubtitleChoiceWire: Decodable {
    let kind: String
    let id: Int?

    var choice: SubtitleChoice? {
        switch kind {
        case "off": .off
        case "automatic": .automatic
        case "track": id.map(SubtitleChoice.track)
        default: nil
        }
    }
}

struct SessionCall: Decodable {
    let session: String
}

/// A hand pick to keep for the item: the item's memory with the pick folded in comes back.
struct RememberCall: Decodable {
    let session: String
    let audio: Int?
    let subtitle: SubtitleChoiceWire?
    /// What the profile remembers so far; nil when nothing is yet.
    let memory: TrackMemory?
}

struct ReportCall: Decodable {
    let session: String
    /// `playing`, `paused`, `buffering` or `stopped`.
    let state: String
    let timeMs: Int
    let durationMs: Int
}

struct StopCall: Decodable {
    let session: String
    let timeMs: Int
    let durationMs: Int
    /// The screen is closing: the session ends too.
    let close: Bool
    /// An uncommitted handoff can release resources without changing the active title's timeline.
    let report: Bool?
}

struct LookCall: Decodable {
    let session: String
    let now: Double
    let throughputKbps: Int?
    let transferSeconds: Double
    let bufferSeconds: Double?
    let waitingSeconds: Double
}

struct LinkCall: Decodable {
    let session: String
    let kbps: Int
    let network: Int
    let now: Double
}

// MARK: Offers

/// The profile's autoplay rule (`AutoplayNext`), and whether "Watch Credits" was chosen for the episode.
struct AutoplayCall: Decodable {
    let enabled: Bool
    let credits: CreditsAutoplay
    /// Seconds of the Up Next countdown.
    let delay: Int
    let creditsWatched: Bool
}

/// An intro skip to confirm (`SkipCheck`): where it went, in seconds.
struct SkipBeginCall: Decodable {
    let session: String
    let target: Double
}

/// Where playback is, for the skip being confirmed. `advanced` is the seconds played since the last look; `pending` says a
/// seek, a landing or a restart is still on its way.
struct SkipCheckCall: Decodable {
    let session: String
    let playhead: Double
    let advanced: Double
    let pending: Bool
}

/// A tap on the picture (`SkipTaps`): where across it, how wide it is (the same unit), and when, on a steady clock in seconds.
struct TapCall: Decodable {
    let session: String
    let mode: SkipTapMode
    let x: Double
    let width: Double
    let at: Double
}

// MARK: Replies

/// How an item plays: the route Core chose, what opens it, and what the screen needs around it.
struct PlanWire: Encodable {
    let session: String
    let item: ItemWire
    let mediaIndex: Int
    let startAt: Double
    /// The title's length; 0 when the server does not say.
    let duration: Double
    let route: RouteWire
    /// What opens the stream; nil while `probe` asks for a read of the link first.
    let stream: StreamSpec?
    let probe: ProbeSpec?
    let intro: [MarkerWire]
    let credits: [MarkerWire]
    let chapters: [ChapterWire]
    let tracks: PlayTracksWire
    let isAnime: Bool
    /// The quality the route was decided at: kbit/s, 0 for Original.
    let quality: Int
    /// What Auto streams at, worded for the screen: a preset's name, or a step of the title's measured curve such as
    /// "3.2 Mbps 720p". Nil unless Auto adapts the stream.
    var qualityTitle: String? = nil
    /// Auto adapts the quality on this stream.
    let adapts: Bool
    /// What the first-start badge shows for the stream that plays.
    let badge: BadgeWire
    /// The server's scene-thumbnail index of the part, when it has one.
    let thumbnails: ThumbnailsWire?
}

/// What the first-start badge says: the picture size, the high dynamic range kind and the surround sound. Each is
/// nil when unknown or when it does not apply to the route.
struct BadgeWire: Encodable {
    let resolution: String?
    let range: String?
    let sound: String?

    init(_ badge: FormatBadge) {
        resolution = badge.resolution?.rawValue
        range = Self.word(badge.range)
        sound = Self.word(badge.sound)
    }

    private static func word(_ range: FormatBadge.Range?) -> String? {
        switch range {
        case .hdr10: "hdr10"
        case .hdr10Plus: "hdr10Plus"
        case .hlg: "hlg"
        case .dolbyVision: "dolbyVision"
        case nil: nil
        }
    }

    private static func word(_ sound: FormatBadge.Sound?) -> String? {
        switch sound {
        case .atmos: "atmos"
        case .surround51: "surround51"
        case .surround71: "surround71"
        case nil: nil
        }
    }
}

struct RouteWire: Encodable {
    let kind: String
    /// Why the file does not play as it is, as Core words them.
    let reasons: [String]
    /// The audio stream the route plays.
    let audio: Int?
    let audioIsStandIn: Bool
    let reencodesVideo: Bool
    let maxVideoBitrateKbps: Int?
    let videoResolution: String?
    let subtitle: DeliveryWire

    init(_ route: PlaybackRoute) {
        kind = route.kind.rawValue
        reasons = route.reasons.map(\.description)
        audio = route.audio?.id
        audioIsStandIn = route.audioIsStandIn
        reencodesVideo = route.reencodesVideo
        maxVideoBitrateKbps = route.maxVideoBitrateKbps
        videoResolution = route.videoResolution
        subtitle = DeliveryWire(route.subtitle)
    }
}

/// How the chosen subtitle reaches the screen: `none`, `sidecar` (a file the app loads), `inStream` (inside the
/// played file), `burnedIn` or `notShown`.
struct DeliveryWire: Encodable {
    let kind: String
    let track: Int?

    init(_ delivery: SubtitleDelivery) {
        switch delivery {
        case .none:
            kind = "none"
            track = nil
        case .sidecar(let track):
            kind = "sidecar"
            self.track = track.id
        case .inStream(let track):
            kind = "inStream"
            self.track = track.id
        case .burnedIn(let track):
            kind = "burnedIn"
            self.track = track.id
        case .notShown(let track):
            kind = "notShown"
            self.track = track.id
        }
    }
}

/// What the player opens. `file` is the part itself; `hls` is the server's stream, whose address carries the
/// token and so is never logged. `offset` is where in the title the stream starts.
struct StreamSpec: Encodable {
    let kind: String
    let url: String
    let headers: [String: String]
    /// The server's conversion, to stop when playback ends.
    let session: String?
    let offset: Double
}

/// A timed read of the file for the link's speed: the address without a token, and the limits Core sets.
struct ProbeSpec: Encodable {
    let url: String
    let headers: [String: String]
    let maxBytes: Int
    let windowSeconds: Double
    let timeoutSeconds: Double
}

/// Where the part's BIF index is read: the address without a token, and what authorizes the request.
struct ThumbnailsWire: Encodable {
    let url: String
    let headers: [String: String]
}

/// The server's own account of the conversion playing: what it does to each stream and how fast. Each field is nil when
/// the server does not say; `active` is false when it has no such conversion.
struct TranscodeWire: Encodable {
    let active: Bool
    /// "copy", "transcode" or "burn" (subtitles).
    let video: String?
    let audio: String?
    let subtitle: String?
    /// Encoding speed as a multiple of real time; below 1 the player outruns it.
    let speed: Double?
    let throttled: Bool?
    let fullPipeline: Bool?
    let decoding: String?
    let encoding: String?

    init(_ session: PlexTranscodeSession?) {
        active = session != nil
        video = session?.videoDecision
        audio = session?.audioDecision
        subtitle = session?.subtitleDecision
        speed = session?.speed
        throttled = session?.throttled
        fullPipeline = session?.hardwareFullPipeline
        decoding = session?.hardwareDecoding
        encoding = session?.hardwareEncoding
    }
}

struct MarkerWire: Encodable {
    let start: Int
    let end: Int
    let isFinal: Bool

    init(_ marker: PlexMarker) {
        start = marker.start
        end = marker.end
        isFinal = marker.isFinal
    }
}

struct ChapterWire: Encodable {
    let title: String?
    let start: Int
    let end: Int
    let thumb: String?

    init(_ chapter: PlexChapter) {
        title = chapter.title
        start = chapter.start
        end = chapter.end
        thumb = chapter.thumb
    }
}

/// The playing file's tracks as the Tracks menu lists them.
struct PlayTracksWire: Encodable, Sendable {
    let audio: [PlayAudioWire]
    /// Every subtitle in file order with the separate files last, so the embedded ones line up with the player's own
    /// text tracks. A menu leaves out the `isHidden` ones: a separate file that replaces an embedded copy hides it.
    let subtitles: [PlaySubtitleWire]
    /// The track On puts on screen, which the rules pick with the audio that plays; nil when the file has no English text.
    let automatic: Int?
    /// Sent with a subtitle download.
    let headers: [String: String]
}

struct PlayAudioWire: Encodable {
    let id: Int
    let label: String
    let language: String?
    let isDefault: Bool
}

struct PlaySubtitleWire: Encodable {
    let id: Int
    let label: String
    let language: String?
    let isDefault: Bool
    let isExternal: Bool
    let isHidden: Bool
    /// How the track shows on this route if it is picked: `sidecar`, `inStream` or `notShown`.
    let delivery: String
    /// Where a separate text file downloads as SRT; nil for a track inside the file and for pictures.
    let url: String?
}

/// The items on either side of the one playing in the play queue.
struct NeighborsWire: Encodable {
    let next: ItemWire?
    let previous: ItemWire?
}

struct TookWire: Encodable {
    let took: Bool
}

/// Auto's answer to a look: the rung to move to, if any, and what it knows of the link.
struct MoveWire: Encodable {
    /// An index into Auto's ladder, to hand back as `StartCall.move`.
    let rung: Int?
    let estimateKbps: Int?
    let belowFloor: Bool
}

/// What autoplay does at an episode's credits marker, and whether it goes on at the end.
struct AutoplayWire: Encodable {
    /// `offer`, `countdown` or `playNow`.
    let atCredits: String
    /// The countdown's length, for `countdown`.
    let seconds: Int?
    let playsNextAtEnd: Bool

    init(_ rule: AutoplayNext, creditsWatched: Bool) {
        switch rule.atCredits {
        case .offer:
            atCredits = "offer"
            seconds = nil
        case .countdown(let length):
            atCredits = "countdown"
            seconds = length
        case .playNow:
            atCredits = "playNow"
            seconds = nil
        }
        playsNextAtEnd = rule.playsNextAtEnd(creditsWatched: creditsWatched)
    }
}

/// `SkipCheck`'s verdict on where playback is.
struct SkipVerdictWire: Encodable {
    /// `waiting`, `landed`, `retry` or `missed`; `none` when no skip is being confirmed.
    let verdict: String
    /// The skip has been tried once more already.
    let retried: Bool

    init(none: Void) {
        verdict = "none"
        retried = false
    }

    init(_ verdict: SkipCheck.Verdict, retried: Bool) {
        switch verdict {
        case .waiting: self.verdict = "waiting"
        case .landed: self.verdict = "landed"
        case .retry: self.verdict = "retry"
        case .missed: self.verdict = "missed"
        }
        self.retried = retried
    }
}

/// What a tap does: skip, wait for a second tap, or show the controls.
struct TapWire: Encodable {
    /// `skip`, `single` or `controls`.
    let action: String
    /// For `skip`: `back` or `forward`.
    let side: String?
    /// For `single`: the seconds to wait for a second tap before the controls show or hide.
    let window: Double?

    init(_ action: SkipTaps.Action) {
        switch action {
        case .skip(let side):
            self.action = "skip"
            self.side = side == .back ? "back" : "forward"
            window = nil
        case .single:
            self.action = "single"
            side = nil
            window = SkipTaps.doubleTapWindow
        case .controls:
            self.action = "controls"
            side = nil
            window = nil
        }
    }
}

enum PlayError: Error, CustomStringConvertible {
    case noFile
    case noSession
    case badState(String)

    var description: String {
        switch self {
        case .noFile: "This item has no file to play."
        case .noSession: "The player session has ended."
        case .badState(let state): "Unknown playback state \(state)."
        }
    }
}
