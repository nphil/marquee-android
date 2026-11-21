import Foundation
import MarqueeCore

/// A report to send (DESIGN §8.12): what the person wrote, what the app knows of itself and, if they kept it,
/// a screenshot.
public struct DiagReport: Sendable {
    public var note: String
    /// Where the report was started from: `settings`, `button` or `player`.
    public var source: String
    /// The report's lines under its header: the device, network, screen, server and settings. The whole
    /// text is redacted before it leaves, so the caller need not.
    public var details: String
    /// JPEG bytes, already scaled to a size ntfy takes.
    public var screenshot: Data?
    /// What was on screen, for the summary message.
    public var screen: String

    public init(note: String, source: String, details: String, screenshot: Data?, screen: String) {
        self.note = note
        self.source = source
        self.details = details
        self.screenshot = screenshot
        self.screen = screen
    }
}

public enum ReportOutcome: Sendable, Equatable {
    case sent(id: String, attached: Bool)
    /// What to tell the person.
    case failed(String)
}

/// What a test message showed about the server.
public enum ConnectionCheck: Sendable, Equatable {
    case sent
    /// A 401: the server wants a token, or does not know this one.
    case needsToken
    /// A 403: the token may not write to the topic.
    case refused
    case unreachable
    case failed(Int)
}

extension DiagLog {
    private static let fallbackBytes = 40_000

    /// Builds and sends a report: the journal with the app's state at its top as an attachment, the screenshot
    /// as another, then a short message with the note that names both. A server that refuses attachments gets
    /// the state and the journal's last 40 KB as messages instead.
    public func report(_ report: DiagReport) async -> ReportOutcome {
        guard let setup = await onExecutor({ $0.setup }), let config = setup.config else {
            return .failed("Diagnostic Logs is not set up.")
        }
        let id = "r" + String((0..<4).map { _ in "0123456789abcdef".randomElement() ?? "0" })
        let started = executor.now
        let calendar = self.calendar
        let note = DiagRedactor.redact(report.note.trimmingCharacters(in: .whitespacesAndNewlines))
        DiagHub.emit(.app, "report.send", [
            "report": id, "from": report.source, "note": !note.isEmpty, "screenshot": report.screenshot != nil,
        ], level: .notice)
        let state = DiagRedactor.redact([
            "Marquee report \(id) · \(ISO8601DateFormatter().string(from: started))",
            "From: \(setup.device) · \(report.source)",
            "Build: \(build.version) (\(build.build)) \(build.commit) · launch \(launch)",
            report.details,
        ].joined(separator: "\n"))
        let journal = await journalData()
        var body = Data(state.utf8)
        body.append(Data("\n\n==== Journal ====\n".utf8))
        body.append(journal)
        let logName = NtfyConfig.reportFilename(device: setup.device, at: started, calendar: calendar)

        let upload = await NtfyClient.post(config.publish(NtfyMessage(
            title: setup.title, text: "Report \(id): log", priority: .min,
            attachment: .init(data: body, filename: logName, contentType: "text/plain; charset=utf-8")
        )), through: transport)
        let attached = upload.answer == .accepted
        if let problem = problem(upload, id: id) { return .failed(problem) }
        if !attached {
            // Attachments are off on this server: the state and the journal's end as plain messages.
            let tail = String(decoding: journal.suffix(Self.fallbackBytes), as: UTF8.self)
            let lines = (state + "\n==== Journal (last part) ====\n" + tail).split(separator: "\n").map { DiagLine(String($0)) }
            let parts = DiagPacker.pack(lines, limit: DiagPacker.messageLimit - 40)
            for (index, part) in parts.enumerated() {
                let outcome = await NtfyClient.post(config.publish(NtfyMessage(
                    title: setup.title, text: "Report \(id) part \(index + 1)/\(parts.count)\n" + part.text, priority: .min
                )), through: transport)
                if let problem = problem(outcome, id: id) { return .failed(problem) }
            }
        }
        var shotName: String?
        if attached, let jpeg = report.screenshot {
            let name = NtfyConfig.reportFilename(device: setup.device, at: started, ext: "jpg", calendar: calendar)
            let outcome = await NtfyClient.post(config.publish(NtfyMessage(
                title: setup.title, text: "Report \(id): screenshot", priority: .min,
                attachment: .init(data: jpeg, filename: name, contentType: "image/jpeg")
            )), through: transport)
            if outcome.answer == .accepted { shotName = name }
        }
        var summary = [
            "Report \(id) from \(setup.device)", note.isEmpty ? "No note." : "Note: \(note)", "Screen: \(report.screen)",
        ]
        summary.append(attached ? "Log: \(logName)" : "Log: in the messages before this one")
        if let shotName { summary.append("Screenshot: \(shotName)") }
        let outcome = await NtfyClient.post(config.publish(NtfyMessage(
            title: "\(setup.title) · Report", text: summary.joined(separator: "\n"), priority: .high, tags: ["memo"]
        )), through: transport)
        if let problem = problem(outcome, id: id) { return .failed(problem) }
        DiagHub.emit(.app, "report.sent", [
            "report": id, "attached": attached, "bytes": body.count, "ms": milliseconds(since: started),
        ], level: .notice)
        return .sent(id: id, attached: attached)
    }

    /// Sends a test message to the saved server. Reaching it also resumes a live log a refused token had stopped.
    public func test() async -> ConnectionCheck? {
        guard let setup = await onExecutor({ $0.setup }), let config = setup.config else { return nil }
        let started = executor.now
        let message = NtfyMessage(title: setup.title, text: "Test: this device can send its diagnostic log here.", tags: ["white_check_mark"])
        let outcome = await NtfyClient.post(config.publish(message), through: transport)
        let result: ConnectionCheck = switch outcome.answer {
        case .accepted: .sent
        case .needsToken: .needsToken
        case .refused: .refused
        case nil: .unreachable
        case .attachmentRefused, .rateLimited, .serverError, .unexpected: .failed(outcome.status ?? 0)
        }
        DiagHub.emit(.integration, "ntfy.test", [
            "result": "\(result)", "status": outcome.status, "error": outcome.error, "ms": milliseconds(since: started),
        ], level: result == .sent ? .info : .notice)
        if result == .sent { resume() }
        return result
    }

    /// What to tell the person when a step failed; nil when it went, or only an attachment was refused.
    private func problem(_ outcome: NtfyOutcome, id: String) -> String? {
        let text: String
        switch outcome.answer {
        case .accepted, .attachmentRefused: return nil
        case .needsToken: text = "The server wants an access token, or does not know this one. Check it in Diagnostic Logs."
        case .refused: text = "The server refused to let this device write to the topic. Check it in Diagnostic Logs."
        case .rateLimited: text = "The server is limiting how fast this device sends. Try again in a minute."
        case .serverError(let status), .unexpected(let status): text = "The server answered with an error (\(status)). Try again later."
        case nil: text = "Can't reach the server. Check the address and that this device is online."
        }
        DiagHub.emit(.app, "report.failed", ["report": id, "status": outcome.status, "error": outcome.error], level: .warning)
        return text
    }

    private func milliseconds(since start: Date) -> Int {
        Int(executor.now.timeIntervalSince(start) * 1000)
    }
}
