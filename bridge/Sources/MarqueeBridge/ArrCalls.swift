import Foundation
import MarqueeCore

/// Radarr's and Sonarr's settings pages use the iPhone's address rules and connection test, as Bazarr's do.
///
/// - `arr.address {kind, address, key}` answers the address a connection would use, or the `problem` with it:
///   `scheme`, `host`, `plaintext` or `key`.
/// - `arr.status {kind, address, key}` asks the service for its version, which needs the key, so it proves the address
///   and the key together. It answers `{version}`, or `{problem}`: `badKey`, `notArr` (another app answered, or the
///   other one of Radarr and Sonarr), `timedOut`, `unreachable` or `failed`.
enum ArrCalls {
    static func handles(_ method: String) -> Bool {
        method.hasPrefix("arr.")
    }

    static func handle(_ method: String, _ payload: Data, through transport: any PlexTransport = OkHttpTransport.shared) async throws -> Data {
        let call = try JSONDecoder().decode(ArrCall.self, from: payload)
        switch method {
        case "arr.address":
            return try Reply.ok(ParsedAddress(ArrConfig.parse(address: call.address, key: call.key).map(\.address)))
        case "arr.status":
            let config = try ArrConfig.parse(address: call.address, key: call.key).get()
            do {
                return try Reply.ok(Connection(version: try await ArrClient(kind: call.kind, config: config, transport: transport).version()))
            } catch let error as ArrError {
                return try Reply.ok(Connection(problem: Self.name(of: error)))
            }
        default:
            throw BridgeError.unknownMethod(method)
        }
    }

    private static func name(of error: ArrError) -> String {
        switch error {
        case .unreachable: "unreachable"
        case .timedOut: "timedOut"
        case .badKey: "badKey"
        case .notArr: "notArr"
        case .failed: "failed"
        }
    }
}

private struct ArrCall: Decodable {
    let kind: ArrKind
    let address: String
    let key: String
}

private struct ParsedAddress: Encodable {
    var address: String?
    var problem: String?

    init(_ result: Result<URL, ArrConfig.Problem>) {
        switch result {
        case .success(let url): address = url.absoluteString
        case .failure(let error): problem = String(describing: error)
        }
    }
}

private struct Connection: Encodable {
    var version: String?
    var problem: String?
}
