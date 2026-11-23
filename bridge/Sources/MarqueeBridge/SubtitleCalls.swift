import Foundation
import MarqueeCore

/// Subtitles use the same search, address validation and word timing as the iPhone.
enum SubtitleCalls {
    static func handles(_ name: String) -> Bool { name.hasPrefix("subtitles.") }

    static func handle(
        _ name: String, _ payload: Data, using services: PlayServices,
        transport: any PlexTransport = OkHttpTransport.shared
    ) async throws -> Data {
        func read<T: Decodable>(_ type: T.Type) throws -> T { try JSONDecoder().decode(type, from: payload) }
        let transport = OriginRestrictedTransport(upstream: transport)
        switch name {
        case "subtitles.bazarr.address":
            let call = try read(AddressCall.self)
            return try Reply.ok(ParsedAddress(BazarrConfig.parse(address: call.address, key: call.key ?? "").map(\.address)))
        case "subtitles.bazarr.status":
            let call = try read(AddressCall.self)
            let config = try BazarrConfig.parse(address: call.address, key: call.key ?? "").get()
            do {
                let status = try await BazarrClient(config: config, transport: transport).status()
                return try Reply.ok(Connection(version: status.version, sonarr: status.sonarrVersion, radarr: status.radarrVersion))
            } catch let error as BazarrError {
                return try Reply.ok(Connection(problem: String(describing: error)))
            }
        case "subtitles.speech.address":
            return try Reply.ok(ParsedAddress(SpeechDock.origin(try read(AddressCall.self).address)))
        case "subtitles.speech.status":
            let origin = try SpeechDock.origin(try read(AddressCall.self).address).get()
            do {
                return try Reply.ok(Connection(version: try await SpeechDockClient(origin: origin, transport: transport).health()))
            } catch let error as SpeechDock.Failure {
                return try Reply.ok(Connection(problem: error == .unreachable ? "unreachable" : "notSpeechDock"))
            }
        case "subtitles.find":
            let call = try read(FindSubtitlesCall.self)
            return try Reply.ok(try await services.session(call.session).findSubtitles(call, transport: transport))
        case "subtitles.find.status":
            let call = try read(FindProgressCall.self)
            return try Reply.ok(await services.session(call.session).subtitleSearch.banner(for: call.request))
        case "subtitles.speech.prepare":
            let call = try read(PrepareSpeechCall.self)
            return try Reply.ok(try await services.session(call.session).prepareSpeech(call))
        case "subtitles.speech.align":
            let call = try read(AlignSpeechCall.self)
            return try Reply.ok(try await services.session(call.session).alignSpeech(call, transport: transport))
        case "subtitles.speech.stop":
            let call = try read(StopSpeechCall.self)
            try await services.session(call.session).stopSpeech(call)
            return try Reply.ok(Empty())
        case "subtitles.reveal":
            let call = try read(RevealCall.self)
            return try Reply.ok(call.cues.map { $0.words(in: call.mode) })
        default:
            throw BridgeError.unknownMethod(name)
        }
    }
}

/// Bazarr's key, SpeechDock's cue text and HomeLabber's token must not go to a different origin through a redirect.
struct OriginRestrictedTransport: PlexTransport {
    let upstream: any PlexTransport

    func send(_ request: PlexRequest) async throws -> PlexHTTPResponse {
        var request = request
        request.restrictToOrigin = true
        return try await upstream.send(request)
    }
}

private struct AddressCall: Decodable {
    let address: String
    let key: String?
}

private struct ParsedAddress: Encodable {
    var address: String?
    var problem: String?

    init<E: Error>(_ result: Result<URL, E>) {
        switch result {
        case .success(let url): address = url.absoluteString
        case .failure(let error): problem = String(describing: error)
        }
    }
}

private struct Connection: Encodable {
    var version: String?
    var sonarr: String?
    var radarr: String?
    var problem: String?
}
