import Foundation
import MarqueeCore

/// What came of one request to the ntfy server.
public struct NtfyOutcome: Sendable, Equatable {
    /// Nil when the server did not answer.
    public var answer: NtfyAnswer?
    public var status: Int?
    public var retryAfter: String?
    /// Why there was no answer.
    public var error: String?

    public init(answer: NtfyAnswer? = nil, status: Int? = nil, retryAfter: String? = nil, error: String? = nil) {
        self.answer = answer
        self.status = status
        self.retryAfter = retryAfter
        self.error = error
    }
}

/// Sends one request to the ntfy server and calls back with what came of it. The live log, Test Connection
/// and reports share it; the tests swap it for a fake server.
public typealias NtfySend = @Sendable (PlexRequest, @escaping @Sendable (NtfyOutcome) -> Void) -> Void

public enum NtfyClient {
    /// Sends over `transport`, the app's own HTTP client. Not reported as a request of its own, which would
    /// make every message the cause of the next.
    public static func send(through transport: any PlexTransport) -> NtfySend {
        { request, completion in
            Task {
                do {
                    let response = try await transport.send(request)
                    completion(NtfyOutcome(
                        answer: NtfyAnswer(status: response.status, body: response.body), status: response.status,
                        retryAfter: response.header("Retry-After")
                    ))
                } catch {
                    completion(NtfyOutcome(error: describe(error)))
                }
            }
        }
    }

    static func post(_ request: PlexRequest, through send: NtfySend) async -> NtfyOutcome {
        await withCheckedContinuation { continuation in
            send(request) { continuation.resume(returning: $0) }
        }
    }

    private static func describe(_ error: any Error) -> String {
        switch error {
        case let failure as NetworkFailure: "\(failure)"
        case is CancellationError: "cancelled"
        default: DiagFormat.describe(error)
        }
    }
}

/// Sends the live log to the ntfy topic (DESIGN §8.12). Lines gather for up to ten seconds; a warning or an
/// error sends them within a second. Each message stays under ntfy's 4096-byte limit. Unsent lines wait on
/// disk, up to 1 MB, until the server takes them. Thirty messages at once, then one per ten seconds, stays
/// inside the server's limit of 60 at once and one per five seconds. Confined to the log's executor, which is
/// what makes it safe to hand to that executor's closures.
final class NtfySender: @unchecked Sendable {
    enum State: Sendable, Equatable {
        case off
        case sending(lastSent: Date?)
        /// The last try got no answer, or a 429 or 5xx.
        case retrying(status: Int?)
        /// A 401 or 403: nothing more is sent until the settings change.
        case refused(NtfyAnswer)

        var word: String {
            switch self {
            case .off: "off"
            case .sending: "sending"
            case .retrying: "retrying"
            case .refused: "refused"
            }
        }
    }

    static let interval: TimeInterval = 10
    static let promptDelay: TimeInterval = 1
    private static let maxPending = 1_000_000

    private let executor: any DiagExecutor
    private let launch: String
    private let transport: NtfySend
    private let file: URL
    /// Unknown until the settings have said; lines wait meanwhile.
    private var setup: DiagLog.Setup?
    private var pending: [DiagLine] = []
    private var pendingBytes = 0
    private var dropped = 0
    private var writer: FileHandle?
    private var bucket: TokenBucket
    private var backoff = DiagBackoff()
    private var inFlight = false
    private var paused = false
    private var prompting = false
    private var timer: (any DiagTimer)?
    private var sequence = 0
    private var waiters: [@Sendable () -> Void] = []
    private(set) var state = State.off {
        didSet {
            guard state.word != oldValue.word else { return }
            var status: Int?
            if case .retrying(let code) = state { status = code }
            DiagHub.emit(.net, "ntfy.state", ["state": state.word, "status": status, "waiting": pending.count], level: .notice)
        }
    }

    init(folder: URL, executor: any DiagExecutor, launch: String, transport: @escaping NtfySend) {
        self.executor = executor
        self.launch = launch
        self.transport = transport
        file = folder.appendingPathComponent("pending.queue")
        bucket = TokenBucket(capacity: 30, refill: 10, now: executor.now)
    }

    var waiting: Int { pending.count }

    private var isLive: Bool {
        setup.map { $0.live && $0.config != nil } ?? false
    }

    /// Takes back what an earlier launch could not send.
    func restore() {
        guard let data = try? Data(contentsOf: file) else { return }
        for row in String(decoding: data, as: UTF8.self).split(separator: "\n") {
            guard let tab = row.firstIndex(of: "\t"), let raw = Int(row[..<tab]), let level = DiagLevel(rawValue: raw) else { continue }
            let text = String(row[row.index(after: tab)...])
            pending.append(DiagLine(text, level: level))
            pendingBytes += text.utf8.count + 1
        }
    }

    func configure(_ new: DiagLog.Setup) {
        let old = setup
        setup = new
        guard isLive else {
            cancelTimer()
            clear()
            state = .off
            return
        }
        // A refusal holds until the settings are saved again or a test reaches the server.
        paused = false
        if old?.config != new.config { backoff = DiagBackoff() }
        switch state {
        case .off, .refused: state = .sending(lastSent: nil)
        case .sending, .retrying: break
        }
        send()
        armTimer()
    }

    func enqueue(_ line: DiagLine) {
        if setup != nil, !isLive { return }
        pending.append(line)
        pendingBytes += line.text.utf8.count + 1
        if pendingBytes > Self.maxPending {
            trim()
        } else {
            persist(line)
        }
        armTimer()
        if line.level >= .warning, isLive { prompt() }
    }

    /// Sends now; `done` runs once nothing waits, or nothing more can go.
    func flush(_ done: @escaping @Sendable () -> Void) {
        waiters.append(done)
        send()
    }

    func clear() {
        pending = []
        pendingBytes = 0
        dropped = 0
        try? writer?.close()
        writer = nil
        try? FileManager.default.removeItem(at: file)
        release()
    }

    private func prompt() {
        guard !prompting else { return }
        prompting = true
        _ = executor.run(after: Self.promptDelay) { [self] in
            prompting = false
            send()
        }
    }

    /// One timer, running only while something waits: a quiet log costs no wake-ups. A tick that sends
    /// nothing, because the server is backing off or the bucket is empty, tries again a tick later; one that
    /// sends leaves the next to the answer.
    private func armTimer() {
        guard timer == nil, isLive, !paused, !pending.isEmpty else { return }
        timer = executor.run(after: Self.interval) { [self] in
            timer = nil
            if !send() { armTimer() }
        }
    }

    private func cancelTimer() {
        timer?.cancel()
        timer = nil
    }

    /// Starts one message if one may go now; false when nothing was started.
    @discardableResult
    private func send() -> Bool {
        guard !inFlight else { return false }
        let now = executor.now
        guard isLive, !paused, !pending.isEmpty, let setup, let config = setup.config, backoff.ready(now: now),
              bucket.take(now: now) else {
            release()
            return false
        }
        sequence += 1
        var header = "launch=\(launch) seq=\(sequence)"
        if dropped > 0 { header += " dropped=\(dropped)" }
        let limit = DiagPacker.messageLimit - header.utf8.count - 1
        let count = fitting(limit)
        guard let message = DiagPacker.pack(Array(pending.prefix(count)), limit: limit).first else {
            release()
            return false
        }
        let request = config.publish(NtfyMessage(
            title: setup.title, text: header + "\n" + message.text,
            priority: NtfyPriority.for(message.level), tags: NtfyPriority.tags(for: message.level)
        ))
        inFlight = true
        transport(request) { [self] outcome in
            executor.run { [self] in finished(outcome, count: count) }
        }
        return true
    }

    /// How many waiting lines fit one message; at least one, which the packer cuts if it is too long.
    private func fitting(_ limit: Int) -> Int {
        var bytes = 0
        var count = 0
        for line in pending {
            bytes += line.text.utf8.count + (count > 0 ? 1 : 0)
            if bytes > limit, count > 0 { break }
            count += 1
        }
        return count
    }

    private func finished(_ outcome: NtfyOutcome, count: Int) {
        inFlight = false
        let now = executor.now
        switch outcome.answer {
        case .accepted:
            backoff.succeeded()
            drop(count)
            dropped = 0
            state = .sending(lastSent: now)
            send()
        case .needsToken, .refused:
            paused = true
            state = .refused(outcome.answer ?? .refused)
            release()
        case .rateLimited, .serverError, nil:
            backoff.failed(status: outcome.status, retryAfter: outcome.retryAfter, now: now)
            state = .retrying(status: outcome.status)
            release()
        case .attachmentRefused, .unexpected:
            // The server will never take this message as it is; the rest still go.
            DiagHub.emit(.net, "ntfy.rejected", ["status": outcome.status, "lines": count], level: .notice)
            drop(count)
            send()
        }
        armTimer()
    }

    /// Runs the flush waiters once nothing more will go now.
    private func release() {
        guard !inFlight, !waiters.isEmpty else { return }
        let done = waiters
        waiters = []
        for waiter in done { waiter() }
    }

    private func drop(_ count: Int) {
        let gone = pending.prefix(count).reduce(0) { $0 + $1.text.utf8.count + 1 }
        pending.removeFirst(min(count, pending.count))
        pendingBytes -= gone
        rewrite()
    }

    /// Past 1 MB the oldest lines go, down to three quarters, and the next message says how many.
    private func trim() {
        var removed = 0
        var bytes = pendingBytes
        while bytes > Self.maxPending * 3 / 4, removed < pending.count {
            bytes -= pending[removed].text.utf8.count + 1
            removed += 1
        }
        pending.removeFirst(removed)
        pendingBytes = bytes
        dropped += removed
        rewrite()
    }

    private func persist(_ line: DiagLine) {
        if writer == nil {
            if !FileManager.default.fileExists(atPath: file.path) {
                _ = FileManager.default.createFile(atPath: file.path, contents: nil)
            }
            writer = try? FileHandle(forWritingTo: file)
            _ = try? writer?.seekToEnd()
        }
        try? writer?.write(contentsOf: Data("\(line.level.rawValue)\t\(line.text)\n".utf8))
    }

    private func rewrite() {
        try? writer?.close()
        writer = nil
        guard !pending.isEmpty else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        let text = pending.map { "\($0.level.rawValue)\t\($0.text)\n" }.joined()
        try? Data(text.utf8).write(to: file, options: .atomic)
    }
}
