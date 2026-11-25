import Foundation
import MarqueeCore

/// Settings backup (DESIGN §8.13): the file, the keys that open it and the sentences for each refusal are
/// MarqueeCore's. What a backup holds and how it is written back is the app's, over its own stores, so the
/// payload and the keyring cross as JSON in Core's Codable form and the app keeps them without reading them.
/// Keyrings and passphrases are secrets: nothing here logs one.
enum BackupCalls {
    static func handles(_ name: String) -> Bool { name.hasPrefix("backup.") }

    static func handle(_ name: String, _ payload: Data) async throws -> Data {
        let decoder = JSONDecoder()
        switch name {
        case "backup.account":
            let call = try decoder.decode(AccountCall.self, from: payload)
            return try Reply.ok(Account(id: BackupAccount.id(user: call.user, homeUsers: call.homeUsers)))
        case "backup.message":
            // The sentences of the refusals the app itself raises, so every platform words them as Core does.
            let which = try decoder.decode(MessageCall.self, from: payload).failure
            let errors: [String: BackupError] = [
                "noAccount": .noAccount, "restoreFailed": .restoreFailed, "restoreIncomplete": .restoreIncomplete,
                "notABackup": .notABackup, "damaged": .damaged, "wrongAccount": .wrongAccount,
                "passphraseTooShort": .passphraseTooShort, "passphraseTooLong": .passphraseTooLong,
            ]
            guard let error = errors[which] else { throw BridgeError.unknownMethod("backup.message \(which)") }
            return try Reply.ok(Message(message: error.message))
        case "backup.keyring.create":
            return try Reply.ok(KeyringReply(BackupKeyring.create()))
        case "backup.keyring.setPassphrase":
            let call = try decoder.decode(PassphraseCall.self, from: payload)
            // Stretching the passphrase takes about a second: off the caller's task, which may be a UI one.
            let keyring = try await Task.detached(priority: .userInitiated) {
                var keyring = call.keyring ?? BackupKeyring.create()
                try keyring.setPassphrase(call.passphrase)
                return keyring
            }.value
            return try Reply.ok(KeyringReply(keyring))
        case "backup.keyring.removePassphrase":
            var keyring = try decoder.decode(KeyringCall.self, from: payload).keyring
            keyring.removePassphrase()
            return try Reply.ok(KeyringReply(keyring))
        case "backup.fingerprint":
            let call = try decoder.decode(SealCall.self, from: payload)
            return try Reply.ok(Fingerprint(fingerprint: BackupSnapshot.fingerprint(call.payload, key: call.keyring.secret)))
        case "backup.seal":
            let call = try decoder.decode(SealCall.self, from: payload)
            let now = call.now.map(Date.init(timeIntervalSince1970:)) ?? Date()
            let file = try await Task.detached(priority: .userInitiated) {
                try Backup.seal(call.payload, account: call.account, keyring: call.keyring, now: now)
            }.value
            return try Reply.ok(Sealed(file: file))
        case "backup.inspect":
            let call = try decoder.decode(InspectCall.self, from: payload)
            let header = try Backup.inspect(call.file)
            return try Reply.ok(Summary(header, belongs: call.account.map { Backup.belongs(header, to: $0) }))
        case "backup.open":
            let call = try decoder.decode(OpenCall.self, from: payload)
            let opened = try await Task.detached(priority: .userInitiated) {
                try Backup.open(call.file, account: call.account, keyring: call.keyring, passphrase: call.passphrase)
            }.value
            // Backups made from now on carry the passphrase that opened this one.
            var adopted = call.keyring
            adopted?.adopt(opened.passphraseKey)
            let changed = adopted != call.keyring ? adopted : nil
            return try Reply.ok(Opened(opened, keyring: changed))
        default:
            throw BridgeError.unknownMethod(name)
        }
    }

    private struct AccountCall: Decodable {
        let user: PlexHomeUser?
        let homeUsers: [PlexHomeUser]
    }

    private struct MessageCall: Decodable {
        let failure: String
    }

    private struct Message: Encodable {
        let message: String
    }

    private struct KeyringCall: Decodable {
        let keyring: BackupKeyring
    }

    private struct PassphraseCall: Decodable {
        let keyring: BackupKeyring?
        let passphrase: String
    }

    private struct SealCall: Decodable {
        let payload: BackupPayload
        let account: String
        let keyring: BackupKeyring
        /// Seconds since 1970; the time of the call when absent.
        let now: Double?
    }

    private struct InspectCall: Decodable {
        let file: Data
        let account: String?
    }

    private struct OpenCall: Decodable {
        let file: Data
        let account: String
        let keyring: BackupKeyring?
        let passphrase: String?
    }

    private struct Account: Encodable {
        let id: String?
    }

    private struct KeyringReply: Encodable {
        let keyring: BackupKeyring
        let hasPassphrase: Bool

        init(_ keyring: BackupKeyring) {
            self.keyring = keyring
            hasPassphrase = keyring.hasPassphrase
        }
    }

    private struct Fingerprint: Encodable {
        let fingerprint: Data
    }

    private struct Sealed: Encodable {
        let file: Data
    }

    /// A file's header: when, by which app, and whether it is the signed-in account's. No key needed.
    private struct Summary: Encodable {
        let createdAt: Int
        let platform: String
        let appVersion: String
        let hasPassphrase: Bool
        let belongs: Bool?

        init(_ header: BackupHeader, belongs: Bool?) {
            createdAt = header.createdAt
            platform = header.app.platform
            appVersion = header.app.version
            hasPassphrase = header.hasPassphraseSlot
            self.belongs = belongs
        }
    }

    private struct Line: Encodable {
        let title: String
        let detail: String?
    }

    private struct Opened: Encodable {
        let summary: Summary
        let payload: BackupPayload
        /// What a restore on Android changes, in words and never a value.
        let contents: [Line]
        /// The keyring with the passphrase key the file was opened with, when that is new.
        let keyring: BackupKeyring?

        init(_ opened: OpenedBackup, keyring: BackupKeyring?) {
            summary = Summary(opened.header, belongs: true)
            payload = opened.payload
            contents = BackupContents(opened.payload, restoringOn: BackupPlatform.android).lines.map { Line(title: $0.title, detail: $0.detail) }
            self.keyring = keyring
        }
    }
}
