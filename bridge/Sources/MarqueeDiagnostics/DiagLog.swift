import Foundation
import MarqueeCore
import Synchronization

/// The diagnostic log (DESIGN §8.12): what every part of the app does, in order, in logcat, in a journal on
/// the device and, while Diagnostic Logs sends live, on the ntfy topic. Values pass through `DiagRedactor`,
/// and a field named like a secret is never written. One executor owns the journal and the sender, so a caller
/// only pays for formatting its values.
public final class DiagLog: @unchecked Sendable {
    /// The build the log comes from, on journal headers and reports.
    public struct Build: Sendable, Equatable {
        public var version: String
        public var build: String
        public var commit: String

        public init(version: String, build: String, commit: String) {
            self.version = version
            self.build = build
            self.commit = commit
        }
    }

    /// Where the live log goes and how its messages are titled. Before the settings have said, lines wait.
    public struct Setup: Sendable, Equatable {
        public var config: NtfyConfig?
        public var live: Bool
        /// `Marquee 0.1.1 · Isabel’s tablet`: the title of every message from this device.
        public var title: String
        /// The device's name, for a report's file names and summary.
        public var device: String

        public init(config: NtfyConfig?, live: Bool, title: String, device: String) {
            self.config = config
            self.live = live
            self.title = title
            self.device = device
        }
    }

    /// Where the sender stands, for the settings page.
    public struct Status: Sendable, Equatable {
        /// `off`, `sending`, `retrying` or `refused`.
        public var state: String
        public var lastSent: Date?
        /// The status of the answer that made it retry, if one came.
        public var status: Int?
        /// For `refused`: `needsToken` for a 401, `refused` for a 403.
        public var refusal: String?
        public var waiting: Int
    }

    /// Takes each event's line, for logcat.
    public typealias Logcat = @Sendable (DiagLevel, String) -> Void

    /// The time of an event that was stamped before it reached Swift, such as one from Kotlin.
    @TaskLocal public static var stamped: Date?

    /// Six characters naming this launch on its journal files and live messages.
    public let launch: String
    public let build: Build
    let executor: any DiagExecutor
    let transport: NtfySend
    let born: Date
    private let logcat: Logcat?
    private let journal: DiagJournal
    private let sender: NtfySender
    private let playback = Mutex<String?>(nil)
    private let zone: Mutex<Zone>
    private let started = Mutex(false)
    // The executor's own.
    private var coalescer = DiagCoalescer()
    private var folding = false
    private(set) var setup: Setup?

    private struct Zone {
        var offset: Int
        var calendar: Calendar
    }

    /// - Parameters:
    ///   - born: When the process began; the `+12.34s` of every line counts from it.
    ///   - utcOffset: The device's offset from UTC in seconds, which Foundation cannot be trusted to know here.
    public init(
        folder: URL, build: Build, launch: String? = nil, born: Date? = nil, utcOffset: Int = 0,
        policy: DiagJournalPolicy = DiagJournalPolicy(), executor: any DiagExecutor, send: @escaping NtfySend,
        logcat: Logcat? = nil
    ) {
        let launch = launch ?? Self.newLaunchID()
        let calendar = Self.calendar(offset: utcOffset)
        self.launch = launch
        self.build = build
        self.executor = executor
        self.transport = send
        self.born = born ?? executor.now
        self.logcat = logcat
        zone = Mutex(Zone(offset: utcOffset, calendar: calendar))
        journal = DiagJournal(
            folder: folder, launch: launch, build: build, calendar: calendar, policy: policy,
            clock: { executor.now }
        )
        sender = NtfySender(folder: folder, executor: executor, launch: launch, transport: send)
    }

    static func newLaunchID() -> String {
        let alphabet = Array("0123456789abcdefghijklmnopqrstuvwxyz")
        return String((0..<6).map { _ in alphabet.randomElement() ?? "0" })
    }

    private static func calendar(offset: Int) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: offset) ?? .gmt
        return calendar
    }

    /// The calendar lines and file names are written in.
    var calendar: Calendar {
        zone.withLock { $0.calendar }
    }

    public func setUTCOffset(_ seconds: Int) {
        zone.withLock { zone in
            guard zone.offset != seconds else { return }
            zone.offset = seconds
            zone.calendar = Self.calendar(offset: seconds)
        }
    }

    /// Opens the journal, takes back what an earlier launch could not send, and takes events from `DiagHub`.
    public func start() {
        let first = started.withLock { started -> Bool in
            defer { started = true }
            return !started
        }
        guard first else { return }
        DiagHub.install { [self] level, category, name, fields in
            record(level, category, name, fields)
        }
        executor.run { [self] in
            sender.restore()
            if let previous = journal.open() { report(previous) }
        }
    }

    /// Opens a playback. Its id goes on every player, audio, subtitle and offer event until `endPlayback`.
    public func beginPlayback(_ id: String) {
        playback.withLock { $0 = id }
    }

    public func endPlayback() {
        playback.withLock { $0 = nil }
    }

    public func configure(_ setup: Setup) {
        executor.run { [self] in
            self.setup = setup
            sender.configure(setup)
        }
    }

    /// Where the app stands, for the next launch to tell whether this one ended cleanly.
    func setPhase(_ phase: DiagJournal.Phase) {
        let current = playback.withLock { $0 }
        executor.run { [self] in journal.mark(phase, playback: current) }
    }

    public func setPhase(named phase: String) {
        guard let phase = DiagJournal.Phase(rawValue: phase) else { return }
        setPhase(phase)
    }

    /// The app is closing: the marker is written, and a held repeat with it.
    public func end() async {
        await onExecutor { log in
            log.hold()
            log.journal.mark(.ended, playback: nil)
        }
    }

    /// Everything in the journal, oldest first, with any held repeat written out.
    public func journalData() async -> Data {
        await onExecutor { log in
            log.hold()
            return log.journal.allData()
        }
    }

    /// Sends what waits now and puts the journal on disk; returns once it is out or cannot go.
    public func flush() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            executor.run { [self] in
                hold()
                journal.sync()
                sender.flush { continuation.resume() }
            }
        }
    }

    /// The journal and anything waiting to be sent.
    public func erase() async {
        await onExecutor { log in
            log.journal.erase()
            log.sender.clear()
        }
    }

    public func status() async -> Status {
        await onExecutor { log in
            let waiting = log.sender.waiting
            switch log.sender.state {
            case .off: return Status(state: "off", lastSent: nil, status: nil, refusal: nil, waiting: waiting)
            case .sending(let lastSent): return Status(state: "sending", lastSent: lastSent, status: nil, refusal: nil, waiting: waiting)
            case .retrying(let status): return Status(state: "retrying", lastSent: nil, status: status, refusal: nil, waiting: waiting)
            case .refused(let answer):
                return Status(state: "refused", lastSent: nil, status: nil, refusal: answer == .needsToken ? "needsToken" : "refused", waiting: waiting)
            }
        }
    }

    func onExecutor<Value: Sendable>(_ work: @escaping @Sendable (DiagLog) -> Value) async -> Value {
        await withCheckedContinuation { continuation in
            executor.run { [self] in continuation.resume(returning: work(self)) }
        }
    }

    /// Resumes a live log a refused token had stopped, once a test has reached the server.
    func resume() {
        executor.run { [self] in
            guard let setup else { return }
            sender.configure(setup)
        }
    }

    private func record(_ level: DiagLevel, _ category: DiagCategory, _ name: String, _ fields: [DiagField]) {
        let wall = Self.stamped ?? executor.now
        let event = DiagEvent(
            wall: wall, uptime: max(0, wall.timeIntervalSince(born)), level: level, category: category, name: name,
            fields: fields, launch: launch, playback: category.followsPlayback ? playback.withLock { $0 } : nil
        )
        executor.run { [self] in write(event) }
    }

    /// Executor only.
    private func write(_ event: DiagEvent) {
        let calendar = self.calendar
        logcat?(event.level, event.line(in: calendar))
        let lines = coalescer.add(event, in: calendar)
        // A repeat is held; its count is written once the run ends or five seconds pass.
        if lines.isEmpty, !folding {
            folding = true
            _ = executor.run(after: 5) { [self] in
                folding = false
                hold()
            }
        }
        for line in lines { keep(line) }
    }

    /// Writes out a held repeat. Executor only.
    func hold() {
        let calendar = self.calendar
        for line in coalescer.flush(in: calendar) { keep(line) }
    }

    private func keep(_ line: DiagLine) {
        journal.append(line.text)
        sender.enqueue(line)
    }

    /// How the launch before this one ended, from the marker it left: one that was in the foreground, or
    /// still launching, did not end on its own, so its last lines come along.
    private func report(_ previous: DiagJournal.Previous) {
        let calendar = self.calendar
        let unexpected = previous.phase == .launching || previous.phase == .foreground
        let tail = unexpected || (previous.phase == .background && previous.playback != nil)
        let name = unexpected ? "previous-session-ended-unexpectedly"
            : tail ? "previous-session-ended-in-background" : "previous-session"
        let wall = executor.now
        let event = DiagEvent(
            wall: wall, uptime: max(0, wall.timeIntervalSince(born)),
            level: unexpected ? .warning : tail ? .notice : .info, category: .app, name: name,
            fields: DiagHub.format([
                "launch": previous.launch, "version": previous.version, "phase": previous.phase.rawValue,
                "playback": previous.playback, "last": DiagFormat.clock(previous.updated, calendar: calendar),
                "lines": tail ? previous.tail.count : nil,
            ]),
            launch: launch
        )
        write(event)
        guard tail else { return }
        for text in previous.tail { keep(DiagLine("| " + text, level: .info)) }
    }
}
