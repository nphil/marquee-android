import Foundation
import MarqueeCore
import Synchronization

/// The calls behind Requests and the Seerr settings page. Every call names the connection it is for (the
/// address, the API key or the Plex token, and a kept sign-in cookie), so the bridge holds no setting of its
/// own. Each answer is `{value, session?}`: `session` is a cookie header that a Plex sign-in during the call
/// produced, for Kotlin to keep so the next launch need not sign in again.
enum SeerrCalls {
    private static let names: Set<String> = [
        "seerr.parse", "seerr.catalogue", "seerr.version", "seerr.signIn", "seerr.me", "seerr.serverSettings",
        "seerr.discover", "seerr.browse", "seerr.genres", "seerr.genreCards", "seerr.languages", "seerr.watchRegions",
        "seerr.watchProviders", "seerr.search", "seerr.details", "seerr.request", "seerr.requests",
    ]

    static func handles(_ method: String) -> Bool {
        names.contains(method)
    }

    static func handle(_ method: String, _ payload: Data, on methods: Methods) async throws -> Data {
        try await handle(method, payload, through: OkHttpTransport.shared, clients: .shared)
    }

    static func handle(_ method: String, _ payload: Data, through transport: any PlexTransport, clients: SeerrClients) async throws -> Data {
        switch method {
        case "seerr.parse":
            let call = try decode(ParseCall.self, payload)
            switch SeerrConfig.parse(address: call.address, apiKey: call.apiKey) {
            case .success(let config): return try Reply.ok(Parsed(problem: nil, address: config.address.absoluteString))
            case .failure(let problem): return try Reply.ok(Parsed(problem: String(describing: problem), address: nil))
            }
        case "seerr.catalogue":
            return try Reply.ok(SeerrCatalogueWire())
        default:
            break
        }

        let link = try decode(SeerrLink.self, payload)
        let slot = try clients.slot(for: link, transport: transport)
        let client = slot.client
        switch method {
        case "seerr.version":
            return try slot.reply(await client.version())
        case "seerr.signIn":
            return try slot.reply(SeerrUserWire(await client.signIn()))
        case "seerr.me":
            return try slot.reply(SeerrUserWire(await client.me()))
        case "seerr.serverSettings":
            return try slot.reply(SeerrSettingsWire(await client.serverSettings()))
        case "seerr.discover":
            let call = try decode(DiscoverCall.self, payload)
            guard let shelf = SeerrShelf(rawValue: call.shelf) else { throw BridgeError.unknownMethod("\(method) \(call.shelf)") }
            return try slot.reply(await client.discover(shelf, page: call.page ?? 1).map(SeerrTitleWire.init))
        case "seerr.browse":
            let call = try decode(BrowseCall.self, payload)
            guard let feed = call.feed.feed else { throw BridgeError.unknownMethod("\(method) feed") }
            return try slot.reply(SeerrPageWire(await client.browse(feed, page: call.page ?? 1)))
        case "seerr.genres":
            return try slot.reply(await client.genres(decode(KindCall.self, payload).kind).map(SeerrGenreWire.init))
        case "seerr.genreCards":
            return try slot.reply(await client.genreCards(decode(KindCall.self, payload).kind).map(SeerrGenreWire.init))
        case "seerr.languages":
            return try slot.reply(await client.languages().map { SeerrNamedWire(code: $0.code, name: $0.name) })
        case "seerr.watchRegions":
            return try slot.reply(await client.watchRegions().map { SeerrNamedWire(code: $0.code, name: $0.name) })
        case "seerr.watchProviders":
            let call = try decode(ProvidersCall.self, payload)
            return try slot.reply(await client.watchProviders(call.kind, region: call.region).map { SeerrProviderWire(id: $0.id, name: $0.name) })
        case "seerr.search":
            let call = try decode(SearchCall.self, payload)
            return try slot.reply(await client.search(call.text, page: call.page ?? 1).map(SeerrTitleWire.init))
        case "seerr.details":
            let call = try decode(DetailsCall.self, payload)
            async let details = client.details(call.kind, tmdbID: call.tmdbID)
            async let user = client.me()
            async let settings = client.serverSettings()
            let page = try await SeerrTitlePageWire(
                details, user: user, settings: settings, pulsarr: call.pulsarr ?? false, serverID: call.serverID
            )
            return try slot.reply(page)
        case "seerr.request":
            let call = try decode(RequestCall.self, payload)
            try await client.request(order(for: call, using: client))
            return try slot.reply(Empty())
        case "seerr.requests":
            let user = try await client.me()
            return try slot.reply(await client.requests(of: user, take: decode(RequestsCall.self, payload).take ?? 20).map(SeerrRequestWire.init))
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    /// A series is ordered by Core's rule, from fresh details: which seasons are open, and whether Seerr takes
    /// a partial request at all.
    private static func order(for call: RequestCall, using client: SeerrClient) async throws -> SeerrOrder {
        guard call.kind == .tv else { return SeerrOrder(kind: .movie, tmdbID: call.tmdbID, is4K: call.is4K) }
        async let details = client.details(.tv, tmdbID: call.tmdbID)
        async let settings = client.serverSettings()
        return try await details.order(seasons: Set(call.seasons ?? []), is4K: call.is4K, settings: settings)
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }

    private struct ParseCall: Decodable {
        let address: String
        let apiKey: String?
    }

    private struct Parsed: Encodable {
        let problem: String?
        let address: String?
    }

    private struct DiscoverCall: Decodable {
        let shelf: String
        let page: Int?
    }

    private struct BrowseCall: Decodable {
        let feed: SeerrFeedWire
        let page: Int?
    }

    private struct KindCall: Decodable {
        let kind: SeerrMediaKind
    }

    private struct ProvidersCall: Decodable {
        let kind: SeerrMediaKind
        let region: String
    }

    private struct SearchCall: Decodable {
        let text: String
        let page: Int?
    }

    private struct DetailsCall: Decodable {
        let kind: SeerrMediaKind
        let tmdbID: Int
        let pulsarr: Bool?
        let serverID: String?
    }

    private struct RequestCall: Decodable {
        let kind: SeerrMediaKind
        let tmdbID: Int
        let seasons: [Int]?
        let is4K: Bool
    }

    private struct RequestsCall: Decodable {
        let take: Int?
    }
}

/// The connection a call is for.
struct SeerrLink: Decodable, Hashable {
    let address: String
    /// Nil signs in with Plex.
    let apiKey: String?
    /// The profile's plex.tv token; used only when there is no key.
    let plexToken: String?
    /// A kept sign-in cookie, used only when the connection is new to the bridge.
    let session: String?
}

/// A call that cannot be made because the connection it names is not a valid Seerr address. Kotlin checks
/// with `seerr.parse` before saving one, so this is not expected.
struct SeerrAddressInvalid: Error {
    let problem: String
}

/// The Seerr connections in use, one client each so a Plex sign-in is shared by every call after it.
final class SeerrClients: Sendable {
    static let shared = SeerrClients()

    /// A client, and the cookie its last Plex sign-in produced that Kotlin has not been given yet.
    final class Slot: Sendable {
        let client: SeerrClient
        private let pending: Box

        fileprivate init(client: SeerrClient, pending: Box) {
            self.client = client
            self.pending = pending
        }

        func reply<Value: Encodable>(_ value: Value) throws -> Data {
            try Reply.ok(Answer(value: value, session: pending.take()))
        }
    }

    fileprivate final class Box: Sendable {
        private let cookie = Mutex<String?>(nil)

        func put(_ value: String) {
            cookie.withLock { $0 = value }
        }

        func take() -> String? {
            cookie.withLock { held in
                defer { held = nil }
                return held
            }
        }
    }

    private struct Answer<Value: Encodable>: Encodable {
        let value: Value
        let session: String?
    }

    private struct Key: Hashable {
        let address: String
        let apiKey: String?
        let plexToken: String?
    }

    /// Few connections are ever in use: the saved one, and the one a settings page is testing.
    private static let limit = 4
    private let slots = Mutex<[Key: Slot]>([:])

    func slot(for link: SeerrLink, transport: any PlexTransport) throws -> Slot {
        let config: SeerrConfig
        switch SeerrConfig.parse(address: link.address, apiKey: link.apiKey) {
        case .success(let parsed): config = parsed
        case .failure(let problem): throw SeerrAddressInvalid(problem: String(describing: problem))
        }
        let key = Key(address: config.address.absoluteString, apiKey: link.apiKey, plexToken: link.apiKey == nil ? link.plexToken : nil)
        return slots.withLock { slots in
            if let slot = slots[key] { return slot }
            if slots.count >= Self.limit { slots.removeAll() }
            let pending = Box()
            let client = SeerrClient(
                config: config, transport: transport, plexToken: key.plexToken, session: link.session,
                onSession: { pending.put($0) }
            )
            let slot = Slot(client: client, pending: pending)
            slots[key] = slot
            return slot
        }
    }
}
