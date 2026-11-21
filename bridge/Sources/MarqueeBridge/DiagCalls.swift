import Foundation
import MarqueeDiagnostics

/// The `diag.*` calls reach the diagnostic log through its router; this gives it the bridge's HTTP, a queue of
/// its own and logcat.
enum DiagCalls {
    private static let router = DiagRouter(
        executor: QueueExecutor(), send: NtfyClient.send(through: OkHttpTransport.shared), logcat: DiagLogcat.sink
    )

    static func handles(_ method: String) -> Bool {
        DiagRouter.handles(method)
    }

    static func handle(_ method: String, _ payload: Data) async throws -> Data {
        try Reply.ok(try await router.handle(method, payload))
    }
}
