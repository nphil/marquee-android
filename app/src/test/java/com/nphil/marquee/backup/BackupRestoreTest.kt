package com.nphil.marquee.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupRestoreTest {
	private class FakeStore(initial: List<BackupEntry>) : BackupStore {
		val values = initial.associateBy { it.id }.toMutableMap()
		var failWriteOf: String? = null
		var failAfterUndoStarts = false
		var undoing = false
		val order = mutableListOf<String>()

		override fun read() = values.values.toList()
		override fun validate(entry: BackupEntry) = BackupScope.covers(entry)
		override fun write(entry: BackupEntry) {
			order += "write ${entry.key}"
			if (undoing && failAfterUndoStarts) error("undo refused")
			if (!undoing && entry.key == failWriteOf) {
				undoing = true
				error("refused")
			}
			values[entry.id] = entry
		}

		override fun remove(entry: BackupEntry) {
			order += "remove ${entry.key}"
			if (undoing && failAfterUndoStarts) error("undo refused")
			values.remove(entry.id)
		}
	}

	private fun setting(key: String, value: Any) = BackupScope.entry(BackupScope.SETTINGS, key, value)!!
	private fun secret(key: String, value: String) = BackupScope.entry(BackupScope.secretStore("arr_services"), key, value)!!

	private val device = listOf(setting("theme.light", "old"), setting("playback.a.quality", 4), setting("theme.dark", "gone"))

	@Test
	fun anAndroidBackupReplacesEverythingInScope() {
		val store = FakeStore(device)
		store.apply(RestorePlan(listOf(setting("theme.light", "new"), setting("playback.a.quality", 4), setting("tracks.s.u.t", "x")), replacesAll = true))
		assertEquals("new", store.values.getValue(setting("theme.light", "").id).string())
		assertEquals(setOf("theme.light", "playback.a.quality", "tracks.s.u.t"), store.values.values.map { it.key }.toSet())
	}

	@Test
	fun anIphonesBackupAddsItsIntegrationsAndRemovesNothing() {
		val store = FakeStore(device)
		val plan = RestorePlan.of(BackupPayload("ios", "1", emptyList(), mapOf("bazarr" to BackupIntegration("https://b.example", "k"))))
		store.apply(plan)
		assertEquals(device.size + 1, store.values.size)
		assertTrue(store.values.values.any { it.key == "bazarr" })
	}

	@Test
	fun secretsAreWrittenFirstAndNothingOutsideTheScopeIsTouched() {
		val store = FakeStore(device)
		val outside = BackupEntry("settings", "plex.clientIdentifier", kind = "string", value = kotlinx.serialization.json.JsonPrimitive("x"))
		val plan = RestorePlan.of(BackupPayload("android", "1", listOf(setting("theme.light", "n"), secret("radarr", "{}"), outside)))
		store.apply(plan)
		assertEquals("write radarr", store.order.first())
		assertFalse(store.order.any { it.contains("clientIdentifier") })
	}

	@Test
	fun anEntryTheStoreRefusesStopsTheRestoreBeforeAnythingIsWritten() {
		val store = object : BackupStore {
			val inner = FakeStore(device)
			override fun read() = inner.read()
			override fun validate(entry: BackupEntry) = entry.key != "bad"
			override fun write(entry: BackupEntry) = inner.write(entry)
			override fun remove(entry: BackupEntry) = inner.remove(entry)
		}
		try {
			store.apply(RestorePlan(listOf(setting("theme.light", "new"), setting("bad", 1)), replacesAll = true))
			fail()
		} catch (e: RestoreFailure) {
			assertFalse(e.incomplete)
		}
		assertTrue(store.inner.order.isEmpty())
	}

	@Test
	fun aFailureHalfwayPutsEveryValueBack() {
		val store = FakeStore(device)
		store.failWriteOf = "theme.light"
		try {
			store.apply(RestorePlan(listOf(secret("radarr", "{}"), setting("tracks.s.u.t", "x"), setting("theme.light", "new")), replacesAll = true))
			fail()
		} catch (e: RestoreFailure) {
			assertFalse(e.incomplete)
		}
		assertEquals(device.associateBy { it.id }, store.values)
	}

	@Test
	fun anUndoThatFailsToo_isSaidToBeIncomplete() {
		val store = FakeStore(device)
		store.failWriteOf = "theme.light"
		store.failAfterUndoStarts = true
		try {
			store.apply(RestorePlan(listOf(secret("radarr", "{}"), setting("theme.light", "new")), replacesAll = false))
			fail()
		} catch (e: RestoreFailure) {
			assertTrue(e.incomplete)
		}
	}

	@Test
	fun valuesThatAlreadyMatchAreNotWritten() {
		val store = FakeStore(device)
		store.apply(RestorePlan(device, replacesAll = true))
		assertTrue(store.order.isEmpty())
		assertNull(store.values.values.firstOrNull { it.key == "x" })
	}
}
