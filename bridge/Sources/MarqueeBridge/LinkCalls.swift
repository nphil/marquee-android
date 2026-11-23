import Foundation
import LumaRemoteCore
import MarqueeCore

/// Linked phones for Kotlin (DESIGN §8.10). The shared Core owns everything on the wire: the pairing code,
/// the keys, the Noise handshakes, the frames and the messages. Kotlin owns what Android owns: the saved
/// phones, Bonjour (NsdManager), the screens, and what this phone shows and does as a driving phone.
///
/// A message crosses this boundary as the JSON text of the link's own wire form (`LinkMessage`), so Kotlin
/// reads and writes exactly what an iPhone does.
enum LinkCalls {
    static func handles(_ method: String) -> Bool { method.hasPrefix("link.") }
    static let shared = LinkEngine()

    static func handle(_ method: String, _ payload: Data, engine: LinkEngine = shared) async throws -> Data {
        let request = try JSONDecoder().decode(Request.self, from: payload)
        do {
            switch method {
            case "link.configure":
                guard let id = request.id else { throw invalid }
                await engine.configure(id: id, name: request.name ?? "", phones: request.phones ?? [])
                return try Reply.ok(Empty())
            case "link.name":
                await engine.rename(request.name ?? "")
                return try Reply.ok(Empty())
            case "link.listen":
                return try Reply.ok(Listening(port: try await engine.listen(loopback: request.loopback ?? false)))
            case "link.unlisten":
                await engine.unlisten()
                return try Reply.ok(Empty())
            case "link.seen":
                await engine.see(request.peers ?? [])
                return try Reply.ok(Empty())
            case "link.parse":
                let invite = try request.invite()
                return try Reply.ok(Code(id: invite.id, name: invite.name))
            case "link.invite.open":
                let invite = try await engine.openInvite()
                return try Reply.ok(Shown(url: invite.url.absoluteString, id: invite.id, name: invite.name))
            case "link.invite.close":
                await engine.closeInvite()
                return try Reply.ok(Empty())
            case "link.join":
                guard let host = request.host, let port = request.port, port > 0, !host.isEmpty else { throw invalid }
                return try Reply.ok(try await engine.join(try request.invite(), host: host, port: port))
            case "link.send":
                guard let peer = request.peer, let text = request.message,
                      let message = LinkMessage.decode(Data(text.utf8)) else { throw invalid }
                try await engine.send(message, to: peer)
                return try Reply.ok(Empty())
            case "link.status":
                guard let status = request.status else { throw invalid }
                await engine.publish(status)
                return try Reply.ok(Empty())
            case "link.control":
                await engine.control(request.peer)
                return try Reply.ok(Empty())
            case "link.forget":
                guard let peer = request.peer else { throw invalid }
                await engine.forget(peer, unlink: request.unlink ?? false)
                return try Reply.ok(Empty())
            case "link.reset":
                await engine.reset()
                return try Reply.ok(Empty())
            case "link.event":
                return try Reply.ok(await engine.nextEvents())
            default:
                throw BridgeError.unknownMethod(method)
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as RemoteFailure {
            throw error
        } catch BridgeError.unknownMethod(let name) {
            throw BridgeError.unknownMethod(name)
        } catch {
            throw RemoteFailure("The linked phone couldn’t complete that action.")
        }
    }

    static let invalid = RemoteFailure("That linked-phone request is incomplete.")

    /// A linked phone as Kotlin keeps it; `key` is base64 in JSON.
    struct Phone: Codable, Sendable, Equatable {
        let id: LinkID
        let key: Data
    }

    /// A phone Bonjour found: where to call it.
    struct Peer: Decodable, Sendable {
        let id: LinkID
        let host: String
        let port: UInt16
    }

    struct Request: Decodable {
        let id: LinkID?
        let name: String?
        let phones: [Phone]?
        let peers: [Peer]?
        let peer: LinkID?
        let url: String?
        let host: String?
        let port: UInt16?
        let message: String?
        let status: LinkStatus?
        let unlink: Bool?
        let loopback: Bool?

        func invite() throws -> LinkInvite {
            guard let url, let invite = LinkInvite(string: url) else { throw RemoteFailure("That isn’t a Marquee link code.") }
            return invite
        }
    }

    struct Listening: Encodable { let port: UInt16 }
    struct Code: Encodable { let id: LinkID; let name: String }
    struct Shown: Encodable { let url: String; let id: LinkID; let name: String }
}

/// A phone that just linked with this one, with the key both derived. Kotlin keeps it.
struct LinkedPeer: Encodable, Sendable {
    let id: LinkID
    let name: String
    let key: Data
}

struct LinkEvent: Encodable, Sendable {
    /// `connected`, `disconnected`, `message`, `paired`, `listenerFailed`.
    var type: String
    var peer: LinkID?
    /// The JSON text of a `LinkMessage`.
    var message: String?
    var phone: LinkedPeer?
}

/// The running link: the listener, the calls to phones Bonjour found, the open sessions and the code on show.
/// This is `PhoneLink`'s networking, moved off the main actor and onto the Core's byte streams.
actor LinkEngine {
    /// A handshake, or the scanning phone's whole pairing, that takes longer has failed.
    private static let deadline: Duration = .seconds(6)
    private static let pairingDeadline: Duration = .seconds(10)
    /// Every connected phone sends at least every `LinkStatusPacer.refresh`; three missed means it went.
    private static let silence = 3 * LinkStatusPacer.refresh
    private static let tickInterval: Duration = .milliseconds(250)
    private static let maxQueued = 512
    private static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    private struct Connection {
        let channel: LinkChannel
        /// The phone that called, which decides which of two crossing calls stays.
        let caller: LinkID
        /// Tells this connection's late events from a newer one's.
        let token: Int
        var heardAt: TimeInterval
        var pacer = LinkStatusPacer()
        var clock = ClockOffset()
        var pings = 0
        var nextPing: TimeInterval = 0
        var reader: Task<Void, Never>?
    }

    private var me: LinkID?
    private var name = ""
    private var phones: [LinkID: Data] = [:]
    private var running = false
    private var listener: LinkListener?
    private var ticker: Task<Void, Never>?
    private var nearby: [LinkID: LinkCalls.Peer] = [:]
    private var links: [LinkID: Connection] = [:]
    private var calls: [LinkID: Task<Void, Never>] = [:]
    private var failures: [LinkID: Int] = [:]
    private var tokens = 0
    private var invite: LinkInvite?
    private var inviteSpent = false
    private var status = LinkStatus()
    private var controlling: LinkID?
    private var queued: [LinkEvent] = []
    private var waiter: CheckedContinuation<[LinkEvent], Never>?

    // MARK: Settings

    func configure(id: LinkID, name: String, phones: [LinkCalls.Phone]) {
        me = id
        self.name = LinkName.clean(name)
        self.phones = Dictionary(phones.map { ($0.id, $0.key) }, uniquingKeysWith: { _, new in new })
        for peer in Array(links.keys) where self.phones[peer] == nil { disconnect(peer) }
        for peer in self.phones.keys { callIfNeeded(peer) }
    }

    func rename(_ name: String) {
        let cleaned = LinkName.clean(name)
        guard cleaned != self.name else { return }
        self.name = cleaned
        for connection in links.values { connection.channel.send(.hello(name: cleaned)) }
    }

    /// Unlinks a phone: a connected one hears it first.
    func forget(_ peer: LinkID, unlink: Bool) {
        if unlink { links[peer]?.channel.send(.unlink) }
        phones[peer] = nil
        failures[peer] = nil
        nearby[peer] = nil
        calls.removeValue(forKey: peer)?.cancel()
        disconnect(peer)
        if controlling == peer { controlling = nil }
    }

    /// Delete All Local Data: every connected phone hears the unlink, and this phone's links are gone.
    func reset() {
        for connection in links.values { connection.channel.send(.unlink) }
        stop()
        me = nil
        name = ""
        phones = [:]
        invite = nil
        inviteSpent = false
        status = LinkStatus()
        controlling = nil
    }

    /// The phone this one is the remote for: its pings settle the clock that stamps each input.
    func control(_ peer: LinkID?) {
        controlling = peer
        if let peer { links[peer]?.pings = 0; links[peer]?.nextPing = 0 }
    }

    // MARK: Running

    /// Starts answering calls and calling the phones Bonjour finds. Returns the port to advertise.
    func listen(loopback: Bool) throws -> UInt16 {
        guard me != nil else { throw LinkCalls.invalid }
        if let listener { return listener.port }
        let listener = LinkListener(loopback: loopback, accepted: { [weak self] stream in
            Task { await self?.answer(stream) }
        }, failed: { [weak self] in
            Task { await self?.listenerFailed() }
        })
        guard let listener else { throw RemoteFailure("Marquee couldn’t listen for linked phones.") }
        self.listener = listener
        running = true
        if ticker == nil {
            ticker = Task { [weak self] in
                while !Task.isCancelled {
                    guard let self else { return }
                    await tick()
                    try? await Task.sleep(for: Self.tickInterval)
                }
            }
        }
        for peer in phones.keys { callIfNeeded(peer) }
        return listener.port
    }

    func unlisten() {
        stop()
    }

    private func stop() {
        guard running else { return }
        running = false
        listener?.stop()
        listener = nil
        ticker?.cancel()
        ticker = nil
        for call in calls.values { call.cancel() }
        calls = [:]
        failures = [:]
        nearby = [:]
        invite = nil
        for peer in Array(links.keys) { disconnect(peer) }
    }

    private func listenerFailed() {
        guard running else { return }
        listener = nil
        emit(LinkEvent(type: "listenerFailed"))
        stop()
    }

    /// Phones Bonjour sees on this network now, replacing the last list.
    func see(_ peers: [LinkCalls.Peer]) {
        nearby = Dictionary(peers.map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
        for peer in phones.keys { callIfNeeded(peer) }
    }

    // MARK: Pairing

    /// Opens the code Show Link Code shows. It lasts while it shows and links one phone.
    func openInvite() throws -> LinkInvite {
        guard let me, running else { throw LinkCalls.invalid }
        let invite = LinkInvite(id: me, name: name)
        self.invite = invite
        inviteSpent = false
        return invite
    }

    func closeInvite() {
        invite = nil
    }

    /// Links with the phone whose code was scanned: this phone calls it at `host`, both prove they hold the
    /// code's secret, and each adds a share to the key they keep.
    func join(_ invite: LinkInvite, host: String, port: UInt16) async throws -> LinkedPeer {
        guard let me else { throw RemoteFailure("Marquee couldn’t read its keys.") }
        guard invite.id != me else { throw RemoteFailure("That is this phone’s own code. Scan it with the other phone.") }
        let mine = LinkIntroduction(id: me, name: name, share: LinkKeys.randomBytes(LinkKeys.shareLength))
        let preamble = LinkPreamble(mode: .pairing, caller: me)
        let psk = LinkKeys.pairingKey(secret: invite.secret)
        let inviter: LinkIntroduction
        do {
            let stream = try await RemoteSocket.connect(.host(host, port: port))
            inviter = try await withDeadline(Self.pairingDeadline, onTimeout: { stream.close() }) {
                let channel = try await LinkTransport.call(stream, preamble: preamble, psk: psk)
                defer { channel.close() }
                channel.send(.join(mine))
                guard case .welcome(let inviter) = try await channel.receive(), inviter.id == invite.id,
                      inviter.share.count == LinkKeys.shareLength else { throw StreamError.closed }
                return inviter
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw RemoteFailure("Couldn’t reach \(invite.name). Keep both phones on the same Wi-Fi, with Marquee open on both and the code still showing.")
        }
        let key = LinkKeys.linkKey(secret: invite.secret, inviter: inviter.id, inviterShare: inviter.share, guest: me, guestShare: mine.share)
        let named = LinkName.clean(inviter.name)
        return add(id: inviter.id, name: named.isEmpty ? invite.name : named, key: key)
    }

    /// The other half of pairing, on the phone showing the code.
    private func answerPairing(_ stream: any ByteStream, preamble: LinkPreamble) async {
        guard let invite, !inviteSpent, let me else { return stream.close() }
        let psk = LinkKeys.pairingKey(secret: invite.secret)
        let channel = try? await withDeadline(Self.deadline, onTimeout: { stream.close() }) {
            try await LinkTransport.answer(stream, preamble: preamble, psk: psk)
        }
        guard let channel else { return stream.close() }
        // Only a phone that read the code gets here, and only the first one links.
        guard self.invite == invite, !inviteSpent else { return channel.close() }
        inviteSpent = true
        let mine = LinkIntroduction(id: me, name: name, share: LinkKeys.randomBytes(LinkKeys.shareLength))
        let guest = try? await withDeadline(Self.deadline, onTimeout: { stream.close() }) {
            guard case .join(let guest) = try await channel.receive(), guest.id == preamble.caller,
                  guest.share.count == LinkKeys.shareLength else { throw StreamError.closed }
            channel.send(.welcome(mine))
            return guest
        }
        channel.close()
        guard let guest else {
            inviteSpent = false
            return
        }
        let key = LinkKeys.linkKey(secret: invite.secret, inviter: me, inviterShare: mine.share, guest: guest.id, guestShare: guest.share)
        let named = LinkName.clean(guest.name)
        let phone = add(id: guest.id, name: named.isEmpty ? "Phone" : named, key: key)
        emit(LinkEvent(type: "paired", peer: phone.id, phone: phone))
    }

    /// A newly linked phone; linking a phone again replaces its old key.
    private func add(id: LinkID, name: String, key: Data) -> LinkedPeer {
        disconnect(id)
        failures[id] = nil
        phones[id] = key
        callIfNeeded(id)
        return LinkedPeer(id: id, name: name, key: key)
    }

    // MARK: Sessions

    private func callIfNeeded(_ peer: LinkID) {
        guard running, let me, nearby[peer] != nil, phones[peer] != nil, links[peer] == nil, calls[peer] == nil else { return }
        let wait = failures[peer].map { LinkDialing.retryDelay(failures: $0) } ?? LinkDialing.delay(me: me, peer: peer)
        calls[peer] = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled else { return }
            await self?.call(peer, as: me)
        }
    }

    /// Opens a session with a linked phone Bonjour found. A task cancelled on the way leaves `calls` to
    /// whoever cancelled it.
    private func call(_ peer: LinkID, as me: LinkID) async {
        guard let key = phones[peer], let address = nearby[peer], running, links[peer] == nil else {
            calls[peer] = nil
            return
        }
        let preamble = LinkPreamble(mode: .session, caller: me)
        let psk = LinkKeys.sessionKey(linkKey: key)
        let channel = try? await Self.dial(address, preamble: preamble, psk: psk)
        guard !Task.isCancelled else {
            channel?.close()
            return
        }
        calls[peer] = nil
        guard let channel else {
            failures[peer, default: 0] += 1
            callIfNeeded(peer)
            return
        }
        failures[peer] = nil
        connected(channel, to: peer, caller: me)
    }

    /// A call from another phone: a linked one opening a session, or one pairing with the code on show.
    private func answer(_ stream: any ByteStream) {
        guard running else { return stream.close() }
        Task { [weak self] in
            let preamble = try? await withDeadline(Self.deadline, onTimeout: { stream.close() }) {
                try await LinkTransport.preamble(stream)
            }
            guard let preamble, let self else { return stream.close() }
            switch preamble.mode {
            case .session: await answerSession(stream, preamble: preamble)
            case .pairing: await answerPairing(stream, preamble: preamble)
            }
        }
    }

    /// A phone this one does not know (never linked, or unlinked here) gets no answer.
    private func answerSession(_ stream: any ByteStream, preamble: LinkPreamble) async {
        guard running, let key = phones[preamble.caller] else { return stream.close() }
        let psk = LinkKeys.sessionKey(linkKey: key)
        let channel = try? await withDeadline(Self.deadline, onTimeout: { stream.close() }) {
            try await LinkTransport.answer(stream, preamble: preamble, psk: psk)
        }
        guard let channel else { return stream.close() }
        connected(channel, to: preamble.caller, caller: preamble.caller)
    }

    private func connected(_ channel: LinkChannel, to peer: LinkID, caller: LinkID) {
        guard running, phones[peer] != nil else { return channel.close() }
        if let existing = links[peer] {
            guard LinkDialing.keepsNew(existingCaller: existing.caller, newCaller: caller) else { return channel.close() }
            disconnect(peer)
        }
        calls.removeValue(forKey: peer)?.cancel()
        tokens += 1
        let token = tokens
        var connection = Connection(channel: channel, caller: caller, token: token, heardAt: Self.now)
        connection.reader = Task { [weak self] in
            while let message = try? await channel.receive() {
                await self?.received(message, from: peer, token: token)
            }
            await self?.ended(peer, token: token)
        }
        links[peer] = connection
        channel.send(.hello(name: name))
        emit(LinkEvent(type: "connected", peer: peer))
        sendStatus()
    }

    private func disconnect(_ peer: LinkID) {
        guard let connection = links.removeValue(forKey: peer) else { return }
        connection.reader?.cancel()
        connection.channel.close()
        emit(LinkEvent(type: "disconnected", peer: peer))
    }

    private func ended(_ peer: LinkID, token: Int) {
        guard links[peer]?.token == token else { return }
        disconnect(peer)
        callIfNeeded(peer)
    }

    private func received(_ message: LinkMessage, from peer: LinkID, token: Int) {
        guard links[peer]?.token == token else { return }
        links[peer]?.heardAt = Self.now
        switch message {
        case .ping(let sent):
            links[peer]?.channel.send(.pong(sent: sent, host: Self.now))
        case .pong(let sent, let host):
            links[peer]?.clock.add(sent: sent, remote: host, received: Self.now)
        case .join, .welcome:
            break
        case .unlink:
            forward(message, from: peer)
            forget(peer, unlink: false)
        case .hello, .status, .input, .command, .cue:
            forward(message, from: peer)
        }
    }

    private func forward(_ message: LinkMessage, from peer: LinkID) {
        guard let json = try? message.encoded() else { return }
        emit(LinkEvent(type: "message", peer: peer, message: String(decoding: json, as: UTF8.self)))
    }

    /// One message to a connected phone. An input without a time is stamped with now, by that phone's clock
    /// once its answers to this phone's pings have told the two apart.
    func send(_ message: LinkMessage, to peer: LinkID) throws {
        guard let connection = links[peer] else { throw RemoteFailure("That phone isn’t connected.") }
        if case .input(let input, nil) = message {
            connection.channel.send(.input(input, at: connection.clock.hostTime(Self.now)))
        } else {
            connection.channel.send(message)
        }
    }

    private func tick() {
        let now = Self.now
        for (peer, connection) in links where now - connection.heardAt > Self.silence {
            ended(peer, token: connection.token)
        }
        // A few quick pings settle the offset; after that it only needs keeping up with drift.
        if let peer = controlling, var connection = links[peer], now >= connection.nextPing {
            connection.channel.send(.ping(sent: now))
            connection.pings += 1
            connection.nextPing = now + (connection.pings < 6 ? 0.25 : 3)
            links[peer] = connection
        }
        sendStatus()
    }

    /// What this phone shows now, for each connected phone that needs it (`LinkStatusPacer`).
    func publish(_ status: LinkStatus) {
        self.status = status.limited()
        sendStatus()
    }

    /// A call to a phone at the address Bonjour gave; a cancelled call closes the stream at once.
    private static func dial(_ address: LinkCalls.Peer, preamble: LinkPreamble, psk: Data) async throws -> LinkChannel {
        let stream = try await RemoteSocket.connect(.host(address.host, port: address.port))
        return try await withTaskCancellationHandler {
            try await withDeadline(deadline, onTimeout: { stream.close() }) {
                try await LinkTransport.call(stream, preamble: preamble, psk: psk)
            }
        } onCancel: {
            stream.close()
        }
    }

    private func sendStatus() {
        guard !links.isEmpty else { return }
        let now = Self.now
        for peer in Array(links.keys) {
            guard links[peer]?.pacer.shouldSend(status, now: now) == true else { continue }
            links[peer]?.channel.send(.status(status))
        }
    }

    // MARK: Events

    private func emit(_ event: LinkEvent) {
        if let waiter {
            self.waiter = nil
            waiter.resume(returning: queued + [event])
            queued = []
        } else {
            queued.append(event)
            if queued.count > Self.maxQueued { queued.removeFirst(queued.count - Self.maxQueued) }
        }
    }

    /// Everything that happened since the last call, waiting for the first thing when nothing has. A call
    /// that is cancelled, or overtaken by another, returns nothing.
    func nextEvents() async -> [LinkEvent] {
        if !queued.isEmpty {
            let events = queued
            queued = []
            return events
        }
        waiter?.resume(returning: [])
        waiter = nil
        guard !Task.isCancelled else { return [] }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { waiter = $0 }
        } onCancel: {
            Task { await self.cancelWaiter() }
        }
    }

    private func cancelWaiter() {
        waiter?.resume(returning: [])
        waiter = nil
    }
}
