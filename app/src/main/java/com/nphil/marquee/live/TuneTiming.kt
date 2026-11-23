package com.nphil.marquee.live

import android.os.SystemClock

/**
 * Where the time goes between a channel tap and its first picture on the direct route. Each stage is stamped once, in
 * milliseconds since the tap, and the whole set is logged as one `live.tune.timing` line when the first frame shows.
 * Stages arrive from the main, OkHttp and playback threads.
 */
internal class TuneTiming(private val clock: () -> Long = SystemClock::elapsedRealtime) {
	enum class Stage(val field: String) {
		/** The tuner is probed, the codecs and the recording schedule are checked, and the route is chosen. */
		Route("route_ms"),

		/** The stream request is about to go out. */
		Request("request_ms"),

		/** The tuner's response headers are in. */
		Headers("headers_ms"),

		/** A LumaTV's lease check, made before the response is handed on, has answered. */
		Admitted("admitted_ms"),

		/** The first bytes of the transport stream. */
		FirstBytes("first_bytes_ms"),

		/** PAT and PMT are parsed and every track has its format. */
		Tracks("tracks_ms"),

		/** The first decoded picture is ready to be put on the screen. */
		Decoded("decoded_ms"),

		/** The first picture is on the screen. */
		Rendered("rendered_ms"),
	}

	private val start = clock()
	private val at = LongArray(Stage.entries.size) { -1 }

	/** Stamps [stage] now unless it already has a time; true when it did. */
	@Synchronized
	fun mark(stage: Stage): Boolean {
		if (at[stage.ordinal] >= 0) return false
		at[stage.ordinal] = clock() - start
		return true
	}

	@Synchronized
	fun has(stage: Stage): Boolean = at[stage.ordinal] >= 0

	/** The stages reached, in order, as log fields. */
	@Synchronized
	fun fields(): Array<Pair<String, Long>> = Stage.entries.filter { at[it.ordinal] >= 0 }.map { it.field to at[it.ordinal] }.toTypedArray()
}
