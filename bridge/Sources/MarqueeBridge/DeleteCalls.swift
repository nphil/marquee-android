import Foundation
import MarqueeCore

/// A title's delete in two calls, so the app takes the title out of its lists as soon as the server has deleted it
/// and the other services follow on their own. Both carry the usual server parameters and the same connections:
///
/// - `detail.delete {item, token?, radarr?, sonarr?, seerr?}` deletes on the server and asks no other service. It
///   answers `{outcome, ticket?}`, the ticket coming with `deleted`. The connections are here only so the ids Radarr,
///   Sonarr and Seerr know the title by are read before the server forgets it.
/// - `detail.cleanUp {ticket, alreadyDeleted, token?, radarr?, sonarr?, seerr?, previous?}` then runs the steps: the
///   Watchlist (`token`), Radarr or Sonarr, and Seerr. It answers each step's own result and, while a step failed that
///   asking again may fix, the ticket again. `previous` is that earlier result, `{watchlist, guid?, arr, requests}`,
///   handed back so only the steps that failed run again. `alreadyDeleted` is this session's earlier deletes, because
///   the server lists those episodes until its trash is emptied.
///
/// `radarr` and `sonarr` are `{address, apiKey}`, absent when not set up; `seerr` is a `SeerrLink`. A connection that
/// no longer parses counts as absent, so a setting never holds a delete back. Replies are `{value, session?}` like
/// Seerr's, since a cleanup may sign in to Seerr and hand Kotlin the cookie.
enum DeleteCalls {
    private static let names: Set<String> = ["detail.delete", "detail.cleanUp"]

    static func handles(_ method: String) -> Bool {
        names.contains(method)
    }

    /// `sleep` is replaceable so a test's failing step needs no real wait.
    static func handle(
        _ method: String, _ payload: Data, library: (ServerCall) throws -> PlexLibrary,
        watchlist: (String) throws -> PlexWatchlist, through transport: any PlexTransport = OkHttpTransport.shared,
        clients: SeerrClients = .shared, sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) }
    ) async throws -> Data {
        let connections = try decode(Connections.self, payload)
        let slot = connections.seerr.flatMap { try? clients.slot(for: $0, transport: transport) }
        let deletion = MediaDeletion(
            server: try library(decode(ServerCall.self, payload)),
            watchlist: try connections.token.map(watchlist),
            arr: ArrServices(
                radarr: client(.radarr, connections.radarr, transport), sonarr: client(.sonarr, connections.sonarr, transport)),
            requests: slot?.client,
            sleep: sleep
        )
        switch method {
        case "detail.delete":
            let first = await deletion.deleteFromServer(try decode(DeleteCall.self, payload).item)
            return try answer(DeletionWire(outcome: first.outcome, ticket: first.ticket), slot)
        case "detail.cleanUp":
            let call = try decode(CleanUpCall.self, payload)
            var deleted = DeletedTitles()
            for key in call.alreadyDeleted { deleted.insert(key) }
            let result: MediaDeletionResult
            if let previous = call.previous {
                result = await deletion.retry(previous.result(ticket: call.ticket), alreadyDeleted: deleted)
            } else {
                result = await deletion.cleanUp(call.ticket, alreadyDeleted: deleted)
            }
            return try answer(DeletionWire(result), slot)
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    private static func client(_ kind: ArrKind, _ link: ArrLink?, _ transport: any PlexTransport) -> ArrClient? {
        link.flatMap { try? ArrConfig.parse(address: $0.address, key: $0.apiKey).get() }
            .map { ArrClient(kind: kind, config: $0, transport: transport) }
    }

    private static func answer(_ wire: DeletionWire, _ slot: SeerrClients.Slot?) throws -> Data {
        if let slot { return try slot.reply(wire) }
        return try Reply.ok(Answer(value: wire))
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }

    private struct Answer: Encodable {
        let value: DeletionWire
    }
}

/// An address and key for Radarr or Sonarr, as the app saved them.
private struct ArrLink: Decodable {
    let address: String
    let apiKey: String
}

/// What a delete may ask beside the server.
private struct Connections: Decodable {
    /// Nil when no account is signed in to a watchlist.
    let token: String?
    let radarr: ArrLink?
    let sonarr: ArrLink?
    let seerr: SeerrLink?
}

/// `item` carries the identity on screen in Plex's own key names, which the Watchlist cleanup falls back on when the
/// server cannot give one.
private struct DeleteCall: Decodable {
    let item: PlexMetadata
}

private struct CleanUpCall: Decodable {
    let ticket: DeletionTicket
    let alreadyDeleted: [String]
    let previous: PreviousCleanup?
}

/// The steps an earlier cleanup finished and the ones it left failed.
private struct PreviousCleanup: Decodable {
    let watchlist: WatchlistCleanup
    let arr: ServiceCleanup
    let requests: ServiceCleanup

    private enum Keys: String, CodingKey { case watchlist, guid, arr, requests }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: Keys.self)
        let name = try values.decode(String.self, forKey: .watchlist)
        let guid = try values.decodeIfPresent(String.self, forKey: .guid)
        switch (name, guid) {
        case ("unchanged", _): watchlist = .unchanged
        case ("removed", let guid?): watchlist = .removed(guid: guid)
        case ("failed", let guid?): watchlist = .failed(guid: guid)
        default:
            throw DecodingError.dataCorruptedError(forKey: .watchlist, in: values, debugDescription: "unknown watchlist result \(name)")
        }
        arr = try Self.step(.arr, in: values)
        requests = try Self.step(.requests, in: values)
    }

    func result(ticket: DeletionTicket) -> MediaDeletionResult {
        MediaDeletionResult(outcome: .deleted, watchlist: watchlist, arr: arr, requests: requests, ticket: ticket)
    }

    private static func step(_ key: Keys, in values: KeyedDecodingContainer<Keys>) throws -> ServiceCleanup {
        let name = try values.decode(String.self, forKey: key)
        guard let step = ServiceCleanup(rawValue: name) else {
            throw DecodingError.dataCorruptedError(forKey: key, in: values, debugDescription: "unknown step result \(name)")
        }
        return step
    }
}

/// How a delete stands. `outcome` is `deleted`, `notAllowed` or `failed`. `watchlist` is `unchanged`, `removed` or
/// `failed`, with the catalogue `guid` for the last two; `arr` and `requests` are a `ServiceCleanup` name. `ticket` is
/// what `detail.cleanUp` takes: it comes with the server's delete, and stays after a cleanup only while a step can be
/// retried.
struct DeletionWire: Encodable {
    let outcome: String
    let watchlist: String
    let guid: String?
    let arr: String
    let requests: String
    let ticket: DeletionTicket?

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
        arr = result.arr.rawValue
        requests = result.requests.rawValue
        ticket = result.ticket
    }

    init(outcome: DeleteOutcome, ticket: DeletionTicket? = nil) {
        self.init(MediaDeletionResult(outcome: outcome, ticket: ticket))
    }

    private static func name(of outcome: DeleteOutcome) -> String {
        switch outcome {
        case .deleted: "deleted"
        case .notAllowed: "notAllowed"
        case .failed: "failed"
        }
    }
}
