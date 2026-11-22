import Foundation
import MarqueeCore

// The JSON Kotlin reads for Plex items (`PlexItem`, `PlexHub` and their parts in MediaModels.kt). Field names are
// PlexMetadata's own. Nil values, empty lists, empty text and zero counts are left out, so a poster in a grid
// costs little JSON and Kotlin gives each its default. Dates travel as whole seconds since 1970. What Core works
// out about an item (watched, episodes left, its show) is sent as Core worked it out.

/// A movie, show, season, episode, collection or extra.
struct ItemWire: Encodable {
    let item: PlexMetadata

    init(_ item: PlexMetadata) {
        self.item = item
    }

    private enum Key: String, CodingKey {
        case ratingKey, key, guid, type, subtype, extraType, primaryExtraKey, title, titleSort, originalTitle, summary, tagline, year
        case index, parentIndex, parentRatingKey, parentGuid, parentTitle
        case grandparentRatingKey, grandparentGuid, grandparentTitle
        case thumb, art, parentThumb, grandparentThumb, grandparentArt, theme, parentTheme
        case duration, viewOffset, viewCount, leafCount, viewedLeafCount, childCount
        case addedAt, lastViewedAt, originallyAvailableAt, contentRating, studio, rating, audienceRating
        case librarySectionID, librarySectionTitle, playQueueItemID
        case media, roles, genres, directors, writers, images, onDeck, rewatchedAt
        case isWatched, unwatchedLeafCount, showRatingKey
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(item.ratingKey, forKey: .ratingKey)
        try c.encode(item.key, forKey: .key)
        try c.encodeIfPresent(item.guid, forKey: .guid)
        try c.encode(item.type, forKey: .type)
        try c.encodeIfPresent(item.subtype, forKey: .subtype)
        try c.encodeIfPresent(item.extraType, forKey: .extraType)
        try c.encodeIfPresent(item.primaryExtraKey, forKey: .primaryExtraKey)
        try c.encode(item.title, forKey: .title)
        try c.encodeIfPresent(item.titleSort, forKey: .titleSort)
        try c.encodeIfPresent(item.originalTitle, forKey: .originalTitle)
        try c.encodeNonEmpty(item.summary, forKey: .summary)
        try c.encodeIfPresent(item.tagline, forKey: .tagline)
        try c.encodeIfPresent(item.year, forKey: .year)
        try c.encodeIfPresent(item.index, forKey: .index)
        try c.encodeIfPresent(item.parentIndex, forKey: .parentIndex)
        try c.encodeIfPresent(item.parentRatingKey, forKey: .parentRatingKey)
        try c.encodeIfPresent(item.parentGuid, forKey: .parentGuid)
        try c.encodeIfPresent(item.parentTitle, forKey: .parentTitle)
        try c.encodeIfPresent(item.grandparentRatingKey, forKey: .grandparentRatingKey)
        try c.encodeIfPresent(item.grandparentGuid, forKey: .grandparentGuid)
        try c.encodeIfPresent(item.grandparentTitle, forKey: .grandparentTitle)
        try c.encodeIfPresent(item.thumb, forKey: .thumb)
        try c.encodeIfPresent(item.art, forKey: .art)
        try c.encodeIfPresent(item.parentThumb, forKey: .parentThumb)
        try c.encodeIfPresent(item.grandparentThumb, forKey: .grandparentThumb)
        try c.encodeIfPresent(item.grandparentArt, forKey: .grandparentArt)
        try c.encodeIfPresent(item.theme, forKey: .theme)
        try c.encodeIfPresent(item.parentTheme, forKey: .parentTheme)
        try c.encodeNonZero(item.duration, forKey: .duration)
        try c.encodeNonZero(item.viewOffset, forKey: .viewOffset)
        try c.encodeNonZero(item.viewCount, forKey: .viewCount)
        try c.encodeIfPresent(item.leafCount, forKey: .leafCount)
        try c.encodeIfPresent(item.viewedLeafCount, forKey: .viewedLeafCount)
        try c.encodeIfPresent(item.childCount, forKey: .childCount)
        try c.encodeSeconds(item.addedAt, forKey: .addedAt)
        try c.encodeSeconds(item.lastViewedAt, forKey: .lastViewedAt)
        try c.encodeIfPresent(item.originallyAvailableAt, forKey: .originallyAvailableAt)
        try c.encodeIfPresent(item.contentRating, forKey: .contentRating)
        try c.encodeIfPresent(item.studio, forKey: .studio)
        try c.encodeIfPresent(item.rating, forKey: .rating)
        try c.encodeIfPresent(item.audienceRating, forKey: .audienceRating)
        try c.encodeIfPresent(item.librarySectionID, forKey: .librarySectionID)
        try c.encodeIfPresent(item.librarySectionTitle, forKey: .librarySectionTitle)
        try c.encodeIfPresent(item.playQueueItemID, forKey: .playQueueItemID)
        try c.encodeNonEmpty(item.media.map(MediaWire.init), forKey: .media)
        try c.encodeNonEmpty(item.roles.map(RoleWire.init), forKey: .roles)
        try c.encodeNonEmpty(item.genres, forKey: .genres)
        try c.encodeNonEmpty(item.directors, forKey: .directors)
        try c.encodeNonEmpty(item.writers, forKey: .writers)
        try c.encodeNonEmpty(item.images.map(ImageWire.init), forKey: .images)
        try c.encodeIfPresent(item.onDeck.map(ItemWire.init), forKey: .onDeck)
        try c.encodeSeconds(item.rewatchedAt, forKey: .rewatchedAt)
        try c.encodeTrue(item.isWatched, forKey: .isWatched)
        try c.encodeIfPresent(item.unwatchedLeafCount, forKey: .unwatchedLeafCount)
        try c.encodeIfPresent(item.showRatingKey, forKey: .showRatingKey)
    }
}

/// A row of items: a hub of Home, a search group, related titles.
struct HubWire: Encodable {
    let hub: PlexHub

    init(_ hub: PlexHub) {
        self.hub = hub
    }

    private enum Key: String, CodingKey {
        case identifier, key, title, type, style, size, more, items, directories
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(hub.identifier, forKey: .identifier)
        try c.encodeIfPresent(hub.key, forKey: .key)
        try c.encode(hub.title, forKey: .title)
        try c.encodeIfPresent(hub.type, forKey: .type)
        try c.encodeIfPresent(hub.style, forKey: .style)
        try c.encode(hub.size, forKey: .size)
        try c.encodeTrue(hub.more, forKey: .more)
        try c.encodeNonEmpty(hub.items.map(ItemWire.init), forKey: .items)
        try c.encodeNonEmpty(hub.directories.map(DirectoryWire.init), forKey: .directories)
    }
}

/// A person, tag or library entry in a hub, such as a search group of people.
struct DirectoryWire: Encodable {
    let directory: PlexDirectory

    init(_ directory: PlexDirectory) {
        self.directory = directory
    }

    private enum Key: String, CodingKey {
        case key, title, tag, id, type, size, thumb, art
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(directory.key, forKey: .key)
        try c.encode(directory.title, forKey: .title)
        try c.encodeIfPresent(directory.tag, forKey: .tag)
        try c.encodeIfPresent(directory.id, forKey: .id)
        try c.encodeIfPresent(directory.type, forKey: .type)
        try c.encode(directory.size, forKey: .size)
        try c.encodeIfPresent(directory.thumb, forKey: .thumb)
        try c.encodeIfPresent(directory.art, forKey: .art)
    }
}

/// One version of an item: a file with its container and codecs.
struct MediaWire: Encodable {
    let media: PlexMedia

    init(_ media: PlexMedia) {
        self.media = media
    }

    private enum Key: String, CodingKey {
        case id, duration, bitrate, width, height, aspectRatio, audioChannels, audioCodec, videoCodec
        case videoResolution, container, videoFrameRate, videoProfile, origin, mediaGrabStatus, parts
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(media.id, forKey: .id)
        try c.encodeNonZero(media.duration, forKey: .duration)
        try c.encodeIfPresent(media.bitrate, forKey: .bitrate)
        try c.encodeIfPresent(media.width, forKey: .width)
        try c.encodeIfPresent(media.height, forKey: .height)
        try c.encodeIfPresent(media.aspectRatio, forKey: .aspectRatio)
        try c.encodeIfPresent(media.audioChannels, forKey: .audioChannels)
        try c.encodeIfPresent(media.audioCodec, forKey: .audioCodec)
        try c.encodeIfPresent(media.videoCodec, forKey: .videoCodec)
        try c.encodeIfPresent(media.videoResolution, forKey: .videoResolution)
        try c.encodeIfPresent(media.container, forKey: .container)
        try c.encodeIfPresent(media.videoFrameRate, forKey: .videoFrameRate)
        try c.encodeIfPresent(media.videoProfile, forKey: .videoProfile)
        try c.encodeIfPresent(media.origin, forKey: .origin)
        try c.encodeIfPresent(media.mediaGrabStatus, forKey: .mediaGrabStatus)
        try c.encodeNonEmpty(media.parts.map(PartWire.init), forKey: .parts)
    }
}

/// One file of a version, with the audio and subtitle menu Core builds for it.
struct PartWire: Encodable {
    let part: PlexPart

    init(_ part: PlexPart) {
        self.part = part
    }

    private enum Key: String, CodingKey {
        case id, key, duration, file, size, container, streams, tracks, hasSceneThumbnails
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(part.id, forKey: .id)
        try c.encode(part.key, forKey: .key)
        try c.encodeNonZero(part.duration, forKey: .duration)
        try c.encodeIfPresent(part.file, forKey: .file)
        try c.encodeIfPresent(part.size, forKey: .size)
        try c.encodeIfPresent(part.container, forKey: .container)
        try c.encodeNonEmpty(part.streams.map(StreamWire.init), forKey: .streams)
        try c.encodeIfPresent(TracksWire(MediaTracks(part: part)), forKey: .tracks)
        try c.encodeTrue(part.hasSceneThumbnails, forKey: .hasSceneThumbnails)
    }
}

/// The tracks a menu offers for one file, named by Core's labeller so Kotlin carries no language tables.
/// Subtitles are the visible ones: a separate file hides its embedded twin.
struct TracksWire: Encodable {
    struct Audio: Encodable {
        let id: Int
        let label: String
        let codec: String
        let channels: Int?
        let title: String?
        let isDefault: Bool
        let badges: [String]
    }

    struct Subtitle: Encodable {
        let id: Int
        let label: String
        let codec: String
        let isExternal: Bool
        let imageFormat: String?
        let badges: [String]
    }

    let audio: [Audio]
    let subtitles: [Subtitle]

    /// Nil for a file with neither, which leaves the key out.
    init?(_ tracks: MediaTracks) {
        audio = tracks.audio.map {
            Audio(
                id: $0.id, label: $0.label, codec: $0.codec, channels: $0.channels, title: $0.title,
                isDefault: $0.isDefault, badges: $0.badges.map(\.rawValue)
            )
        }
        subtitles = tracks.visibleSubtitles.map {
            Subtitle(
                id: $0.id, label: $0.label, codec: $0.codec, isExternal: $0.isExternal,
                imageFormat: $0.imageFormat, badges: $0.badges.map(\.rawValue)
            )
        }
        if audio.isEmpty && subtitles.isEmpty { return nil }
    }
}

/// A video, audio or subtitle stream. `streamType` is Plex's number: 1 video, 2 audio, 3 subtitle.
struct StreamWire: Encodable {
    let stream: PlexStream

    init(_ stream: PlexStream) {
        self.stream = stream
    }

    private enum Key: String, CodingKey {
        case id, streamType, index, key, codec, format, language, languageCode, languageTag
        case title, displayTitle, extendedDisplayTitle
        case isDefault, isSelected, isForced, isHearingImpaired, isVisualImpaired, isEmbeddedInVideo, canNormalizeLoudness
        case channels, audioChannelLayout, samplingRate, bitrate, profile, level, bitDepth, chromaSubsampling
        case colorPrimaries, colorTrc, colorSpace, frameRate, width, height, dolbyVision
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(stream.id, forKey: .id)
        try c.encode(stream.kind.rawValue, forKey: .streamType)
        try c.encodeIfPresent(stream.index, forKey: .index)
        try c.encodeIfPresent(stream.key, forKey: .key)
        try c.encodeIfPresent(stream.codec, forKey: .codec)
        try c.encodeIfPresent(stream.format, forKey: .format)
        try c.encodeIfPresent(stream.language, forKey: .language)
        try c.encodeIfPresent(stream.languageCode, forKey: .languageCode)
        try c.encodeIfPresent(stream.languageTag, forKey: .languageTag)
        try c.encodeIfPresent(stream.title, forKey: .title)
        try c.encodeIfPresent(stream.displayTitle, forKey: .displayTitle)
        try c.encodeIfPresent(stream.extendedDisplayTitle, forKey: .extendedDisplayTitle)
        try c.encodeTrue(stream.isDefault, forKey: .isDefault)
        try c.encodeTrue(stream.isSelected, forKey: .isSelected)
        try c.encodeTrue(stream.isForced, forKey: .isForced)
        try c.encodeTrue(stream.isHearingImpaired, forKey: .isHearingImpaired)
        try c.encodeTrue(stream.isVisualImpaired, forKey: .isVisualImpaired)
        try c.encodeTrue(stream.isEmbeddedInVideo, forKey: .isEmbeddedInVideo)
        try c.encodeTrue(stream.canNormalizeLoudness, forKey: .canNormalizeLoudness)
        try c.encodeIfPresent(stream.channels, forKey: .channels)
        try c.encodeIfPresent(stream.audioChannelLayout, forKey: .audioChannelLayout)
        try c.encodeIfPresent(stream.samplingRate, forKey: .samplingRate)
        try c.encodeIfPresent(stream.bitrate, forKey: .bitrate)
        try c.encodeIfPresent(stream.profile, forKey: .profile)
        try c.encodeIfPresent(stream.level, forKey: .level)
        try c.encodeIfPresent(stream.bitDepth, forKey: .bitDepth)
        try c.encodeIfPresent(stream.chromaSubsampling, forKey: .chromaSubsampling)
        try c.encodeIfPresent(stream.colorPrimaries, forKey: .colorPrimaries)
        try c.encodeIfPresent(stream.colorTrc, forKey: .colorTrc)
        try c.encodeIfPresent(stream.colorSpace, forKey: .colorSpace)
        try c.encodeIfPresent(stream.frameRate, forKey: .frameRate)
        try c.encodeIfPresent(stream.width, forKey: .width)
        try c.encodeIfPresent(stream.height, forKey: .height)
        try c.encodeIfPresent(stream.dolbyVision.map(DolbyVisionWire.init), forKey: .dolbyVision)
    }
}

struct DolbyVisionWire: Encodable {
    let profile: Int
    let level: Int?
    let baseLayerCompatibility: Int?
    let hasEnhancementLayer: Bool

    init(_ vision: PlexStream.DolbyVision) {
        profile = vision.profile
        level = vision.level
        baseLayerCompatibility = vision.baseLayerCompatibility
        hasEnhancementLayer = vision.hasEnhancementLayer
    }
}

/// A cast member.
struct RoleWire: Encodable {
    let id: String?
    let name: String
    let character: String?
    let thumb: String?

    init(_ role: PlexRole) {
        id = role.id
        name = role.name
        character = role.character
        thumb = role.thumb
    }
}

/// Artwork beyond `thumb` and `art`: `coverPoster`, `background`, `snapshot`, `clearLogo`.
struct ImageWire: Encodable {
    let type: String
    let url: String

    init(_ image: PlexImage) {
        type = image.type
        url = image.url
    }
}

private extension KeyedEncodingContainer {
    mutating func encodeNonZero(_ value: Int, forKey key: Key) throws {
        if value != 0 { try encode(value, forKey: key) }
    }

    mutating func encodeNonEmpty(_ value: String, forKey key: Key) throws {
        if !value.isEmpty { try encode(value, forKey: key) }
    }

    mutating func encodeNonEmpty<Value: Encodable>(_ values: [Value], forKey key: Key) throws {
        if !values.isEmpty { try encode(values, forKey: key) }
    }

    mutating func encodeTrue(_ value: Bool, forKey key: Key) throws {
        if value { try encode(value, forKey: key) }
    }

    mutating func encodeSeconds(_ date: Date?, forKey key: Key) throws {
        if let date { try encode(Int(date.timeIntervalSince1970), forKey: key) }
    }
}
