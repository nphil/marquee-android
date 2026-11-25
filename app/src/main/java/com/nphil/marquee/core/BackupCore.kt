package com.nphil.marquee.core

import android.util.Base64
import com.nphil.marquee.backup.BackupJson
import com.nphil.marquee.backup.BackupLine
import com.nphil.marquee.backup.BackupPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The key a backup's file is sealed with, kept by Marquee's backup key store and handed back to Core on each call.
 * It carries a secret, so it is opaque here and never printed.
 */
@JvmInline
internal value class Keyring(val json: JsonObject) {
	override fun toString() = "Keyring"
}

@Serializable
internal data class KeyringReply(val keyring: JsonObject, val hasPassphrase: Boolean)

@Serializable
internal data class BackupSummary(
	val createdAt: Long,
	val platform: String,
	val appVersion: String,
	val hasPassphrase: Boolean,
	val belongs: Boolean? = null,
)

@Serializable
internal data class OpenedBackupReply(
	val summary: BackupSummary,
	val payload: BackupPayload,
	val contents: List<BackupLine>,
	/** Present only when the file's passphrase key is new to this keyring. */
	val keyring: JsonObject? = null,
) {
	override fun toString() = "OpenedBackup"
}

@Serializable
private data class AccountReply(val id: String? = null)

@Serializable
private data class FingerprintReply(val fingerprint: String? = null)

@Serializable
private data class SealedReply(val file: String)

@Serializable
private data class MessageReply(val message: String)

internal suspend fun MarqueeCore.backupAccount(user: PlexHomeUser?, homeUsers: List<PlexHomeUser>): String? =
	call<AccountReply>("backup.account", buildJsonObject {
		if (user != null) put("user", CoreJson.encodeToJsonElement(PlexHomeUser.serializer(), user))
		put("homeUsers", CoreJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PlexHomeUser.serializer()), homeUsers))
	}).id

internal suspend fun MarqueeCore.newKeyring(): KeyringReply = call("backup.keyring.create", JsonObject(emptyMap()))

/** Core's own sentence for a refusal the app raises itself (`restoreFailed`, `noAccount`, ...). */
internal suspend fun MarqueeCore.backupMessage(failure: String): String =
	call<MessageReply>("backup.message", buildJsonObject { put("failure", failure) }).message

internal suspend fun MarqueeCore.setBackupPassphrase(keyring: Keyring?, passphrase: String): KeyringReply =
	call("backup.keyring.setPassphrase", buildJsonObject {
		if (keyring != null) put("keyring", keyring.json)
		put("passphrase", passphrase)
	})

internal suspend fun MarqueeCore.removeBackupPassphrase(keyring: Keyring): KeyringReply =
	call("backup.keyring.removePassphrase", buildJsonObject { put("keyring", keyring.json) })

/** A keyed digest of what a backup would hold, to tell a changed one from an unchanged one without comparing values. */
internal suspend fun MarqueeCore.backupFingerprint(payload: BackupPayload, account: String, keyring: Keyring): String? =
	call<FingerprintReply>("backup.fingerprint", sealParams(payload, account, keyring, null)).fingerprint

internal suspend fun MarqueeCore.sealBackup(payload: BackupPayload, account: String, keyring: Keyring, now: Double? = null): ByteArray =
	Base64.decode(call<SealedReply>("backup.seal", sealParams(payload, account, keyring, now)).file, Base64.DEFAULT)

internal suspend fun MarqueeCore.inspectBackup(file: ByteArray, account: String?): BackupSummary =
	call("backup.inspect", buildJsonObject {
		put("file", Base64.encodeToString(file, Base64.NO_WRAP))
		if (account != null) put("account", account)
	})

internal suspend fun MarqueeCore.openBackup(file: ByteArray, account: String, keyring: Keyring?, passphrase: String?): OpenedBackupReply =
	call("backup.open", buildJsonObject {
		put("file", Base64.encodeToString(file, Base64.NO_WRAP))
		put("account", account)
		if (keyring != null) put("keyring", keyring.json)
		if (passphrase != null) put("passphrase", passphrase)
	})

private fun sealParams(payload: BackupPayload, account: String, keyring: Keyring, now: Double?): JsonElement = buildJsonObject {
	put("payload", BackupJson.encodeToJsonElement(BackupPayload.serializer(), payload))
	put("account", account)
	put("keyring", keyring.json)
	if (now != null) put("now", now)
}
