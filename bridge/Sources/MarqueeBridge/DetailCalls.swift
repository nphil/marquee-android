import Foundation
import MarqueeCore

/// The calls behind the detail page: one item and what hangs off it, watched state, deletion, and the
/// account watchlist. Names match exactly, so a sibling namespace such as `watchlist.items` stays with the
/// file that owns it.
enum DetailCalls {
    private static let names: Set<String> = [
        "detail.item", "detail.children", "detail.extras", "detail.related", "detail.person", "detail.matching",
        "detail.setWatched", "detail.serverInfo", "detail.delete", "detail.deleteVersion", "detail.mediaURL",
        "watchlist.contains", "watchlist.add", "watchlist.remove",
    ]

    static func handles(_ method: String) -> Bool {
        names.contains(method)
    }

    static func handle(_ method: String, _ payload: Data, on methods: Methods) async throws -> Data {
        switch method {
        case "detail.item":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            var item = try await library.metadata(decode(ItemCall.self, payload).ratingKey)
            // A show watched through gets the episode after its latest view up next, as Continue Watching does.
            if item.type == .show { item = await library.offeringRewatch(of: item) }
            return try Reply.ok(ItemWire(item))
        case "detail.children":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            return try Reply.ok(try await library.children(of: decode(ItemCall.self, payload).ratingKey).map(ItemWire.init))
        case "detail.extras":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            return try Reply.ok(try await library.extras(of: decode(ItemCall.self, payload).ratingKey).map(ItemWire.init))
        case "detail.related":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            return try Reply.ok(try await library.related(to: decode(ItemCall.self, payload).ratingKey).map(HubWire.init))
        case "detail.person":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            return try Reply.ok(try await library.titles(ofPerson: decode(PersonCall.self, payload).personID).map(ItemWire.init))
        case "detail.matching":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            return try Reply.ok(try await library.items(matchingGuid: decode(GuidCall.self, payload).guid).map(ItemWire.init))
        case "detail.setWatched":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            let call = try decode(WatchedCall.self, payload)
            if call.watched {
                try await library.markWatched(call.ratingKey)
            } else {
                try await library.markUnwatched(call.ratingKey)
            }
            return try Reply.ok(Empty())
        case "detail.serverInfo":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            return try Reply.ok(ServerInfoWire(try await library.serverInfo()))
        case "detail.delete":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            let call = try decode(DeleteCall.self, payload)
            let watchlist = try call.token.map(methods.watchlist(token:))
            var deleted = DeletedTitles()
            for key in call.alreadyDeleted { deleted.insert(key) }
            let result = await MediaDeletion(server: library, watchlist: watchlist).delete(call.item, alreadyDeleted: deleted)
            return try Reply.ok(DeletionWire(result))
        case "detail.deleteVersion":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            let call = try decode(VersionCall.self, payload)
            return try Reply.ok(DeletionWire(outcome: await library.deleteVersion(call.mediaID, of: call.ratingKey)))
        case "detail.mediaURL":
            let library = try methods.library(for: decode(ServerCall.self, payload))
            let path = try decode(PathCall.self, payload).path
            let base = try await library.connection.connection().uri
            guard let url = PlexURL.make(base: base, path: path) else { throw PlexError.decoding("bad path") }
            return try Reply.ok(MediaURLWire(url: url.absoluteString, headers: library.authorizationHeaders()))
        case "watchlist.contains":
            let call = try decode(WatchlistCall.self, payload)
            return try Reply.ok(Flag(value: try await methods.watchlist(token: call.token).contains(guid: call.guid)))
        case "watchlist.add":
            let call = try decode(WatchlistCall.self, payload)
            try await methods.watchlist(token: call.token).add(guid: call.guid)
            return try Reply.ok(Empty())
        case "watchlist.remove":
            let call = try decode(WatchlistCall.self, payload)
            try await methods.watchlist(token: call.token).remove(guid: call.guid)
            return try Reply.ok(Empty())
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }
}

private struct ItemCall: Decodable {
    let ratingKey: String
}

private struct PersonCall: Decodable {
    /// `PlexRole.id`, the person's tag.
    let personID: String
}

private struct GuidCall: Decodable {
    let guid: String
}

private struct PathCall: Decodable {
    let path: String
}

private struct WatchedCall: Decodable {
    let ratingKey: String
    let watched: Bool
}

private struct VersionCall: Decodable {
    let ratingKey: String
    let mediaID: Int
}

private struct WatchlistCall: Decodable {
    /// The account token: the watchlist belongs to the person, not to a server.
    let token: String
    let guid: String
}

/// A delete. `item` carries the identity on screen in Plex's own key names, which is what the Watchlist
/// cleanup falls back on when the server cannot give one; `alreadyDeleted` is this session's earlier
/// deletes, because the server lists those episodes until its trash is emptied.
private struct DeleteCall: Decodable {
    let item: PlexMetadata
    let alreadyDeleted: [String]
    /// Nil when no account is signed in to a watchlist.
    let token: String?
}

private struct Flag: Encodable {
    let value: Bool
}

private struct ServerInfoWire: Encodable {
    let allowsMediaDeletion: Bool
    let hasPlexPass: Bool

    init(_ info: PlexServerInfo) {
        allowsMediaDeletion = info.allowsMediaDeletion
        hasPlexPass = info.hasPlexPass
    }
}

/// How a delete ended: `outcome` is `deleted`, `notAllowed` or `failed`; `watchlist` is `unchanged`,
/// `removed` or `failed`, with the catalogue `guid` for the last two.
private struct DeletionWire: Encodable {
    let outcome: String
    let watchlist: String
    let guid: String?

    init(_ result: MediaDeletionResult) {
        outcome = Self.name(of: result.outcome)
        switch result.watchlist {
        case .unchanged:
            watchlist = "unchanged"
            guid = nil
        case .removed(let removed):
            watchlist = "removed"
            guid = removed
        case .failed(let failed):
            watchlist = "failed"
            guid = failed
        }
    }

    init(outcome: DeleteOutcome) {
        self.init(MediaDeletionResult(outcome: outcome))
    }

    private static func name(of outcome: DeleteOutcome) -> String {
        switch outcome {
        case .deleted: "deleted"
        case .notAllowed: "notAllowed"
        case .failed: "failed"
        }
    }
}

/// A file the player fetches itself: its address without the token, and the headers that carry the token
/// and identify the app.
private struct MediaURLWire: Encodable {
    let url: String
    let headers: [String: String]
}
