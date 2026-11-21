import Foundation
import MarqueeCore

// The JSON shapes of the calls in `Methods`. Kotlin mirrors them in `CoreModels.kt`.

struct Configure: Decodable {
    let identity: PlexClientIdentity
}

struct Account: Decodable {
    let token: String
}

struct SwitchUser: Decodable {
    let token: String
    let user: PlexHomeUser
    let pin: String?
}

struct ServerCall: Decodable {
    let server: PlexServer
    /// The address that answered last time, tried alone first.
    let remembered: URL?
    /// Probe every address again instead of keeping the one in use.
    let refresh: Bool?
}

/// A plex.tv/link code. Dates travel as seconds since 1970.
struct PIN: Codable {
    let id: Int
    let code: String
    let expiresAt: Double?

    init(_ pin: PlexPIN) {
        id = pin.id
        code = pin.code
        expiresAt = pin.expiresAt?.timeIntervalSince1970
    }

    var plex: PlexPIN {
        PlexPIN(id: id, code: code, expiresAt: expiresAt.map(Date.init(timeIntervalSince1970:)))
    }
}

struct Token: Encodable {
    let token: String
}

struct Empty: Encodable {}

/// How a server was reached: the address in use, or none, and the probe round's lines.
struct Reach: Encodable {
    let connection: PlexConnection?
    /// "Local 192.168.1.69", as the server list shows it.
    let inUse: String?
    let lines: [String]

    init(connection: PlexConnection?, lines: [String]) {
        self.connection = connection
        inUse = connection.map { "\($0.kindWord) \($0.displayAddress)" }
        self.lines = lines
    }
}

struct Section: Encodable {
    let key: String
    let title: String
    let type: PlexMediaType

    init(_ section: PlexLibrarySection) {
        key = section.key
        title = section.title
        type = section.type
    }
}

enum Reply {
    static func ok<Value: Encodable>(_ value: Value) throws -> Data {
        try JSONEncoder().encode(["ok": value])
    }

    static func failure(_ error: any Error) -> Data {
        (try? JSONEncoder().encode(["error": Failure(error)])) ?? Data(#"{"error":{"kind":"other"}}"#.utf8)
    }
}

/// An error as Kotlin sees it. `kind` is the PlexError case or a bridge condition; `failure` names the
/// NetworkFailure when nothing answered.
struct Failure: Encodable {
    var kind: String
    var status: Int?
    var message: String?
    var failure: String?

    init(_ error: any Error) {
        switch error {
        case let error as PlexError:
            switch error {
            case .unauthorized: kind = "unauthorized"
            case .unreachable(let failure):
                kind = "unreachable"
                self.failure = String(describing: failure)
            case .server(let status, let message):
                kind = "server"
                self.status = status
                self.message = message
            case .decoding(let detail):
                kind = "decoding"
                message = detail
            case .pinExpired: kind = "pinExpired"
            case .profilePINRejected: kind = "profilePINRejected"
            case .noConnections: kind = "noConnections"
            }
        case let failure as NetworkFailure:
            kind = "unreachable"
            self.failure = String(describing: failure)
        case is CancellationError:
            kind = "cancelled"
        case is DecodingError:
            kind = "badRequest"
            message = String(describing: error)
        case BridgeError.unknownMethod(let method):
            kind = "badRequest"
            message = "unknown method \(method)"
        case BridgeError.notConfigured:
            kind = "notConfigured"
        default:
            kind = "other"
            message = String(describing: error)
        }
    }
}
