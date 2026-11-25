import Foundation
import Testing

@testable import MarqueeBridge
@testable import MarqueeCore

@Suite struct BackupCallsTests {
    private static let account = "uuid:abc"

    private static let payload = BackupPayload(
        platform: BackupPlatform.android, appVersion: "0.17.0",
        entries: [
            BackupEntry(store: "settings", key: "theme.light", group: .appearance, value: .string("paper")),
            BackupEntry(store: "settings", key: "tracks.s.u.t1", group: .tracks, value: .string("{}")),
            BackupEntry(store: "secret.arr", key: "radarr", group: .integrations, item: "Radarr", value: .string("{\"apiKey\":\"k\"}")),
        ],
        integrations: ["radarr": BackupIntegration(address: "http://radarr.test", secret: "k")]
    )

    /// A keyring whose passphrase was stretched with one round, so the test does not wait for 600 000.
    private static func keyring(passphrase: String? = nil) throws -> BackupKeyring {
        var keyring = BackupKeyring.create()
        if let passphrase { try keyring.setPassphrase(passphrase, iterations: 1) }
        return keyring
    }

    private func object(_ value: some Encodable) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any])
    }

    /// The `ok` of a call, or the `error` as a failure.
    private struct Failed: Error { let error: [String: Any] }

    private func call(_ name: String, _ fields: [String: Any]) async throws -> [String: Any] {
        let reply = await Methods.shared.reply(to: name, payload: try JSONSerialization.data(withJSONObject: fields))
        let json = try #require(JSONSerialization.jsonObject(with: reply) as? [String: Any])
        if let error = json["error"] as? [String: Any] { throw Failed(error: error) }
        return try #require(json["ok"] as? [String: Any])
    }

    private func seal(_ keyring: BackupKeyring, account: String = account) async throws -> String {
        let sealed = try await call("backup.seal", [
            "payload": object(Self.payload), "account": account, "keyring": object(keyring), "now": 1_700_000_000,
        ])
        return try #require(sealed["file"] as? String)
    }

    @Test func theAccountIsThePlexHomeAdminWhicheverProfileIsActive() async throws {
        func user(_ id: Int, _ uuid: String, admin: Bool) -> [String: Any] {
            ["id": id, "uuid": uuid, "title": "t", "admin": admin, "protected": false, "restricted": false, "guest": false]
        }
        let admin = user(1, "AB-1", admin: true)
        let kid = user(2, "CD-2", admin: false)
        let reply = try await call("backup.account", ["user": kid, "homeUsers": [kid, admin]])
        #expect(reply["id"] as? String == "uuid:ab-1")
    }

    @Test func aSealedBackupShowsItsHeaderAndOpensWithTheAccountKey() async throws {
        let keyring = try Self.keyring()
        let file = try await seal(keyring)

        let summary = try await call("backup.inspect", ["file": file, "account": Self.account])
        #expect(summary["createdAt"] as? Int == 1_700_000_000)
        #expect(summary["platform"] as? String == "android")
        #expect(summary["appVersion"] as? String == "0.17.0")
        #expect(summary["belongs"] as? Bool == true)
        #expect(summary["hasPassphrase"] as? Bool == false)

        let opened = try await call("backup.open", ["file": file, "account": Self.account, "keyring": object(keyring)])
        let back = try JSONDecoder().decode(BackupPayload.self, from: JSONSerialization.data(withJSONObject: #require(opened["payload"])))
        #expect(back == Self.payload)
        #expect(opened["keyring"] == nil)
        let lines = try #require(opened["contents"] as? [[String: Any]])
        #expect(lines.map { $0["title"] as? String } == ["Appearance and themes", "Integrations and their keys", "Remembered audio and subtitle choices"])
        #expect(lines[1]["detail"] as? String == "Radarr")
        #expect(lines.last?["detail"] as? String == "1 title")
    }

    @Test func eachRefusalCarriesItsKindAndCoresPlainSentence() async throws {
        let keyring = try Self.keyring()
        let file = try await seal(keyring)

        func refusal(_ fields: [String: Any]) async throws -> (failure: String?, message: String?) {
            do {
                _ = try await call("backup.open", fields)
                Issue.record("opened")
                return (nil, nil)
            } catch let failed as Failed {
                #expect(failed.error["kind"] as? String == "backup")
                return (failed.error["failure"] as? String, failed.error["message"] as? String)
            }
        }

        let other = try await refusal(["file": file, "account": "uuid:other", "keyring": object(keyring)])
        #expect(other.failure == "wrongAccount")
        #expect(other.message == BackupError.wrongAccount.message)

        let notBackup = try await refusal(["file": Data("hello".utf8).base64EncodedString(), "account": Self.account])
        #expect(notBackup.failure == "notABackup")

        // Another device: the account key is not here and the file has no passphrase to fall back on.
        let elsewhere = try await refusal(["file": file, "account": Self.account, "keyring": object(Self.keyring())])
        #expect(elsewhere.failure == "keyNotOnThisDevice")

        var cut = try #require(Data(base64Encoded: file))
        cut.removeLast(4)
        let damaged = try await refusal(["file": cut.base64EncodedString(), "account": Self.account, "keyring": object(keyring)])
        #expect(damaged.failure == "damaged")

        var newer = try #require(Data(base64Encoded: file))
        newer[5] = 9
        let later = try await refusal(["file": newer.base64EncodedString(), "account": Self.account, "keyring": object(keyring)])
        #expect(later.failure == "newerVersion")
        #expect(later.message == BackupError.newerVersion(9).message)
    }

    @Test func theRecoveryPassphraseOpensABackupOnAnotherDeviceAndIsKeptThere() async throws {
        let file = try await seal(Self.keyring(passphrase: "correct horse battery"))
        let fresh = try object(Self.keyring())

        func open(_ passphrase: String?) async throws -> [String: Any] {
            var fields: [String: Any] = ["file": file, "account": Self.account, "keyring": fresh]
            fields["passphrase"] = passphrase
            return try await call("backup.open", fields)
        }

        do {
            _ = try await open(nil)
            Issue.record("opened without the passphrase")
        } catch let failed as Failed {
            #expect(failed.error["failure"] as? String == "needsPassphrase")
        }
        do {
            _ = try await open("not the passphrase")
            Issue.record("opened with the wrong passphrase")
        } catch let failed as Failed {
            #expect(failed.error["failure"] as? String == "wrongPassphrase")
            #expect(failed.error["message"] as? String == BackupError.wrongPassphrase.message)
        }

        let opened = try await open("correct horse battery")
        let adopted = try JSONDecoder().decode(BackupKeyring.self, from: JSONSerialization.data(withJSONObject: #require(opened["keyring"])))
        #expect(adopted.hasPassphrase)
        #expect(adopted.secret == (try JSONDecoder().decode(BackupKeyring.self, from: JSONSerialization.data(withJSONObject: fresh))).secret)
    }

    @Test func theSentencesOfTheAppsOwnRefusalsAreCoresOwn() async throws {
        #expect(try await call("backup.message", ["failure": "restoreFailed"])["message"] as? String == BackupError.restoreFailed.message)
        #expect(try await call("backup.message", ["failure": "noAccount"])["message"] as? String == BackupError.noAccount.message)
        // Android words the profile line's count itself and finds the line by this title.
        #expect(BackupGroup.profiles.title == "Profile settings")
    }

    @Test func aPassphraseMustBeTwelveTo256CharactersAndCanBeRemoved() async throws {
        let keyring = try object(Self.keyring())
        for (phrase, failure) in [("short", "passphraseTooShort"), (String(repeating: "x", count: 257), "passphraseTooLong")] {
            do {
                _ = try await call("backup.keyring.setPassphrase", ["keyring": keyring, "passphrase": phrase])
                Issue.record("accepted \(phrase.count) characters")
            } catch let failed as Failed {
                #expect(failed.error["failure"] as? String == failure)
            }
        }

        let with = try object(Self.keyring(passphrase: "correct horse battery"))
        let removed = try await call("backup.keyring.removePassphrase", ["keyring": with])
        #expect(removed["hasPassphrase"] as? Bool == false)
        #expect((removed["keyring"] as? [String: Any])?["passphrase"] == nil)

        let created = try await call("backup.keyring.create", [:])
        #expect(created["hasPassphrase"] as? Bool == false)
    }

    @Test func theFingerprintFollowsTheContentAndIsKeyedWithTheSecret() async throws {
        let keyring = try Self.keyring()
        func fingerprint(_ payload: BackupPayload, _ keyring: BackupKeyring) async throws -> String? {
            try await call("backup.fingerprint", ["payload": object(payload), "account": Self.account, "keyring": object(keyring)])["fingerprint"] as? String
        }
        var changed = Self.payload
        changed.entries[0].value = .string("ink")
        var later = Self.payload
        later.appVersion = "0.18.0"

        let base = try await fingerprint(Self.payload, keyring)
        #expect(base != nil)
        #expect(try await fingerprint(later, keyring) == base)
        #expect(try await fingerprint(changed, keyring) != base)
        #expect(try await fingerprint(Self.payload, Self.keyring()) != base)
    }
}
