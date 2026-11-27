import Foundation
import MarqueeCore

/// The separate Discover feed supplies membership, not the order of the user's Watchlist.
func releasedWatchlist(_ watchlist: PlexWatchlist) async throws -> Set<String> {
    struct Envelope: Decodable {
        struct Page: Decodable {
            struct Entry: Decodable { let guid: String? }
            let Metadata: [Entry]?
            let totalSize: Int?
            let offset: Int?
        }
        let MediaContainer: Page
    }
    let size = 100
    var start = 0
    var guids = Set<String>()
    while true {
        try Task.checkCancellation()
        let url = PlexURL.make(base: PlexWatchlist.base, path: "/library/sections/watchlist/released", query: [
            URLQueryItem(name: "includeCollections", value: "1"), URLQueryItem(name: "includeExternalMedia", value: "1"),
            URLQueryItem(name: PlexHeader.containerStart, value: String(start)), URLQueryItem(name: PlexHeader.containerSize, value: String(size)),
        ])!
        let page = try await watchlist.http.decode(Envelope.self, from: PlexRequest(url: url)).MediaContainer
        guard page.offset == nil || page.offset == start else { throw PlexError.decoding("unexpected released Watchlist offset") }
        let entries = page.Metadata ?? []
        let previousCount = guids.count
        for entry in entries { if let guid = entry.guid, !guid.isEmpty { guids.insert(guid) } }
        start += entries.count
        if entries.count < size || page.totalSize.map({ start >= $0 }) == true { return guids }
        if page.offset == nil && page.totalSize == nil && guids.count == previousCount {
            throw PlexError.decoding("released Watchlist did not advance")
        }
    }
}
