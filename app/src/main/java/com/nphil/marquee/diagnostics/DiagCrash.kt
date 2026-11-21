package com.nphil.marquee.diagnostics

import kotlinx.serialization.json.JsonObject

/** What an uncaught exception leaves in the log: one error, then the top of its stack. */
internal object DiagCrash {
	private const val FRAMES = 8

	fun events(thread: String, error: Throwable, nowMillis: Long): List<JsonObject> {
		val events = ArrayList<JsonObject>()
		events += DiagWire.event(
			nowMillis, DiagLevel.Error, DiagCategory.App, "crash",
			arrayOf(
				"thread" to thread,
				"exception" to error.javaClass.name,
				"message" to error.message,
				"cause" to error.cause?.let(DiagWire::describe),
			),
		)
		for (frame in error.stackTrace.take(FRAMES)) {
			events += DiagWire.event(nowMillis, DiagLevel.Error, DiagCategory.App, "crash.frame", arrayOf("frame" to frame.toString()))
		}
		return events
	}
}
