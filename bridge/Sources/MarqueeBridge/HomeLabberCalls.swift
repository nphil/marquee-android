import Foundation
import MarqueeCore
import Synchronization

/// The saved HomeLabber address and token, handed in with each call that reads the quality map; nothing is kept here.
struct HomeLabberLink: Decodable {
    let address: String
    let token: String

    var config: HomeLabberConfig? {
        try? HomeLabberConfig.parse(address: address, token: token).get()
    }

    /// A client for the saved link, or nil when it does not parse; the token goes to that one origin only.
    func client(transport: any PlexTransport) -> HomeLabberClient? {
        config.map { HomeLabberClient(config: $0, transport: OriginRestrictedTransport(upstream: transport)) }
    }
}

extension QualityMapCache {
    /// The session's lookups, shared by the Download menu and the player.
    static let shared = QualityMapCache()
}

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
        /// Seconds since 1970 of HomeLabber's last scoring run.
        var lastRun: Double?
        var encoder: String?
        var problem: String?

        init(problem: String) { self.problem = problem }

        init(status: QualityMapStatus) {
            enabled = status.enabled
            scored = status.scored
            total = status.total
            running = status.running
            lastRun = status.lastRun?.timeIntervalSince1970
            encoder = status.encoder
        }
    }
}
