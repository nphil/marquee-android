import Foundation
import MarqueeCore

// The shapes Kotlin reads and writes for the Seerr calls. Core's own types are not Codable, and the badge,
// season and request rules stay here, in Core, so the wire carries their answers rather than their inputs.

struct SeerrBadgeWire: Encodable {
    let name: String
    let title: String

    init(_ badge: SeerrBadge) {
        name = String(describing: badge)
        title = badge.title
    }
}

struct SeerrTitleWire: Encodable {
    let id: String
    let kind: String
    let tmdbID: Int
    let name: String
    let year: Int?
    let posterPath: String?
    let backdropPath: String?
    let overview: String
    let badge: SeerrBadgeWire?

    init(_ title: SeerrTitle) {
        id = title.id
        kind = title.kind.rawValue
        tmdbID = title.tmdbID
        name = title.name
        year = title.year
        posterPath = title.posterPath
        backdropPath = title.backdropPath
        overview = title.overview
        badge = title.badge.map(SeerrBadgeWire.init)
    }
}

struct SeerrPageWire: Encodable {
    let titles: [SeerrTitleWire]
    let page: Int
    let totalPages: Int

    init(_ page: SeerrTitlePage) {
        titles = page.titles.map(SeerrTitleWire.init)
        self.page = page.page
        totalPages = page.totalPages
    }
}

struct SeerrUserWire: Encodable {
    let id: Int
    let name: String

    init(_ user: SeerrUser) {
        id = user.id
        name = user.name
    }
}

struct SeerrSettingsWire: Encodable {
    let movie4KEnabled: Bool
    let series4KEnabled: Bool
    let partialRequestsEnabled: Bool
    let specialEpisodesEnabled: Bool

    init(_ settings: SeerrServerSettings) {
        movie4KEnabled = settings.movie4KEnabled
        series4KEnabled = settings.series4KEnabled
        partialRequestsEnabled = settings.partialRequestsEnabled
        specialEpisodesEnabled = settings.specialEpisodesEnabled
    }
}

struct SeerrGenreWire: Encodable {
    let id: Int
    let name: String
    let backdropPath: String?

    init(_ genre: SeerrGenre) {
        id = genre.id
        name = genre.name
        backdropPath = genre.backdropPath
    }
}

struct SeerrNamedWire: Encodable {
    let code: String
    let name: String
}

struct SeerrProviderWire: Encodable {
    let id: Int
    let name: String
}

struct SeerrSeasonWire: Encodable {
    let number: Int
    let name: String
    let episodeCount: Int
    /// Why the season cannot be requested now; nil when it can.
    let badge: SeerrBadgeWire?
}

/// A request on My Requests, with its title.
struct SeerrRequestWire: Encodable {
    let id: Int
    let kind: String
    let tmdbID: Int?
    let is4K: Bool
    let seasons: [Int]
    let requesterName: String?
    /// Seconds since 1970.
    let createdAt: Double?
    let badge: SeerrBadgeWire?
    let title: SeerrTitleWire?

    init(_ item: SeerrRequestItem) {
        id = item.request.id
        kind = item.request.kind.rawValue
        tmdbID = item.request.tmdbID
        is4K = item.request.is4K
        seasons = item.request.seasons
        requesterName = item.request.requesterName
        createdAt = item.request.createdAt?.timeIntervalSince1970
        badge = item.request.badge.map(SeerrBadgeWire.init)
        title = item.title.map(SeerrTitleWire.init)
    }
}

/// What the title page says about one quality: the badge, and who asked while a request is open.
struct SeerrStatusWire: Encodable {
    let badge: SeerrBadgeWire
    let requestedBy: String?
}

/// The Plex item a title is, when Seerr links one on the signed-in server.
struct SeerrPlexLinkWire: Encodable {
    let ratingKey: String
}

/// A title page: Seerr's details with the account's rules applied, so Kotlin shows them as they are.
struct SeerrTitlePageWire: Encodable {
    let title: SeerrTitleWire
    let tagline: String?
    let genres: [String]
    let runtime: Int?
    let seasons: [SeerrSeasonWire]
    let status: SeerrStatusWire?
    let status4K: SeerrStatusWire?
    let canRequest: Bool
    let canRequest4K: Bool
    let seasonChoices: [SeerrSeasonWire]
    let seasonChoices4K: [SeerrSeasonWire]
    let partialRequests: Bool
    let watchlistAddRequests: Bool
    let plexItem: SeerrPlexLinkWire?

    init(_ details: SeerrDetails, user: SeerrUser, settings: SeerrServerSettings, pulsarr: Bool, serverID: String?) {
        let media = details.title.media
        title = SeerrTitleWire(details.title)
        tagline = details.tagline
        genres = details.genres
        runtime = details.runtime
        seasons = details.seasons.map { SeerrSeasonWire(number: $0.number, name: $0.name, episodeCount: $0.episodeCount, badge: nil) }
        status = Self.status(media, is4K: false)
        status4K = Self.status(media, is4K: true)
        canRequest = details.canRequest(is4K: false, user: user, settings: settings)
        canRequest4K = details.canRequest(is4K: true, user: user, settings: settings)
        seasonChoices = Self.choices(details, is4K: false, settings: settings)
        seasonChoices4K = Self.choices(details, is4K: true, settings: settings)
        partialRequests = settings.partialRequestsEnabled
        watchlistAddRequests = details.watchlistAddRequests(user: user, pulsarr: pulsarr)
        if case .item(let key)? = media?.plexLink(serverID: serverID) {
            plexItem = SeerrPlexLinkWire(ratingKey: key)
        } else {
            plexItem = nil
        }
    }

    private static func status(_ media: SeerrMedia?, is4K: Bool) -> SeerrStatusWire? {
        guard let media, let badge = media.badge(is4K: is4K) else { return nil }
        return SeerrStatusWire(badge: SeerrBadgeWire(badge), requestedBy: media.openRequest(is4K: is4K)?.requesterName)
    }

    private static func choices(_ details: SeerrDetails, is4K: Bool, settings: SeerrServerSettings) -> [SeerrSeasonWire] {
        details.seasonChoices(is4K: is4K, settings: settings).map {
            SeerrSeasonWire(
                number: $0.season.number, name: $0.season.name, episodeCount: $0.season.episodeCount,
                badge: $0.badge.map(SeerrBadgeWire.init)
            )
        }
    }
}

// MARK: Browsing

struct SeerrStreamingWire: Codable {
    let provider: Int
    let region: String
}

/// A browse query, both ways: Kotlin keeps it as the screen's state and sends it back for each page.
struct SeerrQueryWire: Codable {
    var kind: String
    var genre: Int?
    var sort: String
    var reversed: Bool
    var upcoming: Bool
    var era: String?
    var language: String?
    var minRating: Int?
    var runtime: String?
    var streaming: SeerrStreamingWire?

    init(_ query: SeerrBrowseQuery) {
        kind = query.kind.rawValue
        genre = query.genre
        sort = query.sort.rawValue
        reversed = query.reversed
        upcoming = query.upcoming
        era = query.era.map { String(describing: $0) }
        language = query.language
        minRating = query.minRating
        runtime = query.runtime.map { String(describing: $0) }
        streaming = query.streaming.map { SeerrStreamingWire(provider: $0.provider, region: $0.region) }
    }

    /// Nil when a name is not one of Core's, which only a newer Kotlin could send.
    var query: SeerrBrowseQuery? {
        guard let kind = SeerrMediaKind(rawValue: kind), let sort = SeerrSort(rawValue: sort) else { return nil }
        let era = era.flatMap { name in SeerrEra.allCases.first { String(describing: $0) == name } }
        let runtime = runtime.flatMap { name in SeerrRuntime.allCases.first { String(describing: $0) == name } }
        return SeerrBrowseQuery(
            kind: kind, genre: genre, sort: sort, reversed: reversed, upcoming: upcoming, era: era, language: language,
            minRating: minRating, runtime: runtime, streaming: streaming.map { SeerrStreaming(provider: $0.provider, region: $0.region) }
        )
    }
}

/// A list of titles to page through: the trending feed, or a browse query.
struct SeerrFeedWire: Codable {
    var trending: Bool?
    var query: SeerrQueryWire?

    init(_ feed: SeerrFeed) {
        switch feed {
        case .trending:
            trending = true
        case .discover(let query):
            self.query = SeerrQueryWire(query)
        }
    }

    var feed: SeerrFeed? {
        if trending == true { return .trending }
        return query?.query.map(SeerrFeed.discover)
    }
}

/// What Core fixes about browsing and sends without asking Seerr: the Discover rows in order, and the names
/// of the sorts, decades and runtimes.
struct SeerrCatalogueWire: Encodable {
    struct Row: Encodable {
        let id: String
        let title: String
        /// The shelf a row loads, or nil for a row of genre cards.
        let shelf: String?
        /// The kind a row of genre cards is for.
        let genres: String?
        /// What its chevron opens.
        let feed: SeerrFeedWire
    }

    struct Sort: Encodable {
        let id: String
        let title: String
        let first: String
        let reversed: String
    }

    struct Era: Encodable {
        let id: String
        let title: String
    }

    struct Runtime: Encodable {
        let id: String
        let movie: String
        let tv: String
    }

    let rows: [Row]
    let sorts: [Sort]
    let eras: [Era]
    let runtimes: [Runtime]

    init() {
        rows = SeerrDiscoverRow.all.map { row in
            switch row {
            case .shelf(let shelf):
                Row(id: row.id, title: row.title, shelf: shelf.rawValue, genres: nil, feed: SeerrFeedWire(shelf.feed))
            case .genres(let kind):
                Row(id: row.id, title: row.title, shelf: nil, genres: kind.rawValue, feed: SeerrFeedWire(.discover(SeerrBrowseQuery(kind: kind))))
            }
        }
        sorts = SeerrSort.allCases.map { Sort(id: $0.rawValue, title: $0.title, first: $0.orders.first, reversed: $0.orders.reversed) }
        eras = SeerrEra.allCases.map { Era(id: String(describing: $0), title: $0.title) }
        runtimes = SeerrRuntime.allCases.map {
            Runtime(id: String(describing: $0), movie: $0.title(for: .movie), tv: $0.title(for: .tv))
        }
    }
}
