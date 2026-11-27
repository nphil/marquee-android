import Foundation
import MarqueeCore
import Testing
@testable import MarqueeBridge

@Suite struct ReleasedWatchlistTests {
    private struct Feed: PlexTransport {
        let lastPageFails: Bool

        func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
            let query = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let start = query.first { $0.name == PlexHeader.containerStart }?.value
            guard request.method == .get, request.url.host == "discover.provider.plex.tv",
                  request.url.path == "/library/sections/watchlist/released", !query.contains(where: { $0.name == "sort" }),
                  query.contains(URLQueryItem(name: PlexHeader.containerSize, value: "100")),
                  start == "0" || start == "100" else { return PlexHTTPResponse(status: 400, body: Data()) }
            if start == "100", lastPageFails { return PlexHTTPResponse(status: 503, body: Data()) }
            let ids = start == "0" ? Array(repeating: ["guid": "plex://movie/first"], count: 99) + [[:]] : [["guid": "plex://movie/last"]]
            let body = try JSONSerialization.data(withJSONObject: ["MediaContainer": ["Metadata": ids, "totalSize": 101]])
            return PlexHTTPResponse(status: 200, body: body)
        }
    }

    private func list(lastPageFails: Bool) -> PlexWatchlist {
        let identity = PlexClientIdentity(clientIdentifier: "fixture", version: "1", platformVersion: "14", device: "TV", deviceName: "TV")
        return PlexWatchlist(http: PlexHTTPClient(identity: identity, transport: Feed(lastPageFails: lastPageFails)))
    }

    @Test func pagesByRawEntriesAndDeduplicatesCatalogueGuids() async throws {
        let ids = try await releasedWatchlist(list(lastPageFails: false))
        #expect(ids == ["plex://movie/first", "plex://movie/last"])
    }

    @Test func failedLaterPageDoesNotReturnPartialMembership() async {
        await #expect(throws: PlexError.self) { try await releasedWatchlist(list(lastPageFails: true)) }
    }
}
