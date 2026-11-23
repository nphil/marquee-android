import Foundation
import MarqueeCore
import Synchronization

/// The HomeLabber Quality Map plugin's address check and connection test, as the iPhone's HomeLabber page does them.
enum HomeLabberCalls {
    static func handles(_ name: String) -> Bool { name.hasPrefix("homelabber.") }

    static func handle(
        _ name: String, _ payload: Data, transport: any PlexTransport = OkHttpTransport.shared
    ) async throws -> Data {
        let call = try JSONDecoder().decode(ConfigCall.self, from: payload)
        switch name {
        case "homelabber.address":
            switch HomeLabberConfig.parse(address: call.address, token: call.token) {
            case .success(let config): return try Reply.ok(Parsed(address: config.address.absoluteString))
            case .failure(let problem): return try Reply.ok(Parsed(problem: String(describing: problem)))
            }
        case "homelabber.status":
            let config = try HomeLabberConfig.parse(address: call.address, token: call.token).get()
            do {
                let client = HomeLabberClient(config: config, transport: OriginRestrictedTransport(upstream: transport))
                return try Reply.ok(Connection(status: try await client.status()))
            } catch let error as HomeLabberError {
                return try Reply.ok(Connection(problem: String(describing: error)))
            }
        default:
            throw BridgeError.unknownMethod(name)
        }
    }

    struct ConfigCall: Decodable {
        let address: String
        let token: String
    }

    private struct Parsed: Encodable {
        var address: String?
        var problem: String?
    }

    private struct Connection: Encodable {
        var enabled: Bool?
        var scored: Int?
        var total: Int?
        var running: Bool?
        var problem: String?

        init(problem: String) { self.problem = problem }

        init(status: QualityMapStatus) {
            enabled = status.enabled
            scored = status.scored
            total = status.total
            running = status.running
        }
    }
}

/// HomeLabber's scores for the titles whose Download menu was opened. A title is asked about once: a
/// title HomeLabber has not scored stays unscored until the address or token changes, and a failed ask
/// pauses every ask for a minute so an unreachable HomeLabber does not slow each menu.
final class HomeLabberMaps: Sendable {
    static let shared = HomeLabberMaps()

    static let quiet: TimeInterval = 60

    private struct State {
        var config: HomeLabberConfig?
        var entries: [String: QualityMapEntry] = [:]
        var unscored: Set<String> = []
        var quietUntil = Date.distantPast
    }

    private let state = Mutex(State())

    /// The title's measured tiers; nil without a saved HomeLabber, for an unscored title, and while HomeLabber is unreachable.
    func entry(
        config: HomeLabberConfig?, server: String, ratingKey: String, transport: any PlexTransport, now: Date = .now
    ) async -> QualityMapEntry? {
        let key = "\(server)/\(ratingKey)"
        let (known, ask) = state.withLock { state -> (QualityMapEntry?, Bool) in
            if state.config != config { state = State(config: config) }
            guard config != nil else { return (nil, false) }
            if let entry = state.entries[key] { return (entry, false) }
            return (nil, !state.unscored.contains(key) && state.quietUntil <= now)
        }
        guard ask, let config else { return known }
        do {
            let client = HomeLabberClient(config: config, transport: OriginRestrictedTransport(upstream: transport))
            let found = try await client.entry(server: server, ratingKey: ratingKey)
            state.withLock { state in
                guard state.config == config else { return }
                if let found { state.entries[key] = found } else { state.unscored.insert(key) }
            }
            return found
        } catch {
            if !Task.isCancelled {
                state.withLock { state in
                    if state.config == config { state.quietUntil = now.addingTimeInterval(Self.quiet) }
                }
            }
            return nil
        }
    }
}
