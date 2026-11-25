package com.nphil.marquee.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import com.nphil.marquee.BuildConfig
import com.nphil.marquee.core.BackupSummary
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.Keyring
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.OpenedBackupReply
import com.nphil.marquee.core.backupAccount
import com.nphil.marquee.core.backupFingerprint
import com.nphil.marquee.core.backupMessage
import com.nphil.marquee.core.inspectBackup
import com.nphil.marquee.core.newKeyring
import com.nphil.marquee.core.openBackup
import com.nphil.marquee.core.removeBackupPassphrase
import com.nphil.marquee.core.sealBackup
import com.nphil.marquee.core.setBackupPassphrase
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.session.AppSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/** A refusal with its sentence ready, such as a file of another account. */
internal class BackupRefusal(message: String) : Exception(message)

/**
 * Settings › Backup & Sync (DESIGN §8.13): one encrypted file of the settings, saved where the person chooses, kept up
 * to date in a folder when automatic backup is on, and read back all or nothing. Core makes and opens the file; this
 * decides what goes in it, writes it where the system's pickers point, and applies a restore.
 *
 * The key that seals a backup is in the Keystore and never leaves this device, so a backup moves to another device,
 * or to an iPhone, only through the recovery passphrase. Automatic backup therefore needs one.
 */
internal class BackupCenter(
	private val context: Context,
	private val core: MarqueeCore,
	private val session: AppSession,
	private val store: BackupStore,
	/** Emits when something a backup holds may have changed. Collected only while automatic backup is on. */
	private val changes: () -> Flow<Any?>,
	private val scope: CoroutineScope,
) {
	/** What the restore is doing, for its dialog. */
	sealed interface Restore {
		data object Reading : Restore

		/** The file needs the recovery passphrase; [message] says so, or that the one given was wrong. */
		data class Passphrase(val message: String) : Restore

		data class Review(val summary: BackupSummary, val lines: List<BackupLine>) : Restore

		data object Applying : Restore
		data object Done : Restore
		data class Failed(val message: String) : Restore
	}

	data class State(
		/** The Plex account a backup belongs to; null while signed out. */
		val account: String? = null,
		val hasPassphrase: Boolean = false,
		val working: String? = null,
		val problem: String? = null,
		/** Seconds since 1970 of the last backup made here, by hand or by the folder. */
		val last: Long? = null,
		val automatic: Boolean = false,
		val folderName: String? = null,
		val lastAutomatic: Long? = null,
		val automaticProblem: String? = null,
		/** Seconds since 1970 of a backup in the folder that is newer than anything this device saved. */
		val foundNewer: Long? = null,
		val restore: Restore? = null,
		/** The file name the save picker should offer, while a backup waits for it. */
		val exportName: String? = null,
		val hasPendingOpen: Boolean = false,
	) {
		val summary: String get() = if (foundNewer != null || automaticProblem != null) "Needs attention" else if (automatic) "On" else "Off"
	}

	private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
	private val keys = SecretStore(context, "backup")
	private val folder = BackupFolder(context.contentResolver)
	private val keyLock = Mutex()
	private val writeLock = Mutex()
	private val mutable = MutableStateFlow(
		State(
			last = prefs.longOrNull(LAST),
			automatic = prefs.getString(FOLDER, null) != null,
			folderName = prefs.getString(FOLDER_NAME, null),
			lastAutomatic = prefs.longOrNull(LAST_AUTOMATIC),
		),
	)
	val state: StateFlow<State> = mutable.asStateFlow()

	/** Fires when a backup opened from outside the app is waiting, so Settings can show its page. */
	private val openRequests = Channel<Unit>(Channel.CONFLATED)
	val opens: Flow<Unit> = openRequests.receiveAsFlow()

	private var exportBytes: ByteArray? = null
	private var pendingOpen: ByteArray? = null
	private var pendingFile: ByteArray? = null
	private var opened: OpenedBackupReply? = null
	private var dirty = false

	private val folderUri: Uri? get() = prefs.getString(FOLDER, null)?.let(Uri::parse)
	private var newest: Long
		get() = prefs.getLong(NEWEST, 0)
		set(value) = prefs.edit { putLong(NEWEST, value) }

	@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
	fun start() {
		scope.launch {
			session.state.map { it.user to it.homeUsers }.distinctUntilChanged().collectLatest { (user, homeUsers) ->
				val id = user?.let { runCatching { core.backupAccount(it, homeUsers) }.getOrNull() }
				setAccount(id)
			}
		}
		scope.launch {
			mutable.map { it.automatic }.distinctUntilChanged()
				.flatMapLatest { on -> if (on) changes().onEach { dirty = true }.debounce(QUIET) else emptyFlow() }
				.collect { writeIfChanged() }
		}
	}

	/** Marquee is leaving the foreground: a change made in the last moments is written now. */
	fun flush() {
		if (folderUri == null) return
		scope.launch { writeIfChanged() }
	}

	/** Delete All Local Data: the folder and the bookkeeping go. The sealing key stays, so earlier backups still open. */
	fun reset() {
		stopAutomatic()
		prefs.edit { clear() }
		closeRestore()
		exportBytes = null
		pendingOpen = null
		mutable.update { State(account = it.account, hasPassphrase = it.hasPassphrase) }
	}

	private suspend fun setAccount(id: String?) {
		if (id == mutable.value.account) return
		val has = id?.let { loadKeyring(it, create = false)?.let(::hasPassphrase) } ?: false
		mutable.update { it.copy(account = id, hasPassphrase = has) }
	}

	/** The Backup page appeared. */
	fun refresh() {
		mutable.update { it.copy(problem = null) }
		val account = mutable.value.account ?: return
		scope.launch {
			loadKeyring(account, create = false)?.let { found -> mutable.update { it.copy(hasPassphrase = hasPassphrase(found)) } }
			if (folderUri != null && mutable.value.foundNewer == null) checkFolder(account)
		}
	}

	// Back up now

	/** Seals a backup and asks for a place to save it: the page opens the save picker for [State.exportName]. */
	fun backUpNow() {
		if (mutable.value.working != null) return
		scope.launch {
			val account = mutable.value.account ?: return@launch fail(noAccount())
			working("Preparing the backup")
			try {
				val keyring = loadKeyring(account, create = true)!!
				val payload = snapshot()
				val file = core.sealBackup(payload, account, keyring)
				exportBytes = file
				mutable.update { it.copy(exportName = exportName()) }
				Diag.notice(DiagCategory.App, "backup.export", "entries" to payload.entries.size, "bytes" to file.size, "passphrase" to hasPassphrase(keyring))
			} catch (e: Exception) {
				failed("backup.export.failed", e)
			} finally {
				working(null)
			}
		}
	}

	/** The save picker finished; [uri] is null when it was cancelled. */
	fun exported(uri: Uri?) {
		val bytes = exportBytes
		exportBytes = null
		mutable.update { it.copy(exportName = null) }
		if (uri == null || bytes == null) return
		scope.launch {
			try {
				withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
				val now = now()
				prefs.edit { putLong(LAST, now) }
				mutable.update { it.copy(last = now) }
				Diag.notice(DiagCategory.App, "backup.exported")
			} catch (e: Exception) {
				failed("backup.export.failed", e)
			}
		}
	}

	// Recovery passphrase

	/** Sets or changes the passphrase. Returns what to tell the person when it was refused, null when it is set. */
	suspend fun setPassphrase(phrase: String): String? {
		val account = mutable.value.account ?: return noAccount()
		working("Securing the passphrase")
		return try {
			val reply = core.setBackupPassphrase(loadKeyring(account, create = true), phrase)
			saveKeyring(account, Keyring(reply.keyring))
			mutable.update { it.copy(hasPassphrase = reply.hasPassphrase) }
			keyChanged()
			null
		} catch (e: CoreException) {
			e.message ?: DEFAULT
		} catch (e: Exception) {
			words(e)
		} finally {
			working(null)
		}
	}

	/** Backups already saved still open with the passphrase; those made from now on open only here, so automatic backup stops. */
	fun removePassphrase() {
		val account = mutable.value.account ?: return
		scope.launch {
			try {
				val keyring = loadKeyring(account, create = false) ?: return@launch
				val reply = core.removeBackupPassphrase(keyring)
				saveKeyring(account, Keyring(reply.keyring))
				mutable.update { it.copy(hasPassphrase = reply.hasPassphrase) }
				stopAutomatic()
			} catch (e: Exception) {
				failed("backup.passphrase.failed", e)
			}
		}
	}

	// Restore

	/** A backup the person picked. */
	fun restore(uri: Uri) = beginRestore { readFile(uri) }

	fun restoreFromFolder() {
		val tree = folderUri ?: return
		beginRestore {
			withContext(Dispatchers.IO) { folder.read(tree) } ?: throw BackupRefusal(core.backupMessage("notABackup"))
		}
	}

	/** A backup opened from another app (a file manager): it is read now, while the grant of the intent lasts, and the page picks it up. */
	fun open(uri: Uri) {
		scope.launch {
			try {
				pendingOpen = readFile(uri)
				mutable.update { it.copy(hasPendingOpen = true) }
				openRequests.trySend(Unit)
			} catch (e: Exception) {
				failed("backup.restore.failed", e)
			}
		}
	}

	fun takePendingOpen() {
		val file = pendingOpen ?: return
		pendingOpen = null
		mutable.update { it.copy(hasPendingOpen = false) }
		beginRestore { file }
	}

	fun submitPassphrase(phrase: String) {
		val file = pendingFile ?: return
		val account = mutable.value.account ?: return
		restoreStep(Restore.Reading)
		scope.launch {
			try {
				unlock(file, account, loadKeyring(account, create = false), phrase)
			} catch (e: CoreException) {
				if (e.failure == WRONG_PASSPHRASE) restoreStep(Restore.Passphrase(e.message ?: DEFAULT)) else failRestore(e)
			} catch (e: Exception) {
				failRestore(e)
			}
		}
	}

	/** Writes the opened backup to this device, all or nothing. */
	fun confirmRestore() {
		val backup = opened ?: return
		val account = mutable.value.account ?: return
		if (mutable.value.restore !is Restore.Review) return
		restoreStep(Restore.Applying)
		scope.launch {
			try {
				// Backups made from here on carry the passphrase that opened this one.
				backup.keyring?.let { adopted ->
					saveKeyring(account, Keyring(adopted))
					mutable.update { it.copy(hasPassphrase = true) }
				}
				val plan = RestorePlan.of(backup.payload)
				withContext(Dispatchers.IO) { store.apply(plan) }
				newest = maxOf(newest, backup.summary.createdAt)
				prefs.edit { remove(FINGERPRINT) }
				mutable.update { it.copy(foundNewer = null) }
				restoreStep(Restore.Done)
				Diag.notice(DiagCategory.App, "backup.restored", "entries" to plan.writes.size, "from" to backup.summary.platform)
			} catch (e: RestoreFailure) {
				val words = core.backupMessage(if (e.incomplete) "restoreIncomplete" else "restoreFailed")
				restoreStep(Restore.Failed(words))
				Diag.warning(DiagCategory.App, "backup.restore.failed", "reason" to if (e.incomplete) "incomplete" else "restoreFailed")
			} catch (e: Exception) {
				failRestore(e)
			}
		}
	}

	fun closeRestore() {
		pendingFile = null
		opened = null
		mutable.update { it.copy(restore = null) }
	}

	/** Ends the app so it starts again from what was restored: the models in memory were built from the old settings and would write them back. */
	fun closeApp() {
		exitProcess(0)
	}

	private fun beginRestore(read: suspend () -> ByteArray) {
		if (mutable.value.restore != null) return
		mutable.update { it.copy(problem = null, restore = Restore.Reading) }
		scope.launch {
			try {
				prepare(read())
			} catch (e: Exception) {
				failRestore(e)
			}
		}
	}

	private suspend fun prepare(file: ByteArray) {
		val account = mutable.value.account ?: throw BackupRefusal(noAccount())
		val summary = core.inspectBackup(file, account)
		if (summary.belongs == false) throw BackupRefusal(core.backupMessage("wrongAccount"))
		pendingFile = file
		try {
			unlock(file, account, loadKeyring(account, create = false), null)
		} catch (e: CoreException) {
			if (e.failure != NEEDS_PASSPHRASE) throw e
			restoreStep(Restore.Passphrase(e.message ?: DEFAULT))
		}
	}

	private suspend fun unlock(file: ByteArray, account: String, keyring: Keyring?, passphrase: String?) {
		val backup = core.openBackup(file, account, keyring, passphrase)
		if (mutable.value.restore == null) return
		opened = backup
		restoreStep(Restore.Review(backup.summary, review(backup)))
		Diag.info(DiagCategory.App, "backup.restore.opened", "from" to backup.summary.platform, "entries" to backup.payload.entries.size)
	}

	private fun review(backup: OpenedBackupReply): List<BackupLine> {
		if (backup.payload.platform != BackupPlatform.ANDROID) return backup.contents
		// Core counts the profile group's entries, and each setting is one here: say how many profiles they cover.
		val profiles = BackupScope.profileCount(backup.payload.entries)
		return backup.contents.map { line ->
			if (line.title == PROFILES_TITLE) line.copy(detail = if (profiles == 1) "1 profile" else "$profiles profiles") else line
		}
	}

	private fun failRestore(error: Throwable) {
		if (mutable.value.restore == null) return
		restoreStep(Restore.Failed(words(error)))
		Diag.warning(DiagCategory.App, "backup.restore.failed", "reason" to reason(error))
	}

	private fun restoreStep(step: Restore) = mutable.update { if (it.restore == null) it else it.copy(restore = step) }

	// Automatic backup

	/** Whether the folder picker can open: the account and the passphrase must be there. */
	suspend fun prepareAutomatic(): Boolean {
		mutable.update { it.copy(problem = null) }
		if (mutable.value.account == null) {
			fail(noAccount())
			return false
		}
		if (!mutable.value.hasPassphrase) {
			fail(PASSPHRASE_NEEDED)
			return false
		}
		return true
	}

	/** The folder the person picked. */
	fun chooseFolder(tree: Uri?) {
		val account = mutable.value.account
		if (tree == null || account == null) return
		scope.launch {
			working("Checking the folder")
			try {
				withContext(Dispatchers.IO) {
					context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
				}
				val found = existing(tree, account)
				if (found != null && !found.belongs) {
					release(tree)
					return@launch fail(OTHER_ACCOUNT)
				}
				val old = folderUri
				if (old != null && old != tree) release(old)
				val name = withContext(Dispatchers.IO) { folder.name(tree) }
				prefs.edit {
					putString(FOLDER, tree.toString())
					putString(FOLDER_NAME, name)
					remove(FINGERPRINT)
				}
				mutable.update { it.copy(automatic = true, folderName = name, automaticProblem = null, foundNewer = null) }
				Diag.notice(DiagCategory.App, "backup.auto.on", "found" to (found != null))
				if (found != null && found.createdAt > newest) {
					mutable.update { it.copy(foundNewer = found.createdAt) }
				} else {
					writeIfChanged()
				}
			} catch (e: Exception) {
				failed("backup.auto.failed", e)
			} finally {
				working(null)
			}
		}
	}

	fun stopAutomatic() {
		folderUri?.let(::release)
		prefs.edit {
			remove(FOLDER)
			remove(FOLDER_NAME)
			remove(FINGERPRINT)
			remove(LAST_AUTOMATIC)
		}
		mutable.update { it.copy(automatic = false, folderName = null, lastAutomatic = null, automaticProblem = null, foundNewer = null) }
		Diag.notice(DiagCategory.App, "backup.auto.off")
	}

	/** The person chose this device's settings over the newer backup in the folder. */
	fun replaceNewerInFolder() {
		mutable.update { it.copy(foundNewer = null) }
		prefs.edit { remove(FINGERPRINT) }
		scope.launch { writeIfChanged(replacingNewer = true) }
	}

	private fun release(tree: Uri) {
		runCatching {
			context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
		}
	}

	private suspend fun existing(tree: Uri, account: String): BackupFolderRule.Existing? {
		val file = withContext(Dispatchers.IO) { folder.read(tree) } ?: return null
		val summary = try {
			core.inspectBackup(file, account)
		} catch (_: CoreException) {
			// Not a backup: the next one replaces it.
			return null
		}
		return BackupFolderRule.Existing(summary.createdAt, summary.belongs != false)
	}

	private suspend fun checkFolder(account: String) {
		val tree = folderUri ?: return
		try {
			val found = existing(tree, account) ?: return
			if (found.belongs && found.createdAt > newest) mutable.update { it.copy(foundNewer = found.createdAt) }
		} catch (e: Exception) {
			mutable.update { it.copy(automaticProblem = words(e)) }
		}
	}

	/** Writes the folder's backup when the settings differ from what was last written. */
	private suspend fun writeIfChanged(replacingNewer: Boolean = false) {
		val tree = folderUri ?: return
		val account = mutable.value.account ?: return
		if (mutable.value.foundNewer != null || !writeLock.tryLock()) return
		try {
			dirty = false
			val keyring = loadKeyring(account, create = false)
			if (keyring == null || !hasPassphrase(keyring)) {
				mutable.update { it.copy(automaticProblem = PASSPHRASE_NEEDED) }
				return
			}
			val payload = snapshot()
			val fingerprint = core.backupFingerprint(payload, account, keyring)
			if (fingerprint != null && fingerprint == prefs.getString(FINGERPRINT, null)) return
			val now = now()
			val file = core.sealBackup(payload, account, keyring, now.toDouble())
			val current = withContext(Dispatchers.IO) { folder.read(tree) }
			val found = current?.let { bytes ->
				try {
					core.inspectBackup(bytes, account).let { BackupFolderRule.Existing(it.createdAt, it.belongs != false) }
				} catch (_: CoreException) {
					null
				}
			}
			when (val verdict = BackupFolderRule.verdict(found, newest, replacingNewer)) {
				BackupFolderRule.Verdict.OtherAccount -> {
					stopAutomatic()
					fail(OTHER_ACCOUNT)
					return
				}
				is BackupFolderRule.Verdict.Newer -> {
					mutable.update { it.copy(foundNewer = verdict.createdAt) }
					Diag.notice(DiagCategory.App, "backup.auto.newer")
					return
				}
				BackupFolderRule.Verdict.Write -> withContext(Dispatchers.IO) { folder.write(tree, file, current) }
			}
			newest = now
			prefs.edit {
				putLong(LAST, now)
				putLong(LAST_AUTOMATIC, now)
				if (fingerprint != null) putString(FINGERPRINT, fingerprint)
			}
			mutable.update { it.copy(last = now, lastAutomatic = now, automaticProblem = null) }
			Diag.info(DiagCategory.App, "backup.auto.written", "entries" to payload.entries.size, "bytes" to file.size)
		} catch (e: Exception) {
			mutable.update { it.copy(automaticProblem = words(e)) }
			Diag.warning(DiagCategory.App, "backup.auto.failed", "reason" to reason(e))
		} finally {
			writeLock.unlock()
		}
	}

	// Pieces

	private suspend fun snapshot(): BackupPayload = withContext(Dispatchers.IO) {
		val entries = store.read().sortedBy { it.id }
		BackupPayload(BackupPlatform.ANDROID, BuildConfig.VERSION_NAME, entries, BackupScope.integrationsFrom(entries))
	}

	private suspend fun readFile(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
		val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
		input.use { stream ->
			val out = java.io.ByteArrayOutputStream()
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val count = stream.read(buffer)
				if (count < 0) break
				out.write(buffer, 0, count)
				// A backup is a few hundred kilobytes; a file this large is something else.
				if (out.size() > MAX_FILE) throw BackupRefusal(core.backupMessage("notABackup"))
			}
			out.toByteArray()
		}
	}

	/** The account's keyring, made on first use when [create] is set. */
	private suspend fun loadKeyring(account: String, create: Boolean): Keyring? = keyLock.withLock {
		val found = withContext(Dispatchers.IO) { keys.read(keyName(account)) }
			?.let { text -> runCatching { Keyring(BackupJson.parseToJsonElement(text).jsonObject) }.getOrNull() }
		if (found != null || !create) return@withLock found
		val made = Keyring(core.newKeyring().keyring)
		withContext(Dispatchers.IO) { keys.write(keyName(account), made.json.toString()) }
		made
	}

	private suspend fun saveKeyring(account: String, keyring: Keyring) = keyLock.withLock {
		withContext(Dispatchers.IO) { keys.write(keyName(account), keyring.json.toString()) }
	}

	private fun hasPassphrase(keyring: Keyring): Boolean = keyring.json["passphrase"].let { it != null && it !is kotlinx.serialization.json.JsonNull }

	/** A change to the passphrase must reach the folder's file even when the settings did not change. */
	private fun keyChanged() {
		prefs.edit { remove(FINGERPRINT) }
		if (folderUri != null) flush()
	}

	private fun keyName(account: String) = "keyring.$account"

	private fun working(text: String?) = mutable.update { it.copy(working = text) }

	private fun fail(words: String) = mutable.update { it.copy(problem = words) }

	private fun failed(event: String, error: Throwable) {
		fail(words(error))
		Diag.warning(DiagCategory.App, event, "reason" to reason(error))
	}

	private suspend fun noAccount(): String = core.backupMessage("noAccount")

	private fun exportName(): String = "Marquee Backup ${java.time.LocalDate.now()}.marqueebackup"

	private fun now() = System.currentTimeMillis() / 1000

	companion object {
		private const val LAST = "last"
		private const val LAST_AUTOMATIC = "lastAutomatic"
		private const val FOLDER = "folder"
		private const val FOLDER_NAME = "folderName"
		private const val NEWEST = "newest"
		private const val FINGERPRINT = "fingerprint"
		private const val NEEDS_PASSPHRASE = "needsPassphrase"
		private const val WRONG_PASSPHRASE = "wrongPassphrase"
		private const val MAX_FILE = 32 * 1024 * 1024

		/** How long settings stay unchanged before the folder's backup is written. */
		private val QUIET = 20.seconds

		/** Core's title of the profile group's line in the review; the bridge test pins it. */
		const val PROFILES_TITLE = "Profile settings"

		const val PASSPHRASE_NEEDED = "Set a recovery passphrase first. The key that opens a backup stays on this device, so only the passphrase can open it on another one."
		private const val OTHER_ACCOUNT = "That folder holds the backup of another Plex account. Choose a different folder."
		private const val DEFAULT = "The backup could not be saved or read."

		/** The sentence to show for a failure. */
		fun words(error: Throwable): String = when {
			error is CoreException && error.kind == CoreException.Kind.Backup -> error.message ?: DEFAULT
			error is BackupRefusal -> error.message ?: DEFAULT
			error is SecurityException -> "Marquee is not allowed to use that location. Choose it again."
			error is FileNotFoundException -> "Marquee can no longer reach the folder. Choose it again."
			error is IOException && error.message?.contains("ENOSPC") == true -> "There is not enough space to save the backup."
			else -> DEFAULT
		}

		/** A name for the log: the kind of failure, never a path or a message the system wrote. */
		private fun reason(error: Throwable): String = when (error) {
			is CoreException -> error.failure ?: error.kind.name
			is RestoreFailure -> "restore"
			else -> error::class.simpleName ?: "other"
		}

	}

	private fun android.content.SharedPreferences.longOrNull(key: String): Long? = if (contains(key)) getLong(key, 0) else null
}
