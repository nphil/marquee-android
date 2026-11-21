import Foundation
import MarqueeCore

/// The diagnostic log on the device (DESIGN §8.12), always kept: a file per launch, cut at 1 MB, with files
/// older than a day or past 8 MB in all removed. The layout is the iPhone's, so one reader serves both. A
/// marker file says how far the launch got, so the next one can tell whether it ended cleanly. Used only on
/// the log's executor.
final class DiagJournal {
    enum Phase: String, Codable, Sendable {
        case launching, foreground, background, ended
    }

    /// What the launch before this one left: how far it got, and its last lines.
    struct Previous {
        var launch: String
        var version: String
        var phase: Phase
        var playback: String?
        var updated: Date
        var tail: [String]
    }

    private struct Marker: Codable {
        var launch: String
        var version: String
        var phase: Phase
        var playback: String?
        var updated: Date
    }

    /// The previous launch's last lines that come along when it ended unexpectedly.
    private static let tailLines = 60
    private static let tailBytes = 64 * 1024

    private let folder: URL
    private let launch: String
    private let build: DiagLog.Build
    private let stamp: String
    private let policy: DiagJournalPolicy
    private let clock: @Sendable () -> Date
    private var marker: Marker
    private var handle: FileHandle?
    private var current: String?
    private var currentBytes = 0
    private var part = 0

    init(
        folder: URL, launch: String, build: DiagLog.Build, calendar: Calendar, policy: DiagJournalPolicy,
        clock: @escaping @Sendable () -> Date
    ) {
        self.folder = folder
        self.launch = launch
        self.build = build
        self.policy = policy
        self.clock = clock
        stamp = Self.stamp(clock(), calendar: calendar)
        marker = Marker(launch: launch, version: build.version, phase: .launching, playback: nil, updated: clock())
    }

    /// `20261112-094103`, the way a journal file and a report name their time.
    static func stamp(_ date: Date, calendar: Calendar) -> String {
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return String(format: "%04d%02d%02d-%02d%02d%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0,
                      parts.hour ?? 0, parts.minute ?? 0, parts.second ?? 0)
    }

    /// Creates the folder, reads what the last launch left, marks this one as launching and starts its file.
    func open() -> Previous? {
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let previous = readPrevious()
        writeMarker()
        startFile()
        prune()
        return previous
    }

    func append(_ text: String) {
        let data = Data((text + "\n").utf8)
        if handle == nil || policy.rotates(currentBytes: currentBytes, adding: data.count) {
            startFile()
            prune()
        }
        guard let handle else { return }
        do {
            try handle.write(contentsOf: data)
            currentBytes += data.count
        } catch {
            // A full disk: the line is lost, the next file is tried on the next line.
            self.handle = nil
        }
    }

    func mark(_ phase: Phase, playback: String?) {
        guard marker.phase != phase || marker.playback != playback else { return }
        marker.phase = phase
        marker.playback = playback
        marker.updated = clock()
        writeMarker()
        if phase != .foreground { sync() }
    }

    /// Pushes what is written to the disk, for a process that may be killed next.
    func sync() {
        try? handle?.synchronize()
    }

    /// Every file, oldest first.
    func allData() -> Data {
        sync()
        var data = Data()
        for file in files() {
            if let contents = try? Data(contentsOf: folder.appendingPathComponent(file.name)) { data.append(contents) }
        }
        return data
    }

    func erase() {
        try? handle?.close()
        handle = nil
        for file in files() { try? FileManager.default.removeItem(at: folder.appendingPathComponent(file.name)) }
        part = 0
        startFile()
    }

    private func startFile() {
        try? handle?.close()
        part += 1
        let name = "\(stamp)-\(launch)-\(String(format: "%02d", part)).log"
        let url = folder.appendingPathComponent(name)
        let header = Data("# Marquee \(build.version) (\(build.build)) \(build.commit) launch=\(launch) part=\(part)\n".utf8)
        _ = FileManager.default.createFile(atPath: url.path, contents: header)
        handle = try? FileHandle(forWritingTo: url)
        _ = try? handle?.seekToEnd()
        current = name
        currentBytes = header.count
    }

    private func prune() {
        let names = policy.removals(files(), current: current, now: clock())
        for name in names { try? FileManager.default.removeItem(at: folder.appendingPathComponent(name)) }
    }

    /// The journal files, oldest first.
    private func files() -> [DiagJournalPolicy.File] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names.filter { $0.hasSuffix(".log") }.compactMap { name in
            guard let attributes = try? FileManager.default.attributesOfItem(atPath: folder.appendingPathComponent(name).path) else {
                return nil
            }
            return DiagJournalPolicy.File(
                name: name, bytes: Self.bytes(attributes[.size]), modified: attributes[.modificationDate] as? Date ?? .distantPast
            )
        }
        .sorted { ($0.modified, $0.name) < ($1.modified, $1.name) }
    }

    /// The size attribute is an `NSNumber` in one Foundation and a `UInt64` in another.
    private static func bytes(_ value: Any?) -> Int {
        switch value {
        case let number as NSNumber: number.intValue
        case let size as UInt64: Int(size)
        case let size as Int: size
        default: 0
        }
    }

    private var markerURL: URL {
        folder.appendingPathComponent("session.json")
    }

    private func writeMarker() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        guard let data = try? encoder.encode(marker) else { return }
        try? data.write(to: markerURL, options: .atomic)
    }

    private func readPrevious() -> Previous? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        guard let data = try? Data(contentsOf: markerURL), let last = try? decoder.decode(Marker.self, from: data) else {
            return nil
        }
        let newest = files().last { $0.name.contains("-\(last.launch)-") }
        return Previous(
            launch: last.launch, version: last.version, phase: last.phase, playback: last.playback, updated: last.updated,
            tail: newest.map { tail(of: folder.appendingPathComponent($0.name)) } ?? []
        )
    }

    private func tail(of url: URL) -> [String] {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return [] }
        defer { try? handle.close() }
        let size = (try? handle.seekToEnd()) ?? 0
        let start = size > UInt64(Self.tailBytes) ? size - UInt64(Self.tailBytes) : 0
        try? handle.seek(toOffset: start)
        let data = (try? handle.readToEnd()) ?? Data()
        var lines = String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init)
        // A read that started mid-file starts mid-line.
        if start > 0, !lines.isEmpty { lines.removeFirst() }
        return Array(lines.filter { !$0.hasPrefix("#") }.suffix(Self.tailLines))
    }
}
