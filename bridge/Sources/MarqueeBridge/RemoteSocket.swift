import CMarqueeJNI
import Dispatch
import Foundation
import LumaRemoteCore
import Synchronization

/// The Core's ordered stream over Android's TCP socket. Never block a Swift worker or the caller's actor.
final class RemoteSocket: ByteStream, Sendable {
    private final class Descriptor: Sendable {
        let fd: Int32

        init(_ fd: Int32) { self.fd = fd }
        deinit { marquee_remote_close(fd) }

        func send(_ bytes: Data, datagram: Bool) -> Bool {
            bytes.withUnsafeBytes { marquee_remote_send(fd, $0.bindMemory(to: UInt8.self).baseAddress, $0.count, datagram) }
        }
    }

    private let descriptor: Descriptor
    private let reader = DispatchQueue(label: "Marquee.remote.read")
    private let writer = DispatchQueue(label: "Marquee.remote.write", qos: .userInitiated)
    private struct WriteState: Sendable {
        var closed = false
        var queuedBytes = 0
    }
    private let writes = Mutex(WriteState())
    let remoteAddress: (host: String, port: UInt16)?

    init(fd: Int32, remoteAddress: (String, UInt16)? = nil) {
        descriptor = Descriptor(fd)
        self.remoteAddress = remoteAddress
    }

    static func connect(_ address: TVAddress) async throws -> any ByteStream {
        guard case .host(let host, let port) = address else { throw StreamError.unreachable("no address") }
        let fd = await dial(host, port, datagram: false)
        guard fd >= 0 else { throw StreamError.unreachable("TCP") }
        let result = RemoteSocket(fd: fd, remoteAddress: (host, port))
        try Task.checkCancellation()
        return result
    }

    private static let dialer = DispatchQueue(label: "Marquee.remote.dial", attributes: .concurrent)

    private static func dial(_ host: String, _ port: UInt16, datagram: Bool) async -> Int32 {
        await withCheckedContinuation { done in
            dialer.async { done.resume(returning: marquee_remote_connect(host, port, datagram)) }
        }
    }

    func send(_ bytes: Data) {
        let accepted = writes.withLock { state -> Bool in
            guard !state.closed, state.queuedBytes + bytes.count <= 64 * 1024 else { return false }
            state.queuedBytes += bytes.count
            writer.async {
                if !self.descriptor.send(bytes, datagram: false) { self.close() }
                self.writes.withLock { $0.queuedBytes -= bytes.count }
            }
            return true
        }
        if !accepted { close() }
    }

    func receive(maximum: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { done in
            reader.async { [descriptor] in
                var bytes = Data(count: maximum)
                let count = bytes.withUnsafeMutableBytes {
                    marquee_remote_read(descriptor.fd, $0.bindMemory(to: UInt8.self).baseAddress, maximum)
                }
                guard count > 0 else { done.resume(throwing: StreamError.closed); return }
                bytes.count = count
                done.resume(returning: bytes)
            }
        }
    }

    func close() {
        writes.withLock { state in
            guard !state.closed else { return }
            state.closed = true
            // Wake a blocked reader immediately; let an already queued protocol reply or bye finish.
            marquee_remote_stop_reading(descriptor.fd)
            writer.async { [descriptor] in marquee_remote_shutdown(descriptor.fd) }
        }
    }

    static func pointerOutput(host: String, port: UInt16, grant: UDPGrant) async -> (@Sendable (PointerReport) -> Void)? {
        let fd = await dial(host, port, datagram: true)
        guard fd >= 0 else { return nil }
        let descriptor = Descriptor(fd)
        let sealer = Mutex(DatagramSealer(grant: grant))
        return { report in
            if let packet = sealer.withLock({ try? $0.pointer(report) }) {
                _ = descriptor.send(packet, datagram: true)
            }
        }
    }

    static func wake(mac: String, host: String) async throws {
        guard let packet = WakeOnLAN.magicPacket(mac: mac) else { throw RemoteFailure("This TV didn’t provide a wake address.") }
        var sent = false
        for address in [host, "255.255.255.255"] {
            let fd = await dial(address, WakeOnLAN.port, datagram: true)
            guard fd >= 0 else { continue }
            sent = Descriptor(fd).send(packet, datagram: true) || sent
        }
        if !sent { throw StreamError.unreachable("Wake-on-LAN") }
    }
}
