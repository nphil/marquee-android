import CMarqueeJNI
import Foundation
import MarqueeCore
import Synchronization

/// `PlexTransport` over the app's OkHttp client. A request goes up to Kotlin (`NativeCore.transportSend`)
/// as JSON plus its body; the answer comes back through `complete` or `fail`. Cancelling the calling task
/// cancels the OkHttp call, which the probe race relies on to drop the losing addresses.
final class OkHttpTransport: PlexTransport {
    static let shared = OkHttpTransport()

    private let pending = Mutex<[Int64: CheckedContinuation<PlexHTTPResponse, any Error>]>([:])
    private let lastID = Atomic<Int64>(0)

    func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
        let id = lastID.add(1, ordering: .relaxed).newValue
        let description = try JSONEncoder().encode(WireRequest(request))
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pending.withLock { $0[id] = continuation }
                // Cancelled before the continuation was stored: the handler below found nothing to resume.
                if Task.isCancelled {
                    take(id)?.resume(throwing: CancellationError())
                    return
                }
                description.withUnsafeBytes { wire in
                    let body = request.body ?? Data()
                    body.withUnsafeBytes { bytes in
                        marquee_transport_send(
                            id,
                            wire.bindMemory(to: UInt8.self).baseAddress, wire.count,
                            bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count,
                            request.body != nil
                        )
                    }
                }
            }
        } onCancel: {
            guard let continuation = take(id) else { return }
            marquee_transport_cancel(id)
            continuation.resume(throwing: CancellationError())
        }
    }

    func complete(id: Int64, status: Int, headers: Data, body: Data) {
        guard let continuation = take(id) else { return }
        let fields = (try? JSONDecoder().decode([String: String].self, from: headers)) ?? [:]
        continuation.resume(returning: PlexHTTPResponse(status: status, body: body, headers: fields))
    }

    func fail(id: Int64, code: Int32) {
        take(id)?.resume(throwing: NetworkFailure(wire: code))
    }

    private func take(_ id: Int64) -> CheckedContinuation<PlexHTTPResponse, any Error>? {
        pending.withLock { $0.removeValue(forKey: id) }
    }
}

/// What Kotlin needs to build the OkHttp request; the body travels beside it as bytes.
private struct WireRequest: Encodable {
    let method: String
    let url: String
    let headers: [String: String]
    let timeoutMillis: Int
    let restrictToOrigin: Bool

    init(_ request: PlexRequest) {
        method = request.method.rawValue
        url = request.url.absoluteString
        headers = request.headers
        timeoutMillis = Int((request.timeout * 1000).rounded())
        restrictToOrigin = request.restrictToOrigin
    }
}

extension NetworkFailure {
    /// The codes `NativeTransport.Failure` sends.
    init(wire code: Int32) {
        self = switch code {
        case 1: .timedOut
        case 2: .connectionLost
        case 3: .cannotConnect
        case 4: .hostNotFound
        case 5: .offline
        case 6: .secureConnectionFailed
        default: .other
        }
    }
}
