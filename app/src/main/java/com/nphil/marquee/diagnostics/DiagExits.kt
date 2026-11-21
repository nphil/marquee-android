package com.nphil.marquee.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * How the previous run of the app ended, from the system's own record (Android 11 and later): a crash in
 * native code, an ANR, a kill for memory. The first run only notes the time, so what happened before the
 * log existed is not reported as new.
 */
internal object DiagExits {
	private const val PREFS = "diagnostics-exits"
	private const val SEEN = "seen"
	private const val FRAMES = 12
	private const val TRACE_BYTES = 256 * 1024
	private const val KB_PER_MB = 1024L

	fun report(context: Context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) read(context)
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun read(context: Context) {
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		if (!prefs.contains(SEEN)) {
			prefs.edit { putLong(SEEN, System.currentTimeMillis()) }
			return
		}
		val seen = prefs.getLong(SEEN, 0)
		val exits = try {
			context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 0).orEmpty()
		} catch (_: RuntimeException) {
			return
		}
		val fresh = exits.filter { it.timestamp > seen }.sortedBy { it.timestamp }
		fresh.forEach(::log)
		fresh.lastOrNull()?.let { newest -> prefs.edit { putLong(SEEN, newest.timestamp) } }
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun log(info: ApplicationExitInfo) {
		Diag.event(
			level(info.reason), DiagCategory.App, "previous.exit",
			"reason" to reasonName(info.reason),
			"status" to info.status,
			"importance" to importanceName(info.importance),
			"pssMB" to info.pss / KB_PER_MB,
			"rssMB" to info.rss / KB_PER_MB,
			"at" to info.timestamp,
			"description" to info.description,
		)
		if (info.reason == ApplicationExitInfo.REASON_ANR) {
			anrFrames(trace(info)).forEach { Diag.info(DiagCategory.App, "previous.anr.frame", "frame" to it) }
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun trace(info: ApplicationExitInfo): String = try {
		info.traceInputStream?.use { stream ->
			val buffer = ByteArray(TRACE_BYTES)
			var size = 0
			while (size < buffer.size) {
				val read = stream.read(buffer, size, buffer.size - size)
				if (read < 0) break
				size += read
			}
			String(buffer, 0, size, Charsets.UTF_8)
		}.orEmpty()
	} catch (_: java.io.IOException) {
		""
	}

	/** The main thread's frames from an ANR trace: the lines after its `"main"` header, up to the next thread. */
	fun anrFrames(trace: String, limit: Int = FRAMES): List<String> =
		trace.lineSequence()
			.dropWhile { !it.startsWith("\"main\"") }
			.drop(1)
			.takeWhile { it.isNotBlank() }
			.map { it.trim() }
			.filter { it.startsWith("at ") }
			.take(limit)
			.toList()

	fun reasonName(reason: Int): String = when (reason) {
		1 -> "exit self"
		2 -> "signaled"
		3 -> "low memory"
		4 -> "crash"
		5 -> "native crash"
		6 -> "ANR"
		7 -> "initialization failure"
		8 -> "permission change"
		9 -> "excessive resource usage"
		10 -> "user requested"
		11 -> "user stopped"
		12 -> "dependency died"
		13 -> "other"
		14 -> "freezer"
		15 -> "package state change"
		16 -> "package updated"
		else -> "unknown"
	}

	/** What deserves attention in a later read of the log: the app's own faults and memory kills. */
	fun level(reason: Int): DiagLevel = when (reason) {
		3, 4, 5, 6, 7, 9 -> DiagLevel.Warning
		2, 13, 14, 0 -> DiagLevel.Notice
		else -> DiagLevel.Info
	}

	fun importanceName(importance: Int): String = when (importance) {
		100 -> "foreground"
		125 -> "foreground service"
		200 -> "visible"
		230 -> "perceptible"
		300 -> "service"
		325 -> "top sleeping"
		400 -> "cached"
		1000 -> "gone"
		else -> importance.toString()
	}
}
