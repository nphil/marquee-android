import Foundation
import MarqueeCore
import Synchronization
import Testing

@testable import MarqueeDiagnostics

/// Every test installs the global `DiagHub` sink and moves its threshold, so they run one at a time: each
/// suite is an extension of this one.
@Suite(.serialized) enum Diagnostics {}

/// The log's executor on a clock the test moves. Work runs in the caller, in order; a timer fires as the clock
/// passes it. Nothing here sleeps.
final class ManualExecutor: DiagExecutor, @unchecked Sendable {
    final class Timer: DiagTimer, @unchecked Sendable {
        let due: Date
        let order: Int
        let work: @Sendable () -> Void
        private let stopped = Mutex(false)

        init(due: Date, order: Int, work: @escaping @Sendable () -> Void) {
            self.due = due
            self.order = order
            self.work = work
        }

        var cancelled: Bool { stopped.withLock { $0 } }

        func cancel() {
            stopped.withLock { $0 = true }
        }
    }

    private struct State {
        var clock: Date
        var queue: [@Sendable () -> Void] = []
        var draining = false
        var timers: [Timer] = []
        var issued = 0
    }

    private let state: Mutex<State>

    /// 2023-11-14 22:13:20 UTC.
    init(start: Date = Date(timeIntervalSince1970: 1_700_000_000)) {
        state = Mutex(State(clock: start))
    }

    var now: Date { state.withLock { $0.clock } }

    /// Timers that have neither fired nor been cancelled.
    var scheduled: Int { state.withLock { $0.timers.filter { !$0.cancelled }.count } }

    func run(_ work: @escaping @Sendable () -> Void) {
        let drain = state.withLock { state -> Bool in
            state.queue.append(work)
            guard !state.draining else { return false }
            state.draining = true
            return true
        }
        guard drain else { return }
        while let next = state.withLock({ state -> (@Sendable () -> Void)? in
            guard !state.queue.isEmpty else {
                state.draining = false
                return nil
            }
            return state.queue.removeFirst()
        }) {
            next()
        }
    }

    func run(after delay: TimeInterval, _ work: @escaping @Sendable () -> Void) -> any DiagTimer {
        state.withLock { state in
            state.issued += 1
            let timer = Timer(due: state.clock.addingTimeInterval(delay), order: state.issued, work: work)
            state.timers.append(timer)
            return timer
        }
    }

    /// Moves the clock on, running each timer that falls due as the clock reaches it. A microsecond of slack
    /// absorbs the rounding of dates a billion seconds from 1970.
    func advance(by seconds: TimeInterval) {
        let target = now.addingTimeInterval(seconds)
        let limit = target.addingTimeInterval(0.000_001)
        while true {
            let due = state.withLock { state -> Timer? in
                let next = state.timers.filter { !$0.cancelled && $0.due <= limit }
                    .min { ($0.due, $0.order) < ($1.due, $1.order) }
                if let next {
                    state.timers.removeAll { $0 === next }
                    state.clock = max(state.clock, next.due)
                } else {
                    state.clock = max(state.clock, target)
                }
                return next
            }
            guard let due else { return }
            run(due.work)
        }
    }
}

/// A stand-in for the ntfy server: it keeps every request and answers as told, at once or when asked.
final class FakeNtfy: @unchecked Sendable {
    enum Behaviour {
        case answer(NtfyOutcome)
        /// A different answer for each request.
        case reply(@Sendable (PlexRequest) -> NtfyOutcome)
        /// No answer until `respond`.
        case hold
    }

    private struct State {
        var requests: [PlexRequest] = []
        var held: [@Sendable (NtfyOutcome) -> Void] = []
        var behaviour = Behaviour.answer(FakeNtfy.accepted)
    }

    static let accepted = NtfyOutcome(answer: .accepted, status: 200)
    static let unreachable = NtfyOutcome(error: "cannotConnect")

    static func status(_ code: Int, retryAfter: String? = nil, body: String = "") -> NtfyOutcome {
        NtfyOutcome(answer: NtfyAnswer(status: code, body: Data(body.utf8)), status: code, retryAfter: retryAfter)
    }

    private let state = Mutex(State())

    var requests: [PlexRequest] { state.withLock { $0.requests } }
    var count: Int { state.withLock { $0.requests.count } }

    func behave(_ behaviour: Behaviour) {
        state.withLock { $0.behaviour = behaviour }
    }

    func answer(_ outcome: NtfyOutcome) {
        behave(.answer(outcome))
    }

    /// Forgets the requests so far.
    func reset() {
        state.withLock { $0.requests = [] }
    }

    var send: NtfySend {
        { [self] request, completion in
            let behaviour = state.withLock { state -> Behaviour in
                state.requests.append(request)
                if case .hold = state.behaviour { state.held.append(completion) }
                return state.behaviour
            }
            switch behaviour {
            case .answer(let outcome): completion(outcome)
            case .reply(let reply): completion(reply(request))
            case .hold: break
            }
        }
    }

    /// Answers the oldest request that is being held.
    func respond(_ outcome: NtfyOutcome) {
        let completion = state.withLock { $0.held.isEmpty ? nil : $0.held.removeFirst() }
        completion?(outcome)
    }
}

/// What logcat was given.
final class LogcatRecorder: @unchecked Sendable {
    private let lines = Mutex<[(level: DiagLevel, line: String)]>([])

    var all: [(level: DiagLevel, line: String)] { lines.withLock { $0 } }

    var sink: DiagLog.Logcat {
        { [self] level, line in lines.withLock { $0.append((level, line)) } }
    }
}

/// A log on a manual clock and a fake server, in a folder of its own.
final class Harness: @unchecked Sendable {
    static let title = "Marquee 0.1.1 · Isabel’s tablet"
    static let device = "Isabel’s tablet"

    let folder: URL
    let executor: ManualExecutor
    let ntfy: FakeNtfy
    let logcat = LogcatRecorder()
    let log: DiagLog
    private let ownsFolder: Bool

    init(
        launch: String = "aaaaaa", offset: Int = 0, policy: DiagJournalPolicy = DiagJournalPolicy(), folder: URL? = nil,
        executor: ManualExecutor = ManualExecutor(), ntfy: FakeNtfy = FakeNtfy(), start: Bool = true
    ) {
        self.folder = folder ?? FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")
        ownsFolder = folder == nil
        self.executor = executor
        self.ntfy = ntfy
        DiagHub.setThreshold(.info)
        log = DiagLog(
            folder: self.folder, build: DiagLog.Build(version: "0.1.1", build: "7", commit: "abc1234"), launch: launch,
            born: executor.now, utcOffset: offset, policy: policy, executor: executor, send: ntfy.send,
            logcat: logcat.sink
        )
        if start { log.start() }
    }

    deinit {
        DiagHub.install(nil)
        DiagHub.setThreshold(.info)
        if ownsFolder { try? FileManager.default.removeItem(at: folder) }
    }

    static func config(token: String? = nil) -> NtfyConfig {
        NtfyConfig(server: URL(string: "http://127.0.0.1:2586")!, topic: "marquee-test", token: token)
    }

    /// Saves a server, the way the settings page does.
    func configure(live: Bool = true, token: String? = nil, saved: Bool = true) {
        log.configure(DiagLog.Setup(
            config: saved ? Self.config(token: token) : nil, live: live, title: Self.title, device: Self.device
        ))
    }

    /// Sends what configuring left waiting, so a test starts from a quiet log.
    func settle() {
        executor.advance(by: 10)
        ntfy.reset()
    }

    func note(_ name: String, _ level: DiagLevel = .info, _ category: DiagCategory = .app, _ fields: KeyValuePairs<String, Any?> = [:]) {
        DiagHub.emit(category, name, fields, level: level)
    }

    /// The journal's lines, without its `#` headers.
    func journalLines() async -> [String] {
        String(decoding: await log.journalData(), as: UTF8.self).split(separator: "\n").map(String.init).filter { !$0.hasPrefix("#") }
    }
}

extension PlexRequest {
    var text: String { String(decoding: body ?? Data(), as: UTF8.self) }

    func query(_ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
    }

    /// A live message's first line: `launch=aaaaaa seq=2`.
    var header: String { text.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? "" }

    /// A live message's lines after its header.
    var lines: [String] { Array(text.split(separator: "\n", omittingEmptySubsequences: false).dropFirst().map(String.init)) }
}
