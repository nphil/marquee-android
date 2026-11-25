package com.nphil.marquee.backup

/** Where the settings a backup carries are kept; the restore reads and writes them only through this. */
internal interface BackupStore {
	/** Every stored value that is in scope. */
	fun read(): List<BackupEntry>

	/** Whether [entry] can be written here as it is. Nothing is written unless every entry passes. */
	fun validate(entry: BackupEntry): Boolean

	fun write(entry: BackupEntry)

	fun remove(entry: BackupEntry)
}

/** What a restore changes: the entries it writes, and whether in-scope values the backup lacks are removed. */
internal class RestorePlan(val writes: List<BackupEntry>, val replacesAll: Boolean) {
	companion object {
		/**
		 * An Android backup replaces everything in scope. An iPhone's carries only its integrations, which are
		 * written over the ones here, and nothing else on this device changes.
		 */
		fun of(payload: BackupPayload): RestorePlan =
			if (payload.platform == BackupPlatform.ANDROID) {
				RestorePlan(payload.entries.filter(BackupScope::covers), replacesAll = true)
			} else {
				RestorePlan(BackupScope.entriesFrom(payload.integrations), replacesAll = false)
			}
	}
}

/** A restore that did not finish. [incomplete] is true when the undo failed too, so some values are the backup's. */
internal class RestoreFailure(val incomplete: Boolean, cause: Throwable? = null) : Exception(cause)

/**
 * Applies [plan] all or nothing: every entry is checked before any is written, secrets are written first (the
 * likeliest to be refused), and a failure puts back every value already touched.
 */
internal fun BackupStore.apply(plan: RestorePlan) {
	if (!plan.writes.all(::validate)) throw RestoreFailure(incomplete = false)
	val before = try {
		read().associateBy { it.id }
	} catch (e: Exception) {
		throw RestoreFailure(incomplete = false, cause = e)
	}
	val written = plan.writes.associateBy { it.id }
	val removals = if (plan.replacesAll) before.values.filter { it.id !in written && BackupScope.covers(it) } else emptyList()
	val changes = plan.writes.filter { before[it.id] != it }
		.sortedBy { if (BackupScope.secretName(it.store) != null) 0 else 1 }

	val touched = mutableListOf<BackupEntry>()
	try {
		for (entry in changes) {
			touched += entry
			write(entry)
		}
		for (entry in removals) {
			touched += entry
			remove(entry)
		}
	} catch (e: Exception) {
		var restored = true
		for (entry in touched.asReversed()) {
			try {
				val old = before[entry.id]
				if (old != null) write(old) else remove(entry)
			} catch (_: Exception) {
				restored = false
			}
		}
		throw RestoreFailure(incomplete = !restored, cause = e)
	}
}
