import Foundation
import LumaRemoteCore

struct RemoteFailure: Error {
    let message: String
    init(_ message: String) { self.message = message }

    static func explain(_ error: any Error) -> Self {
        switch error {
        case let failure as Self: failure
        case PairingError.wrongCode, HandshakeError.refused, HandshakeError.authenticationFailed:
            Self("The code didn’t work. Open pairing on the TV and check its current code.")
        case PairingError.refused(let code), TVLink.RequestError.failed(let code): refused(code)
        case PairingError.unexpectedReply: Self("The TV’s reply wasn’t compatible. Update Marquee or LumaTV.")
        case TVLink.RequestError.timedOut: Self("The TV didn’t reply in time.")
        case TVLink.RequestError.disconnected, TVLink.RequestError.notConnected, StreamError.closed:
            Self("Not connected to the TV.")
        case StreamError.timedOut, StreamError.unreachable: Self("Couldn’t reach the TV. Check that both devices are on the same network.")
        default: Self("The TV remote couldn’t complete that action.")
        }
    }

    static func refused(_ code: String) -> Self {
        switch code {
        case "standby": Self("The TV is in standby. Turn it on first.")
        case "no-field": Self("Open a text field on the TV first.")
        case "off": Self("The LumaTV plugin for this action is off.")
        case "not-found": Self("The TV couldn’t find that item.")
        case "rate": Self("Too many commands. Try again in a moment.")
        case "too-long": Self("That text is too long for the TV.")
        default: Self("The TV refused that command.")
        }
    }
}

/// Marquee's JNI messages are not the TV wire format. LumaRemoteCore owns all encoding, authentication and retries.
enum RemoteCalls {
    static func handles(_ method: String) -> Bool { method.hasPrefix("remote.") }
    private static let sessions = RemoteSessions()

    static func handle(_ method: String, _ payload: Data) async throws -> Data {
        let request = try JSONDecoder().decode(Request.self, from: payload)
        do {
            switch method {
            case "remote.invite":
                return try Reply.ok(Invite(request.invite()))
            case "remote.pair":
                let invite = try request.invite()
                var tv = try await Pairing.pair(address: .host(invite.host, port: invite.port), tv: invite.tv,
                                                code: invite.code, phone: request.phone(), connect: RemoteSocket.connect)
                try Task.checkCancellation()
                tv.service = request.service
                return try Reply.ok(tv)
            case "remote.open":
                guard let tv = request.tv, tv.key.count == 32, !tv.host.isEmpty, tv.port > 0 else { throw invalid }
                try await sessions.open(try request.sessionID(), tv: tv, phone: request.phone())
                return try Reply.ok(Empty())
            case "remote.close":
                await sessions.close(try request.sessionID())
                return try Reply.ok(Empty())
            case "remote.wake":
                guard let tv = request.tv else { throw invalid }
                try await RemoteSocket.wake(mac: tv.mac, host: tv.host)
                return try Reply.ok(Empty())
            default:
                let session = try await sessions.get(request.sessionID())
                switch method {
                case "remote.event":
                    let snapshot = await session.snapshots.first { _ in true }
                    guard let snapshot else { throw CancellationError() }
                    return try Reply.ok(snapshot)
                case "remote.input":
                    try await session.send(request.messages())
                    return try Reply.ok(Empty())
                case "remote.action":
                    try await session.perform(request.tvAction())
                    return try Reply.ok(Empty())
                case "remote.list":
                    guard let kind = request.kind else { throw invalid }
                    return try Reply.ok(try await session.list(kind).map(RemoteListEntry.init))
                case "remote.pointer":
                    try await session.point(request)
                    return try Reply.ok(Empty())
                default: throw BridgeError.unknownMethod(method)
                }
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw RemoteFailure.explain(error)
        }
    }

    struct Invite: Encodable {
        let host: String
        let port: UInt16
        let id: String
        let code: String

        init(_ invite: PairingInvite) {
            host = invite.host
            port = invite.port
            id = invite.tv.description
            code = invite.code.characters
        }
    }

    struct Request: Decodable {
        let session: String?
        let tv: PairedTV?
        let url: URL?
        let id: TVID?
        let code: String?
        let host: String?
        let port: UInt16?
        let service: String?
        let name: String?
        let model: String?
        let app: String?
        let key: RemoteKey?
        let press: KeyAction?
        let text: String?
        let edit: String?
        let count: Int?
        let action: String?
        let item: ItemID?
        let level: Double?
        let kind: ListKind?
        let pointer: String?
        let x: Double?
        let y: Double?

        func sessionID() throws -> String {
            guard let session else { throw invalid }
            return session
        }

        func phone() throws -> PhoneInfo {
            guard let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let model, let app else { throw invalid }
            return PhoneInfo(name: name, model: model, app: app)
        }

        func invite() throws -> PairingInvite {
            let source: PairingInvite
            if let url {
                guard let invite = PairingInvite(url: url) else { throw RemoteFailure("That isn’t a LumaTV pairing link.") }
                source = invite
            } else {
                guard let host, let port, let id else { throw invalid }
                guard let code, let parsed = PairingCode(code) else { throw RemoteFailure("Enter the eight-character code shown on the TV.") }
                source = PairingInvite(host: host, port: port, tv: id, code: parsed)
            }
            guard source.port > 0, !source.host.isEmpty, !source.host.utf8.contains(0) else { throw invalid }
            return source
        }

        func messages() throws -> [ClientMessage] {
            if let key {
                let press = press ?? .tap
                guard (press == .tap || !key.isTapOnly),
                      (press != .down && press != .up) || key.isHoldable else { throw invalid }
                return [.key(key, press)]
            }
            if let text { return ClientMessage.textMessages(for: text) }
            let operation: EditOperation
            switch edit {
            case "backspace": operation = .backspace(min(max(count ?? 1, 1), 256))
            case "enter": operation = .enter
            case "tab": operation = .tab
            case "left": operation = .left
            case "right": operation = .right
            case "clear": operation = .clear
            case "hide": operation = .hide
            case "show": operation = .show
            default: throw invalid
            }
            return [.edit(operation)]
        }

        func tvAction() throws -> TVAction {
            switch action {
            case "home": .home
            case "quick": .quickSettings
            case "settings": .settings(page: nil)
            case "notifications": .notifications
            case "input": .inputPicker
            case "pictureoff": .pictureOff
            case "cameras": .cameras
            case "camera": .camera(try itemID())
            case "camera-close": .closeCamera
            case "guide": .guide
            case "tune": .tune(channel: try itemID())
            case "launch": .launch(app: try itemID())
            case "volume": .volume(level: try volumeLevel())
            default: throw invalid
            }
        }

        private func volumeLevel() throws -> Double {
            guard let level, level.isFinite else { throw invalid }
            return level
        }

        private func itemID() throws -> ItemID {
            guard let item else { throw invalid }
            return item
        }
    }

    static let invalid = RemoteFailure("That TV remote request is incomplete.")
}

private struct RemoteListEntry: Encodable {
    let id: ItemID
    let label: String
    let number: String?

    init(_ item: ListItem) {
        id = item.id
        label = item.label
        number = item.no
    }
}

private actor RemoteSessions {
    private var sessions: [String: RemoteSession] = [:]

    func open(_ id: String, tv: PairedTV, phone: PhoneInfo) async throws {
        guard sessions[id] == nil else { throw RemoteCalls.invalid }
        let session = RemoteSession(tv: tv, phone: phone)
        sessions[id] = session
        await session.start()
    }

    func get(_ id: String) throws -> RemoteSession {
        guard let session = sessions[id] else { throw TVLink.RequestError.notConnected }
        return session
    }

    func close(_ id: String) async {
        if let session = sessions.removeValue(forKey: id) { await session.stop() }
    }
}

actor RemoteSession {
    struct Snapshot: Encodable {
        var status = "connecting"
        var tv: PairedTV
        var standby = false
        var keyboard = false
        var privateText = false
        var keyboardPurpose: Int?
        var enter: Int?
        var volume: Double?
        var volumeMax: Double?
        var muted: Bool?
        var actions: [String] = []
        var pointer = false
        var notice: String?
        var noticeID = 0
    }

    nonisolated let snapshots: AsyncStream<Snapshot>
    private let output: AsyncStream<Snapshot>.Continuation
    private let link: TVLink
    private let pointer = PointerSender()
    private var reader: Task<Void, Never>?
    private var stopped = false
    private var state = TVState()
    private var snapshot: Snapshot

    init(tv: PairedTV, phone: PhoneInfo) {
        snapshot = Snapshot(tv: tv)
        link = TVLink(tv: tv, phone: phone, connect: RemoteSocket.connect)
        (snapshots, output) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1))
    }

    func start() async {
        guard !stopped else { return }
        output.yield(snapshot)
        reader = Task { [weak self, link] in
            for await event in link.events { await self?.handle(event) }
        }
        await link.start()
    }

    func stop() async {
        stopped = true
        reader?.cancel()
        reader = nil
        pointer.attach(nil)
        output.finish()
        await link.stop()
    }

    func send(_ messages: [ClientMessage]) throws {
        for message in messages where !link.send(message) { throw TVLink.RequestError.notConnected }
    }

    func perform(_ action: TVAction) async throws {
        guard state.allows(action) else { throw RemoteFailure("That action isn’t available on this TV.") }
        try await link.perform(action)
    }

    func list(_ kind: ListKind) async throws -> [ListItem] {
        let action: String = switch kind { case .apps: "launch"; case .channels: "tune"; case .cameras: "camera" }
        guard state.actions.contains(action) else { throw RemoteFailure("That list isn’t available on this TV.") }
        return try await link.list(kind)
    }

    func point(_ request: RemoteCalls.Request) throws {
        guard snapshot.status == "connected", snapshot.pointer, !snapshot.standby else { return }
        switch request.pointer {
        case "begin": pointer.set(.engaged, true)
        case "end":
            pointer.release(.left)
            pointer.set(.scrolling, false)
            pointer.set(.engaged, false)
        case "move": pointer.move(dx: try delta(request.x), dy: try delta(request.y))
        case "scroll":
            pointer.set(.scrolling, true)
            pointer.scroll(dx: try delta(request.x), dy: try delta(request.y))
        case "click": pointer.click(.left)
        case "down": pointer.press(.left)
        case "up": pointer.release(.left)
        case "centre": pointer.centre()
        default: throw RemoteCalls.invalid
        }
    }

    private func delta(_ value: Double?) throws -> Double {
        guard let value, value.isFinite else { throw RemoteCalls.invalid }
        return min(max(value, -4_096), 4_096)
    }

    private func handle(_ event: TVLink.Event) async {
        guard !stopped else { return }
        switch event {
        case .status(let status):
            switch status {
            case .connecting: snapshot.status = "connecting"
            case .connected: snapshot.status = "connected"
            case .offline: snapshot.status = "offline"
            case .incompatible: snapshot.status = "incompatible"
            case .rejected, .revoked: snapshot.status = "unpaired"
            case .stopped: return
            }
            if status != .connected {
                pointer.attach(nil)
                snapshot.pointer = false
            }
        case .reached(let host, let port):
            snapshot.tv.host = host
            snapshot.tv.port = port
        case .welcome(let welcome):
            guard welcome.tv.id == snapshot.tv.id, welcome.v == 1 else {
                snapshot.status = "incompatible"
                output.yield(snapshot)
                await stop()
                return
            }
            state = TVState()
            apply(welcome.state)
            snapshot.tv.name = welcome.tv.name
            if !welcome.tv.mac.isEmpty { snapshot.tv.mac = welcome.tv.mac }
            let send = await RemoteSocket.pointerOutput(host: snapshot.tv.host, port: snapshot.tv.port, grant: welcome.udp)
            guard !stopped else { return }
            pointer.attach(send)
            snapshot.pointer = send != nil
        case .state(let update): apply(update)
        case .refused(let code, _):
            snapshot.notice = RemoteFailure.refused(code).message
            snapshot.noticeID += 1
        }
        output.yield(snapshot)
    }

    private func apply(_ update: TVStateUpdate) {
        state.apply(update)
        snapshot.standby = state.power == .standby
        snapshot.keyboard = state.ime.active
        snapshot.privateText = state.ime.hiddenText || state.ime.sensitive
        snapshot.keyboardPurpose = state.ime.purpose
        snapshot.enter = state.ime.enter
        snapshot.volume = state.volume?.level
        snapshot.volumeMax = state.volume?.max
        snapshot.muted = state.volume?.muted
        snapshot.actions = state.actions.sorted()
    }
}
