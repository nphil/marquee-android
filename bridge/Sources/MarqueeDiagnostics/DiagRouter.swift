import Foundation
import MarqueeCore
import Synchronization

/// The `diag.*` calls: the diagnostic log (DESIGN §8.12) as Kotlin reaches it, a method name and a JSON
/// object in and a JSON value out. Kotlin sends its events in order and in batches, with the time it stamped
/// each; the log formats, redacts, journals and sends them exactly as it does Core's own, so a line reads the
/// same whichever side wrote it. The wire shapes are mirrored in Kotlin's `diagnostics/DiagWire.kt`.
public final class DiagRouter: Sendable {
    public enum Failure: Error, Equatable {
        /// `diag.start` has not come yet.
        case notStarted
        /// A test or report was asked for with no server saved.
        case notSetUp
        case unknownMethod(String)
    }

    private let executor: any DiagExecutor
    private let send: NtfySend
    private let logcat: DiagLog.Logcat?
    private let log = Mutex<DiagLog?>(nil)

    public init(executor: any DiagExecutor, send: @escaping NtfySend, logcat: DiagLog.Logcat?) {
        self.executor = executor
        self.send = send
        self.logcat = logcat
    }

    public static func handles(_ method: String) -> Bool {
        method.hasPrefix("diag.")
    }

    /// The call's answer, ready to be encoded as the reply's value.
    public func handle(_ method: String, _ payload: Data) async throws -> any Encodable & Sendable {
        switch method {
        case "diag.start":
            return Started(launch: begin(try decode(Start.self, payload)).launch)
        case "diag.events":
            apply(try decode(Events.self, payload), to: try current())
            return Empty()
        case "diag.crash":
            // The process is about to die: the event, then the journal and the live log out before it does.
            let log = try current()
            apply(try decode(Events.self, payload), to: log)
            await log.flush()
            return Empty()
        case "diag.configure":
            return configure(try decode(Setup.self, payload), try current())
        case "diag.parse":
            return Parsed(try decode(Parse.self, payload))
        case "diag.phase":
            try current().setPhase(named: try decode(Phase.self, payload).phase)
            return Empty()
        case "diag.flush":
            try await current().flush()
            return Empty()
        case "diag.end":
            try await current().end()
            return Empty()
        case "diag.erase":
            try await current().erase()
            return Empty()
        case "diag.journal":
            let limit = try decode(JournalCall.self, payload).maxBytes
            let data = try await current().journalData()
            return JournalText(text: String(decoding: limit.map { data.suffix($0) } ?? data, as: UTF8.self))
        case "diag.state":
            let log = try current()
            return State(launch: log.launch, status: await log.status())
        case "diag.test":
            guard let check = try await current().test() else { throw Failure.notSetUp }
            return Checked(check)
        case "diag.report":
            let call = try decode(ReportCall.self, payload)
            let outcome = try await current().report(DiagReport(
                note: call.note, source: call.source, details: call.details,
                screenshot: call.screenshot.flatMap { Data(base64Encoded: $0) }, screen: call.screen
            ))
            return Sent(outcome)
        default:
            throw Failure.unknownMethod(method)
        }
    }

    /// Starts the log on the first call and hands the same one back after.
    private func begin(_ start: Start) -> DiagLog {
        log.withLock { current in
            if let current { return current }
            let log = DiagLog(
                folder: URL(fileURLWithPath: start.folder, isDirectory: true),
                build: DiagLog.Build(version: start.version, build: start.build, commit: start.commit),
                born: Date(timeIntervalSince1970: start.born / 1000), utcOffset: start.offset,
                executor: executor, send: send, logcat: logcat
            )
            current = log
            log.start()
            return log
        }
    }

    private func current() throws -> DiagLog {
        guard let log = log.withLock({ $0 }) else { throw Failure.notStarted }
        return log
    }

    private func apply(_ batch: Events, to log: DiagLog) {
        if let offset = batch.offset { log.setUTCOffset(offset) }
        for op in batch.ops {
            switch op.o {
            case "e":
                guard let level = op.l.flatMap(DiagLevel.init(rawValue:)), let category = op.c.flatMap(DiagCategory.init(rawValue:)),
                      let name = op.n else { continue }
                let wall = op.t.map { Date(timeIntervalSince1970: $0 / 1000) }
                let pairs = (op.f ?? []).map { ($0.key, $0.value.text) }
                // One door for every event, so the threshold and the redaction are Core's own.
                DiagLog.$stamped.withValue(wall) { DiagHub.emit(category, name, pairs: pairs, level: level) }
            case "b":
                if let id = op.id { log.beginPlayback(id) }
            case "x":
                log.endPlayback()
            default:
                continue
            }
        }
    }

    private func configure(_ setup: Setup, _ log: DiagLog) -> Configured {
        let config: NtfyConfig?
        switch NtfyConfig.parse(server: setup.server ?? "", topic: setup.topic ?? "", token: setup.token ?? "") {
        case .success(let parsed): config = parsed
        case .failure: config = nil
        }
        DiagHub.setThreshold(setup.detailed ? .debug : .info)
        log.configure(DiagLog.Setup(config: config, live: setup.live, title: setup.title, device: setup.device))
        return Configured(configured: config != nil)
    }

    private func decode<Value: Decodable>(_ type: Value.Type, _ payload: Data) throws -> Value {
        try JSONDecoder().decode(type, from: payload)
    }
}

// MARK: Wire shapes

private struct Empty: Encodable {}

private struct Start: Decodable {
    let folder: String
    let version: String
    let build: String
    let commit: String
    /// When the process began, in milliseconds since 1970.
    let born: Double
    /// The device's offset from UTC, in seconds.
    let offset: Int
}

private struct Started: Encodable {
    let launch: String
}

/// Events and playback marks in the order they happened. `o` is `e` for an event, `b` for a playback that
/// begins (with its `id`) and `x` for the one that ends. An event carries the time Kotlin stamped it with
/// (`t`, milliseconds since 1970), its level (`l`, 0 debug to 4 error), category, name and fields.
private struct Events: Decodable {
    let offset: Int?
    let ops: [Op]

    struct Op: Decodable {
        let o: String
        let t: Double?
        let l: Int?
        let c: String?
        let n: String?
        let f: [Field]?
        let id: String?
    }
}

/// `["key", value]`.
private struct Field: Decodable {
    let key: String
    let value: Scalar

    init(from decoder: any Decoder) throws {
        var pair = try decoder.unkeyedContainer()
        key = try pair.decode(String.self)
        value = try pair.decode(Scalar.self)
    }
}

/// A field's value in the type it arrived in, so numbers and booleans are written as the iPhone writes them.
private enum Scalar: Decodable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)

    init(from decoder: any Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() {
            self = .null
        } else if let bool = try? value.decode(Bool.self) {
            self = .bool(bool)
        } else if let int = try? value.decode(Int64.self) {
            self = .int(int)
        } else if let double = try? value.decode(Double.self) {
            self = .double(double)
        } else {
            self = .string(try value.decode(String.self))
        }
    }

    /// Text is redacted when the event is emitted.
    var text: String {
        switch self {
        case .null: DiagFormat.value(nil)
        case .bool(let bool): DiagFormat.value(bool)
        case .int(let int): DiagFormat.value(int)
        case .double(let double): DiagFormat.value(double)
        case .string(let string): string
        }
    }
}

private struct Setup: Decodable {
    let server: String?
    let topic: String?
    let token: String?
    let live: Bool
    let title: String
    let device: String
    let detailed: Bool
}

private struct Configured: Encodable {
    /// Whether a server and topic were given and are valid.
    let configured: Bool
}

private struct Parse: Decodable {
    let server: String
    let topic: String
    let token: String
}

/// What a typed server, topic and token are: valid, with the host the report goes to, or the one thing wrong.
private struct Parsed: Encodable {
    /// `scheme`, `host`, `plaintext`, `topic` or `token`.
    let problem: String?
    let host: String?

    init(_ parse: Parse) {
        switch NtfyConfig.parse(server: parse.server, topic: parse.topic, token: parse.token) {
        case .success(let config):
            problem = nil
            host = URLComponents(url: config.server, resolvingAgainstBaseURL: false)?.host
        case .failure(let problem):
            self.problem = "\(problem)"
            host = nil
        }
    }
}

private struct Phase: Decodable {
    let phase: String
}

private struct JournalCall: Decodable {
    /// Only the last this many bytes; all of it when absent.
    let maxBytes: Int?
}

private struct JournalText: Encodable {
    let text: String
}

private struct State: Encodable {
    let launch: String
    /// `off`, `sending`, `retrying` or `refused`.
    let state: String
    /// Milliseconds since 1970.
    let lastSent: Double?
    let status: Int?
    /// For `refused`: `needsToken` or `refused`.
    let refusal: String?
    let waiting: Int

    init(launch: String, status: DiagLog.Status) {
        self.launch = launch
        state = status.state
        lastSent = status.lastSent.map { $0.timeIntervalSince1970 * 1000 }
        self.status = status.status
        refusal = status.refusal
        waiting = status.waiting
    }
}

private struct Checked: Encodable {
    /// `sent`, `needsToken`, `refused`, `unreachable` or `failed`.
    let result: String
    let status: Int?

    init(_ check: ConnectionCheck) {
        switch check {
        case .sent: (result, status) = ("sent", nil)
        case .needsToken: (result, status) = ("needsToken", nil)
        case .refused: (result, status) = ("refused", nil)
        case .unreachable: (result, status) = ("unreachable", nil)
        case .failed(let code): (result, status) = ("failed", code)
        }
    }
}

private struct ReportCall: Decodable {
    let note: String
    let source: String
    let details: String
    let screen: String
    /// The JPEG, in base 64.
    let screenshot: String?
}

private struct Sent: Encodable {
    let sent: Bool
    let id: String?
    let attached: Bool?
    /// What to tell the person, when it did not go.
    let text: String?

    init(_ outcome: ReportOutcome) {
        switch outcome {
        case .sent(let id, let attached): (sent, self.id, self.attached, text) = (true, id, attached, nil)
        case .failed(let text): (sent, id, attached, self.text) = (false, nil, nil, text)
        }
    }
}
