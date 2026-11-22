import Foundation
import MarqueeCore

/// Plex never sees the file paths or the local playback session. Only kept streams are offered by the player.
struct LocalPlayFiles: Decodable {
    let snapshot: String
    let decision: String?
    let file: String
    let version: Int
    let isAnime: Bool
    let countries: [String]
    let sidecars: [Int: String]
    let localOffset: Int

    func item() throws -> PlexMetadata {
        var item = try read(snapshot)
        guard item.media.indices.contains(version) else { throw DownloadError("The saved media version is missing.") }
        let original = item.media[version]
        let saved = original.parts.flatMap(\.streams).filter { sidecars[$0.id] != nil }
        let media = try decision.map { try read($0).media } ?? [original]
        guard var first = media.first, !first.parts.isEmpty else { throw DownloadError("The saved file description is missing.") }
        first.parts[0].streams.removeAll { $0.kind == .subtitle && $0.key != nil }
        let present = Set(first.parts[0].streams.map(\.id))
        first.parts[0].streams += saved.filter { !present.contains($0.id) }
        item.media = [first]
        return item
    }

    private func read(_ path: String) throws -> PlexMetadata {
        try PlexMetadata.fromSnapshot(Data(contentsOf: URL(fileURLWithPath: path)))
    }
}
