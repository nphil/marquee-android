import Foundation
import MarqueeCore

// The JSON shapes of the browse calls in `BrowseCalls`. Kotlin mirrors them in BrowseModels.kt. Items and hubs
// travel in the item model's own form (ItemWire.swift).

/// Where one server's snapshots are kept and whose they are: one folder per server and profile.
struct SnapshotSpec: Decodable {
    /// A folder in the app's cache directory.
    let directory: String
    let server: String
    /// The profile's uuid; empty for the account itself.
    let profile: String

    var scope: SnapshotScope {
        SnapshotScope(server: server, profile: profile)
    }
}

/// Whether a read asks the server and records the answer, or answers from the last recording alone.
enum SnapshotMode: String, Decodable {
    case live, saved
}

struct HomeLoad: Decodable {
    /// The libraries with a shown row (`HomeLayout.shownLibraries`, by `HomeLayout.libraryID`); nil asks every one.
    let libraries: [String]?
    /// Libraries switched off under Favorite Libraries.
    let hidden: [String]
    let dismissed: RewatchDismissals?
    let mode: SnapshotMode
    let snapshot: SnapshotSpec?
    /// Rows are on screen already, so Home may wait longer for the shows watched again.
    let showing: Bool?
}

struct HomeFollow: Decodable {
    let run: String
    let dismissed: RewatchDismissals?
}

/// A Continue Watching card made for a show watched again, as far as taking it off the row needs.
struct RewatchCard: Decodable {
    let ratingKey: String
    let grandparentRatingKey: String?
    let grandparentGuid: String?
    /// Seconds since 1970.
    let rewatchedAt: Double

    var metadata: PlexMetadata? {
        var object = ["ratingKey": ratingKey, "type": "episode"]
        object["grandparentRatingKey"] = grandparentRatingKey
        object["grandparentGuid"] = grandparentGuid
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              var card = try? JSONDecoder().decode(PlexMetadata.self, from: data) else { return nil }
        card.rewatchedAt = Date(timeIntervalSince1970: rewatchedAt)
        return card
    }
}

struct RewatchChange: Decodable {
    let dismissed: RewatchDismissals?
    let card: RewatchCard
}

/// One Home row. `shown` is not sent: Kotlin applies the profile's layout, whose default is shown for a Home row and
/// hidden for a library's.
struct HomeRowWire: Encodable {
    struct Library: Encodable {
        /// `HomeLayout.libraryID`: the library's uuid, else its key.
        let id: String
        let key: String
        let title: String
    }

    let id: String
    let title: String
    let library: Library?
    let continueWatching: Bool
    let hub: HubWire

    init(_ row: HomeRow, itemLimit: Int? = nil) {
        id = row.id
        title = row.displayTitle
        library = row.library.map { Library(id: HomeLayout.libraryID($0), key: $0.key, title: $0.title) }
        continueWatching = row.isContinueWatching
        var hub = row.hub
        if let itemLimit { hub.items = Array(hub.items.prefix(itemLimit)) }
        self.hub = HubWire(hub)
    }
}

struct HomeReply: Encodable {
    var rows: [HomeRowWire]
    /// The shows watched again are still being read; `home.follow` with `run` waits for them.
    var late = false
    var run: String?
    /// When the saved copy was recorded, in seconds since 1970; nil when nothing was saved.
    var savedAt: Double?
}

struct FollowReply: Encodable {
    /// Nil when a newer load replaced the run.
    var rows: [HomeRowWire]?
}

/// What a library grid lists, in the iPhone app's own terms (`SectionQuery`).
struct QueryWire: Decodable {
    let type: PlexMediaType
    /// A `SectionQuery.Sort` raw value: `titleSort`, `addedAt`, `originallyAvailableAt`, ...
    let sort: String
    let reversed: Bool?
    let unwatchedOnly: Bool?
    /// An entry of `library.genres`.
    let genre: PlexDirectory?

    var sectionQuery: SectionQuery {
        SectionQuery(
            type: type, sort: SectionQuery.Sort(rawValue: sort) ?? .title,
            reversed: reversed ?? false, unwatchedOnly: unwatchedOnly ?? false, genre: genre
        )
    }
}

struct SectionRef: Decodable {
    let section: String
}

struct ItemsCall: Decodable {
    let section: String
    let query: QueryWire
    let start: Int
    let size: Int?
}

struct AlphabetCall: Decodable {
    let section: String
    let query: QueryWire
}

struct PagedSectionCall: Decodable {
    let section: String
    let start: Int
    let size: Int?
}

struct ListCall: Decodable {
    /// A key the server handed out.
    let key: String
    let start: Int
    let size: Int?
}

struct RefetchCall: Decodable {
    let ratingKeys: [String]
}

struct SearchCall: Decodable {
    let query: String
    let limit: Int?
}

struct WatchlistPageCall: Decodable {
    let token: String
    let start: Int
    let size: Int?
    let snapshot: SnapshotSpec?
    let mode: SnapshotMode?
}

struct SnapshotRemoval: Decodable {
    let directory: String
    /// Only this profile's snapshots; every one when nil.
    let profile: String?
}

/// One page of a list, with the size of the whole.
struct PageWire: Encodable {
    let items: [ItemWire]
    let total: Int

    init(_ page: PlexPage<PlexMetadata>) {
        items = page.items.map(ItemWire.init)
        total = page.totalSize
    }
}

struct LetterWire: Encodable {
    let character: String
    let count: Int
    let offset: Int

    init(_ entry: AlphabetIndexEntry) {
        character = entry.character
        count = entry.count
        offset = entry.offset
    }
}
