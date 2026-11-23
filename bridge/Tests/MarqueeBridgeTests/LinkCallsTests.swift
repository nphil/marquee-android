#if !os(Android)
import Foundation
import LumaRemoteCore
import Testing

@testable import MarqueeBridge

@Suite struct LinkCallsTests {
    private let idA = "00112233445566778899aabbccddeeff"
    private let idB = "ffeeddccbbaa99887766554433221100"

    /// Two engines on loopback: one shows a code, the other joins with it; both then find each other, open a
    /// session with the key the pairing made, and carry messages. Unlinking from one side drops the other.
    @Test func linksByCodeThenOpensASessionAndCarriesMessages() async throws {
        let a = Phone(LinkEngine())
        let b = Phone(LinkEngine())
        let _: Ack = try await a.call("configure", ["id": idA, "name": "Pixel", "phones": []])
        let _: Ack = try await b.call("configure", ["id": idB, "name": "Tablet", "phones": []])
        let aPort: Port = try await a.call("listen", ["loopback": true])
        let bPort: Port = try await b.call("listen", ["loopback": true])

        let shown: Shown = try await a.call("invite.open")
        let code: Code = try await b.call("parse", ["url": shown.url])
        #expect(code.id == idA && code.name == "Pixel")
        let joined: Linked = try await b.call("join", ["url": shown.url, "host": "127.0.0.1", "port": aPort.port])
        #expect(joined.id == idA && joined.name == "Pixel")

        let paired = try await a.next { $0.type == "paired" }
        #expect(paired.phone?.id == idB && paired.phone?.name == "Tablet")
        #expect(paired.phone?.key == joined.key)

        let _: Ack = try await a.call("seen", ["peers": [["id": idB, "host": "127.0.0.1", "port": bPort.port]]])
        let _: Ack = try await b.call("seen", ["peers": [["id": idA, "host": "127.0.0.1", "port": aPort.port]]])
        _ = try await a.next { $0.type == "connected" && $0.peer == idB }
        _ = try await b.next { $0.type == "connected" && $0.peer == idA }

        let hello = try await b.next { $0.kind == "hello" }
        #expect(hello.json["name"] as? String == "Pixel")

        let _: Ack = try await b.call("send", ["peer": idA, "message": #"{"type":"input","input":"select"}"#])
        let input = try await a.next { $0.kind == "input" }
        #expect(input.json["input"] as? String == "select")

        let _: Ack = try await a.call("status", ["status": ["inPlayer": false, "nowPlaying": [
            "title": "Heat", "isPlaying": true, "position": 12.0, "duration": 100.0, "hasNext": false, "subtitles": [],
        ]]])
        // The empty status every session opens with comes first; the one that names the title is the news.
        let status = try await b.next { $0.kind == "status" && $0.playingTitle == "Heat" }
        #expect(status.playingTitle == "Heat")

        let _: Ack = try await b.call("forget", ["peer": idA, "unlink": true])
        _ = try await a.next { $0.kind == "unlink" }
        _ = try await a.next { $0.type == "disconnected" && $0.peer == idB }
        await #expect(throws: RemoteFailure.self) {
            let _: Ack = try await a.call("send", ["peer": idB, "message": #"{"type":"unlink"}"#])
        }

        let _: Ack = try await a.call("unlisten")
        let _: Ack = try await b.call("unlisten")
    }

    @Test func aCodeThatIsNotALinkIsRefused() async throws {
        let phone = Phone(LinkEngine())
        await #expect(throws: RemoteFailure.self) {
            let _: Code = try await phone.call("parse", ["url": "lumaremote://pair?v=1"])
        }
    }
}

private struct Ok<Value: Decodable>: Decodable { let ok: Value }
private struct Ack: Decodable {}
private struct Port: Decodable { let port: UInt16 }
private struct Shown: Decodable { let url: String }
private struct Code: Decodable { let id: String; let name: String }
private struct Linked: Decodable { let id: String; let name: String; let key: String }

private struct Event: Decodable {
    let type: String
    let peer: String?
    let message: String?
    let phone: Linked?

    /// The wire message's `type`, for a `message` event.
    var kind: String? { type == "message" ? json["type"] as? String : nil }
    var playingTitle: String? {
        ((json["status"] as? [String: Any])?["nowPlaying"] as? [String: Any])?["title"] as? String
    }
    var json: [String: Any] {
        message.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] } ?? [:]
    }
}

/// One phone's engine and the events it has reported, in order, that a test has not asked for yet.
private final class Phone: @unchecked Sendable {
    private let engine: LinkEngine
    private var buffer: [Event] = []

    init(_ engine: LinkEngine) { self.engine = engine }

    func call<Value: Decodable>(_ operation: String, _ parameters: [String: Any] = [:]) async throws -> Value {
        let payload = try JSONSerialization.data(withJSONObject: parameters)
        let reply = try await LinkCalls.handle("link.\(operation)", payload, engine: engine)
        return try JSONDecoder().decode(Ok<Value>.self, from: reply).ok
    }

    /// The first event, oldest first, that `match` accepts; the ones before it are dropped.
    func next(_ match: (Event) -> Bool) async throws -> Event {
        while true {
            if let index = buffer.firstIndex(where: match) {
                let event = buffer[index]
                buffer.removeSubrange(...index)
                return event
            }
            let engine = engine
            let waiting = Task { await engine.nextEvents() }
            let batch = try await withDeadline(.seconds(8), onTimeout: { waiting.cancel() }) { await waiting.value }
            guard !batch.isEmpty else { throw StreamError.timedOut }
            buffer += try JSONDecoder().decode([Event].self, from: JSONEncoder().encode(batch))
        }
    }
}
#endif
