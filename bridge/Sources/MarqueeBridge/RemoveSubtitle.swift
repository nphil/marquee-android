import Foundation
import MarqueeCore

struct RemoveSubtitleCall: Decodable {
    let session: String
    let ratingKey: String
    let partId: Int
    let stream: Int
    var ticket: String?
}

struct SubtitleRemovalReply: Encodable, Sendable {
    var ticket: String?
    var tracks: PlayTracksWire?
}

extension PlaySession {
    func removeExternalSubtitle(_ call: RemoveSubtitleCall, confirm: Bool, transport: any PlexTransport) async throws -> SubtitleRemovalReply {
        guard let library, hasStream, library.server.isOwned, let file = subtitleFile, call.stream > 0,
              file.key == .init(ratingKey: call.ratingKey, partId: call.partId) else { throw PlayError.noFile }
        if confirm {
            guard let grant = subtitleSearch.removal, grant.ticket == call.ticket, grant.file == file.id,
                  grant.stream == call.stream else { throw PlayError.noFile }
            subtitleSearch.removal = nil
        }
        guard try await library.serverInfo().allowsMediaDeletion else { throw PlayError.noFile }
        let fresh = try await library.metadata(call.ratingKey)
        let path = "/library/streams/\(call.stream)"
        guard var media = fresh.media.first(where: { $0.id == file.media.id }),
              let index = media.parts.firstIndex(where: { $0.id == call.partId }),
              media.parts[index].streams.contains(where: { $0.id == call.stream && $0.kind == .subtitle && $0.key == path }),
              subtitleFile?.id == file.id else { throw PlayError.noFile }
        if !confirm {
            let ticket = UUID().uuidString
            subtitleSearch.removal = (ticket, file.id, call.stream)
            return SubtitleRemovalReply(ticket: ticket)
        }
        let base = try await library.connection.connection().uri
        guard subtitleFile?.id == file.id, let url = PlexURL.make(base: base, path: path) else { throw PlayError.noFile }
        let result = try await transport.send(PlexRequest(method: .delete, url: url, headers: library.authorizationHeaders(), isIdempotent: false, restrictToOrigin: true))
        guard (200..<300).contains(result.status) else { throw PlayError.noFile }
        guard subtitleFile?.id == file.id else { return SubtitleRemovalReply() }
        media.parts[index].streams.removeAll { $0.id == call.stream }
        let part = media.parts[index], tracks = MediaTracks(part: part)
        subtitleFile?.media = media
        subtitleFile?.tracks = tracks
        return SubtitleRemovalReply(tracks: file.menu(media, part, tracks))
    }
}
