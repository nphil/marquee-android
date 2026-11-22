#if !os(Android)
import CMarqueeJNI
import Dispatch
import Foundation
import LumaRemoteCore
import LumaRemoteFakeTV
import Testing

@testable import MarqueeBridge

@Suite struct RemoteCallsTests {
    @Test func confirmsInviteThenSendsCommandsToThePairedTV() async throws {
        let tv = FakeTV()
        let server = try LoopbackRemote()
        defer { server.stop() }
        server.serve(tv)
        let port = server.port
        let link = await tv.pairingLink(host: "127.0.0.1", port: port)
        let url = ["url": link.absoluteString]
        let checked: Invite = try await request("invite", url)
        #expect(checked.host == "127.0.0.1")
        #expect(checked.port == port)
        #expect(await tv.log.isEmpty)

        let phone = ["name": "Android fixture", "model": "Fixture", "app": "Marquee test"]
        let pairing: PairedTV = try await request("pair", phone.merging(url) { _, new in new })
        #expect(pairing.name == "Living Room TV")
        #expect(pairing.key.count == 32)
        #expect(await tv.log == ["pair Android fixture"])

        let session = ["session": UUID().uuidString]
        let record = try JSONSerialization.jsonObject(with: JSONEncoder().encode(pairing))
        var parameters: [String: Any] = phone.merging(session) { _, new in new }
        parameters["tv"] = record
        let _: Ack = try await request("open", parameters)
        do {
            let ready = try await withDeadline(.seconds(5), onTimeout: {
                Task { let _: Ack? = try? await request("close", session) }
            }) {
                while true {
                    let status: Status = try await request("event", session)
                    if status.status == "connected" { return status }
                    try Task.checkCancellation()
                }
            }
            #expect(ready.actions.contains("quick"))
            #expect(ready.volume == 30)
            await tv.setTextField(active: true)
            let _: Ack = try await request("input", session.merging(["key": "up"]) { _, new in new })
            let _: Ack = try await request("input", session.merging(["text": "Hello 🌙"]) { _, new in new })
            let _: Ack = try await request("input", session.merging(["edit": "enter"]) { _, new in new })
            let _: Ack = try await request("action", session.merging(["action": "quick"]) { _, new in new })
            let apps: [Listed] = try await request("list", session.merging(["kind": "apps"]) { _, new in new })
            #expect(apps.map(\.label) == ["YouTube", "Netflix", "Marquee"])
            let received = await tv.log
            #expect(received == ["pair Android fixture", "hello Android fixture", "key up tap", "text Hello 🌙", "edit enter", "act quick", "get apps"])
        } catch {
            let _: Ack? = try? await request("close", session)
            throw error
        }
        let _: Ack = try await request("close", session)
    }

    private struct Ack: Decodable {}
    private struct Invite: Decodable { let host: String; let port: UInt16 }
    private struct Listed: Decodable { let label: String }
    private struct Status: Decodable, Sendable { let status: String; let actions: [String]; let volume: Double? }
}

private struct RemoteFixtureReply<T: Decodable>: Decodable {
    let ok: T
}

private func request<T: Decodable>(_ operation: String, _ parameters: [String: Any]) async throws -> T {
    let payload = try JSONSerialization.data(withJSONObject: parameters)
    let reply = try await RemoteCalls.handle("remote.\(operation)", payload)
    return try JSONDecoder().decode(RemoteFixtureReply<T>.self, from: reply).ok
}

private final class LoopbackRemote: Sendable {
    private let fd: Int32
    let port: UInt16

    init() throws {
        var port: UInt16 = 0
        fd = marquee_remote_listener(&port)
        self.port = port
        try #require(fd >= 0)
    }

    deinit { if fd >= 0 { marquee_remote_close(fd) } }
    func stop() { marquee_remote_shutdown(fd) }

    func serve(_ tv: FakeTV) {
        DispatchQueue(label: "marquee.remote.fixture").async {
            // A pairing socket and an authenticated session. No listener in the Android app.
            for _ in 0..<2 {
                let peer = marquee_remote_accept(self.fd)
                guard peer >= 0 else { return }
                Task { await tv.serve(RemoteSocket(fd: peer)) }
            }
        }
    }
}
#endif
