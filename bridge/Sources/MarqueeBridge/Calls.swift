import CMarqueeJNI
import Foundation
import MarqueeCore
import Synchronization

/// Calls in flight, kept so Kotlin can cancel one by id. Each runs as its own task; the reply goes back
/// through `NativeCore.onResult`, cancelled or not, and Kotlin drops replies it no longer waits for.
final class Calls: Sendable {
    static let shared = Calls()

    private let tasks = Mutex<[Int64: Task<Void, Never>]>([:])

    func start(id: Int64, method: String, payload: Data) {
        // Stored under the lock the task's own clean-up takes, so a call that ends at once cannot leave
        // its entry behind.
        tasks.withLock { tasks in
            tasks[id] = Task {
                let reply = await Methods.shared.reply(to: method, payload: payload)
                _ = self.tasks.withLock { $0.removeValue(forKey: id) }
                reply.withUnsafeBytes { bytes in
                    marquee_deliver_result(id, bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count)
                }
            }
        }
    }

    func cancel(id: Int64) {
        tasks.withLock { $0.removeValue(forKey: id) }?.cancel()
    }
}

/// The methods Kotlin can call: a name and a JSON object in, `{"ok": …}` or `{"error": {"kind": …}}` out.
/// Plex types travel in MarqueeCore's own Codable form, so Kotlin can store a server or profile and hand it
/// back unchanged.
final class Methods: Sendable {
    static let shared = Methods()

    private struct State {
        var identity: PlexClientIdentity?
        /// One per server and token, so the address a probe picked serves the calls after it.
        var libraries: [String: PlexLibrary] = [:]
    }

    private let state = Mutex(State())

    func reply(to method: String, payload: Data) async -> Data {
        do {
            return try await handle(method, payload)
        } catch {
            return Reply.failure(error)
        }
    }

    private func handle(_ method: String, _ payload: Data) async throws -> Data {
        switch method {
        case "configure":
            let identity = try decode(Configure.self, payload).identity
            state.withLock { $0 = State(identity: identity) }
            return try Reply.ok(Empty())
        case "reset":
            state.withLock { $0.libraries = [:] }
            return try Reply.ok(Empty())
        case "pin.create":
            return try Reply.ok(PIN(try await plexTV(token: nil).createPIN(strong: false)))
        case "pin.wait":
            let pin = try decode(PIN.self, payload)
            return try Reply.ok(Token(token: try await plexTV(token: nil).waitForToken(pin.plex)))
        case "plex.account":
            return try Reply.ok(try await plexTV(token: decode(Account.self, payload).token).account())
        case "plex.servers":
            return try Reply.ok(try await plexTV(token: decode(Account.self, payload).token).servers())
        case "plex.homeUsers":
            return try Reply.ok(try await plexTV(token: decode(Account.self, payload).token).homeUsers())
        case "plex.switchUser":
            let request = try decode(SwitchUser.self, payload)
            let token = try await plexTV(token: request.token).switchHomeUser(request.user, pin: request.pin)
            return try Reply.ok(Token(token: token))
        case "server.connect":
            let request = try decode(ServerCall.self, payload)
            let connection = try library(for: request).connection
            let picked: PlexConnection?
            do {
                picked = try await request.refresh == true ? connection.refresh() : connection.connection()
            } catch PlexError.unreachable, PlexError.noConnections {
                picked = nil
            }
            return try Reply.ok(Reach(connection: picked, lines: await connection.probeRounds.last?.lines ?? []))
        case "library.sections":
            let library = try library(for: decode(ServerCall.self, payload))
            return try Reply.ok(try await library.sections().map(Section.init))
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    private func identity() throws -> PlexClientIdentity {
        guard let identity = state.withLock({ $0.identity }) else { throw BridgeError.notConfigured }
        return identity
    }

    private func plexTV(token: String?) throws -> PlexTV {
        PlexTV(http: PlexHTTPClient(identity: try identity(), token: token, transport: OkHttpTransport.shared))
    }

    private func library(for call: ServerCall) throws -> PlexLibrary {
        let identity = try identity()
        let key = call.server.clientIdentifier + " " + (call.server.accessToken ?? "")
        return state.withLock { state in
            if let library = state.libraries[key] { return library }
            let library = PlexLibrary(server: call.server, identity: identity, transport: OkHttpTransport.shared, rememberedURI: call.remembered)
            state.libraries[key] = library
            return library
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }
}

enum BridgeError: Error {
    case unknownMethod(String)
    case notConfigured
}
