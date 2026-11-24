package com.nphil.marquee.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * A LumaTV tuner can convert an antenna channel for a client that asks with `?transcode=<profile>`. The profiles come
 * from the tuner's `status.json` (`transcode.profiles`), and the best one for this tablet is chosen by [choose]. A TV
 * that publishes none is asked for [BUILT_IN] only when the channel is known to be 720 lines or fewer, so the picture is
 * never reduced. The tuner may still answer with the broadcast as it is, which plays as before. A tuner that fails the
 * request is asked for the broadcast for the rest of this app run.
 */
internal class TunerTranscode {
	class Profile(val name: String, val codec: String, val maxHeight: Int?, val progressive: Boolean, val kbps: Int? = null)

	/** [profile] null: the original stream is requested, for [reason]. [source] is the channel's known height in lines. */
	class Decision(val profile: Profile?, val reason: String, val source: Int?) {
		val asked: Boolean get() = profile != null
	}

	private val declined = ConcurrentHashMap.newKeySet<String>()
	private val heights = HashMap<String, Int>()
	private val learning = HashSet<String>()

	/**
	 * What the tune of [channel] on [tuner] asks for. A tune that does not limit the picture learns the channel's height
	 * from its pictures ([noted]); the tallest seen counts, so a slate cannot pass for the station.
	 */
	@Synchronized
	fun decide(
		isLuma: Boolean, tuner: String, channel: String, offer: List<Profile>?, canDecode: (codec: String, height: Int?) -> Boolean,
	): Decision {
		if (!isLuma) return Decision(null, "not-luma", null)
		val key = "$tuner|$channel"
		val source = heights[key]
		if (tuner in declined) return Decision(null, "declined", source)
		val chosen = choose(offer, source, canDecode)
		if (chosen.first?.maxHeight == null) {
			learning += key
			heights.remove(key)
		} else learning -= key
		return Decision(chosen.first, chosen.second, source)
	}

	/** A picture of [height] lines reached the screen of a tune of [channel] that did not limit it. */
	@Synchronized
	fun noted(height: Int, tuner: String, channel: String) {
		val key = "$tuner|$channel"
		if (key in learning) heights[key] = maxOf(heights[key] ?: 0, height)
	}

	fun decline(tuner: String) {
		declined += tuner
	}

	fun asks(tuner: String): Boolean = tuner !in declined

	companion object {
		/** The tuner's `mobile` profile: H.264, 1280x720 at most. Used while the TV publishes no profiles. */
		val BUILT_IN = Profile("mobile", "h264", 720, progressive = true)
		const val BUILT_IN_HEIGHT = 720
		val shared = TunerTranscode()

		fun ask(url: HttpUrl, profile: Profile): HttpUrl = url.newBuilder().setQueryParameter("transcode", profile.name).build()

		/**
		 * The best profile of [offer] for a channel of [source] lines, with the reason when there is none. A profile
		 * qualifies when it keeps the resolution (no limit, or a limit at or above the source; an unknown source only
		 * suits no limit), is progressive and has a decoder here; the tuner's order breaks ties. Without an [offer] it is
		 * [BUILT_IN] for a source of 720 lines or fewer.
		 */
		fun choose(offer: List<Profile>?, source: Int?, canDecode: (codec: String, height: Int?) -> Boolean): Pair<Profile?, String> {
			if (offer == null) return when {
				source == null -> null to "unknown"
				source > BUILT_IN_HEIGHT -> null to "hd"
				!canDecode(BUILT_IN.codec, source) -> null to "no-decoder"
				else -> BUILT_IN to "sd"
			}
			val best = offer.firstOrNull { p ->
				p.progressive && (p.maxHeight == null || (source != null && p.maxHeight >= source)) && canDecode(p.codec, p.maxHeight ?: source)
			}
			return best to if (best != null) "profile" else "no-profile"
		}

		/** The profiles in a tuner's `status.json`: null when it publishes none (an older TV), empty when it is off. Unknown fields are ignored. */
		fun offer(status: JsonElement?): List<Profile>? {
			val root = (status as? JsonObject)?.get("transcode") as? JsonObject ?: return null
			if ((root["enabled"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
			val list = root["profiles"] as? JsonArray ?: return null
			return list.mapNotNull { item ->
				val entry = item as? JsonObject ?: return@mapNotNull null
				val name = entry.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val codec = entry.text("codec")?.lowercase() ?: return@mapNotNull null
				Profile(
					name, codec, (entry["maxHeight"] as? JsonPrimitive)?.intOrNull,
					(entry["progressive"] as? JsonPrimitive)?.booleanOrNull == true, (entry["kbps"] as? JsonPrimitive)?.intOrNull,
				)
			}
		}

		private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
	}
}

/**
 * Whether a failed tune that asked for a profile is retried once without it: the tuner refused the request (400, 503)
 * or sent no picture. A 503 that [sharingRefusal] explains (tuner full, TV recording) is the tuner's answer, not the
 * conversion's.
 */
internal fun retriesWithoutTranscode(asked: Boolean, status: Int?, noVideo: Boolean, sharingRefusal: Boolean): Boolean =
	asked && !sharingRefusal && (noVideo || status == 400 || status == 503)

/**
 * When a LumaTV stream ends under a viewer who did not change the channel (the converter steps aside, the TV restarts
 * it), the same channel is reopened, at most [LIMIT] times in [WINDOW_MS]. A reopen that ends again within [QUICK_MS] is
 * followed by one more without the conversion; after the limit playback stops.
 */
internal class TunerReopen {
	class Decision(val reopen: Boolean, val n: Int, val withoutTranscode: Boolean)

	private val reopened = ArrayList<Long>()

	/** The stream ended at [nowMs] (monotonic). */
	fun ended(nowMs: Long): Decision {
		reopened.removeAll { nowMs - it > WINDOW_MS }
		if (reopened.size >= LIMIT) return Decision(false, reopened.size, false)
		val endedQuickly = reopened.lastOrNull()?.let { nowMs - it <= QUICK_MS } == true
		reopened += nowMs
		return Decision(true, reopened.size, withoutTranscode = reopened.size == 2 && endedQuickly)
	}

	companion object {
		const val LIMIT = 2
		const val WINDOW_MS = 60_000L
		const val QUICK_MS = 5_000L
	}
}
