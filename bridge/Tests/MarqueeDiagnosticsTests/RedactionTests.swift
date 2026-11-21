import Foundation
import MarqueeCore
import Testing

@testable import MarqueeDiagnostics

extension Diagnostics {
    /// A secret never leaves the process in a line: not in the journal, not in logcat, not on the topic.
    @Suite struct Redaction {
        static let secrets = [
            "SUPERSECRETPLEXTOKEN", "bearerSecret123", "tk_0123456789abcdef", "p4ssw0rd-value", "cookieSecretValue",
            "hlmq_abcdef0123456789", "apiKeyValue98765",
        ]

        /// Every place a line can end up, as one string each.
        private func sinks(_ h: Harness) async -> [(name: String, text: String)] {
            [
                ("journal", String(decoding: await h.log.journalData(), as: UTF8.self)),
                ("logcat", h.logcat.all.map(\.line).joined(separator: "\n")),
                ("topic", h.ntfy.requests.map { $0.text + $0.url.absoluteString }.joined(separator: "\n")),
                ("pending", (try? String(contentsOf: h.folder.appendingPathComponent("pending.queue"), encoding: .utf8)) ?? ""),
            ]
        }

        @Test func aLineBearingEverySortOfSecretIsWrittenWithoutThem() async {
            let h = Harness()
            h.configure(token: "tk_0123456789abcdef")
            h.note("request", .warning, .net, [
                "url": URL(string: "https://plex.example:32400/library/sections?X-Plex-Token=SUPERSECRETPLEXTOKEN&X-Plex-Client-Identifier=abc")!,
                "accessToken": "tk_0123456789abcdef",
                "Authorization": "Bearer bearerSecret123",
                "password": "p4ssw0rd-value",
                "cookie": "connect.sid=cookieSecretValue",
                "error": "GET https://plex.example/video/:/transcode?X-Plex-Token=SUPERSECRETPLEXTOKEN failed: Cookie: sid=cookieSecretValue",
                "detail": "ntfy rejected tk_0123456789abcdef; homelab said hlmq_abcdef0123456789; header x-api-key: apiKeyValue98765",
                "note": "this part is fine to keep",
            ])
            h.executor.advance(by: 1)
            for (name, text) in await sinks(h) {
                for secret in Self.secrets { #expect(!text.contains(secret), "\(secret) reached the \(name)") }
            }
            let journal = await h.journalLines()
            #expect(journal.contains { $0.contains("url=\"https://plex.example:32400/library/sections?X-Plex-Token=…&X-Plex-Client-Identifier=…\"") })
            #expect(journal.contains { $0.contains(" Authorization=…") })
            #expect(journal.contains { $0.contains("note=\"this part is fine to keep\"") })
            #expect(h.ntfy.count >= 1)
        }

        @Test func aSecretNamedFieldIsBlankedWhateverItHolds() async {
            let h = Harness()
            h.note("login", .info, .plex, ["authToken": "plainwords", "client_secret": "more plain words", "user": "isabel"])
            let line = await h.journalLines().first ?? ""
            #expect(line.hasSuffix(" plex login authToken=… client_secret=… user=isabel"))
        }

        @Test func aLongValueIsCutSoOneEventCannotCrowdOutTheRest() async {
            let h = Harness()
            h.note("big", .info, .app, ["text": String(repeating: "a", count: 2_000)])
            let line = await h.journalLines().first ?? ""
            #expect(line.count < 400)
            #expect(line.hasSuffix("…"))
        }

        @Test func aPlaybackUrlIsReducedToItsHostAndPath() async {
            let h = Harness()
            h.note("start", .info, .player, ["url": URL(string: "https://media.example/video/:/transcode/universal/start.m3u8?X-Plex-Token=SUPERSECRETPLEXTOKEN&session=abc&path=%2Flibrary%2Fmetadata%2F42")!])
            let line = await h.journalLines().first ?? ""
            #expect(line.contains("https://media.example/video/:/transcode/universal/start.m3u8?X-Plex-Token=…&session=…&path=…"))
            #expect(!line.contains("SUPERSECRETPLEXTOKEN"))
            #expect(!line.contains("metadata"))
        }
    }
}
