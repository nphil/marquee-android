import CMarqueeJNI
import Foundation
import LumaRemoteCore
import MarqueeCore
import Synchronization

/// The two handshakes of a link (DESIGN §8.10) over any byte stream, and the encrypted frames that follow.
/// It is the iPhone's `LinkTransport` and `LinkChannel` with the Core's TCP stream in place of Network.framework,
/// so an Android phone and an iPhone speak the same bytes.
enum LinkTransport {
    /// The caller's side: the preamble and the first handshake message in one write, then the answer.
    static func call(_ stream: any ByteStream, preamble: LinkPreamble, psk: Data) async throws -> LinkChannel {
        var handshake = NoiseHandshake(role: .initiator, prologue: preamble.bytes, psk: psk)
        stream.send(preamble.bytes + (try handshake.writeFirst()))
        let reply = try await stream.receive(exactly: NoiseHandshake.messageLength)
        return LinkChannel(stream: stream, session: try handshake.readSecond(reply))
    }

    /// Who is calling, and whether to pair or to open a session.
    static func preamble(_ stream: any ByteStream) async throws -> LinkPreamble {
        guard let preamble = LinkPreamble(bytes: try await stream.receive(exactly: LinkPreamble.length)) else {
            throw StreamError.closed
        }
        return preamble
    }

    /// The answering side. A caller without the key fails the first message's tag here, before any reply.
    static func answer(_ stream: any ByteStream, preamble: LinkPreamble, psk: Data) async throws -> LinkChannel {
        var handshake = NoiseHandshake(role: .responder, prologue: preamble.bytes, psk: psk)
        try handshake.readFirst(try await stream.receive(exactly: NoiseHandshake.messageLength))
        let (reply, session) = try handshake.writeSecond()
        stream.send(reply)
        return LinkChannel(stream: stream, session: session)
    }
}

/// An open link: one sealed frame per message. Sends may come from any thread; one task reads.
final class LinkChannel: Sendable {
    private let stream: any ByteStream
    private let sealer: Mutex<FrameSealer>
    private let opener: Mutex<FrameOpener>

    init(stream: any ByteStream, session: NoiseSession) {
        self.stream = stream
        sealer = Mutex(FrameSealer(cipher: session.send))
        opener = Mutex(FrameOpener(cipher: session.receive))
    }

    /// Seals and queues one message. Sealing and queueing share the lock, so frames leave in nonce order.
    /// Status is cut to fit a frame before it gets here (`LinkStatus.limited()`).
    func send(_ message: LinkMessage) {
        guard let plaintext = try? message.encoded() else { return }
        sealer.withLock { sealer in
            if let frame = try? sealer.seal(plaintext) { stream.send(frame) }
        }
    }

    /// The other phone's next message; kinds this version does not know are skipped. Throws once the
    /// connection ends or a frame fails to open.
    func receive() async throws -> LinkMessage {
        while true {
            if let plaintext = try opener.withLock({ try $0.next() }) {
                if let message = LinkMessage.decode(plaintext) { return message }
                continue
            }
            let bytes = try await stream.receive(maximum: 16 * 1024)
            opener.withLock { $0.append(bytes) }
        }
    }

    func close() {
        stream.close()
    }
}

/// A TCP listener whose callers are handed to `accepted` one by one. Stopping it ends the accept loop; one
/// that ends by itself (the network went) reports through `failed`.
final class LinkListener: Sendable {
    let port: UInt16
    private let fd: Int32
    /// Shared with the accept thread; a Mutex cannot be captured by value.
    private final class Flags: Sendable {
        struct State: Sendable {
            var stopped = false
            /// The descriptor is closed, and may already belong to something else.
            var closed = false
        }
        let state = Mutex(State())
    }
    private let flags = Flags()

    init?(loopback: Bool, accepted: @escaping @Sendable (RemoteSocket) -> Void, failed: @escaping @Sendable () -> Void) {
        var port: UInt16 = 0
        let fd = marquee_link_listener(loopback, &port)
        guard fd >= 0 else { return nil }
        self.port = port
        self.fd = fd
        // Blocking accept, so a thread of its own; `stop` wakes it with a shutdown.
        Thread.detachNewThread { [flags] in
            while true {
                let peer = marquee_remote_accept(fd)
                if peer < 0 { break }
                accepted(RemoteSocket(fd: peer))
            }
            let stopped = flags.state.withLock { state -> Bool in
                state.closed = true
                return state.stopped
            }
            marquee_remote_close(fd)
            if !stopped { failed() }
        }
    }

    func stop() {
        flags.state.withLock { state in
            guard !state.stopped else { return }
            state.stopped = true
            if !state.closed { marquee_remote_shutdown(fd) }
        }
    }
}
