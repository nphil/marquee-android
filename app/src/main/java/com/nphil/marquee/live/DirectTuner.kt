package com.nphil.marquee.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlexServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Only a physical, attached LAN may read a tuner. VPNs can make the phone look like Plex to a one-tuner TV. */
internal class DirectTuner(private val context: Context, private val http: OkHttpClient) {
	private val connections = context.getSystemService(ConnectivityManager::class.java)
	private val memory = TunerMemory<Site, TunerCandidate>(SystemClock::elapsedRealtime)

	suspend fun probe(channel: GuideChannel): TunerCandidate? =
		probeTunerIfAllowed(canReadLocalTuner(context)) { probeAttached(channel) }

	private suspend fun probeAttached(channel: GuideChannel): TunerCandidate? {
		val uuid = channel.device.text("uuid")?.takeIf { it.startsWith(HDHOMERUN) } ?: return null
		val base = channel.device.text("uri")?.toHttpUrlOrNull()?.takeIf {
			it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
		} ?: return null
		val network = connections.activeNetwork ?: return null
		val caps = connections.getNetworkCapabilities(network) ?: return null
		if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
			!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
		val links = connections.getLinkProperties(network) ?: return null
		val routes = links.routes.filter { !it.hasGateway() && it.destination.prefixLength > 0 }
		fun attached(address: InetAddress) = !address.isLoopbackAddress && !address.isMulticastAddress &&
			!address.isAnyLocalAddress && routes.any { it.matches(address) }
		// OkHttp bypasses its DNS resolver for IP literals. Check those here as well.
		val literal = try { InetAddresses.parseNumericAddress(base.host) } catch (_: IllegalArgumentException) { null }
		if (literal != null && !attached(literal)) return null

		val site = Site(network, uuid, base)
		memory.recall(site)?.let { return it.tuner }
		val bound = http.newBuilder()
			.proxy(Proxy.NO_PROXY).socketFactory(network.socketFactory)
			.dns(object : Dns {
				override fun lookup(hostname: String): List<InetAddress> =
					network.getAllByName(hostname).filter(::attached).ifEmpty { throw UnknownHostException(hostname) }
			})
			.cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).cache(null)
			.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
			.callTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
			.build()
		val tuner = TunerCandidate.discover(bound, base, uuid, links.linkAddresses.mapNotNull { it.address.hostAddress }.toSet())
		memory.keep(site, tuner)
		return tuner
	}

	/** A stream from [tuner] could not connect or went silent: for a minute it is not asked again, as on the iPhone. */
	fun lost(tuner: TunerCandidate) = memory.lost(tuner)

	/** What a remembered answer holds for: this tuner, at this address, on this network. */
	private data class Site(val network: Network, val uuid: String, val base: HttpUrl)
}

internal class TunerCandidate private constructor(
	private val http: OkHttpClient,
	private val base: HttpUrl,
	val count: Int,
	val isLuma: Boolean,
	private val clientAddresses: Set<String>,
	private val lineup: JsonArray?,
) {
	fun lineup(number: String): JsonObject? = lineup?.firstNotNullOfOrNull { item ->
		(item as? JsonObject)?.takeIf { it.text("GuideNumber") == number }
	}

	suspend fun availability(): TunerAvailability {
		if (!isLuma) return TunerAvailability.Unreported
		return availability(json(http, base, "status.json"))
	}

	private fun checkAdmission(track: (Call) -> Unit): TunerAvailability {
		val check = http.newCall(request(base, "status.json"))
		check.timeout().timeout(500, TimeUnit.MILLISECONDS)
		track(check)
		return availability(try { check.execute().use(::readJson) } catch (_: IOException) { null })
	}

	private fun availability(document: JsonElement?): TunerAvailability {
		val status = document as? JsonObject ?: return TunerAvailability.Unreported
		val holder = status["holder"]
		if (holder == JsonNull) return TunerAvailability.Free
		if (holder !is JsonObject) return TunerAvailability.Unreported
		val name = holder.text("name")
		if (name == TUNER_AGENT && holder.text("ip")?.let(::canonicalIP)?.let { it in clientAddresses } == true)
			return TunerAvailability.Ours
		return TunerAvailability.Held(name?.takeIf { it.isNotBlank() }?.let { "The tuner is in use by ${it.take(80)}." }
			?: "The tuner is already in use.")
	}

	/** Plex is a different client. Wait for the TV to see the outgoing Marquee socket close before it asks. */
	suspend fun afterRelease(): TunerAvailability = withTimeoutOrNull(5_000) {
		var state = availability()
		while (state == TunerAvailability.Ours) {
			delay(150)
			state = availability()
		}
		state
	} ?: TunerAvailability.Releasing

	fun open(url: String): DirectTunerFeed? {
		val stream = url.toHttpUrlOrNull()?.takeIf { it.sameOrigin(base) && it.username.isEmpty() && it.password.isEmpty() }
			?: return null
		return DirectTunerFeed(this, stream)
	}

	fun streamClient(admission: (Call) -> Unit, claim: (TunerAvailability) -> Unit): OkHttpClient =
		http.newBuilder().addInterceptor { chain ->
			var request = chain.request()
			var response = chain.proceed(request)
			var redirects = 0
			while (redirects < 5 && response.isRedirect) {
				val next = response.header("Location")?.let(request.url::resolve)
					?.takeIf { it.sameOrigin(base) && it.username.isEmpty() && it.password.isEmpty() } ?: break
				response.close()
				request = request.newBuilder().url(next).build()
				response = chain.proceed(request)
				redirects++
			}
			// A busy LumaTV can send 200 with a playable slate without granting a tuner lease.
			if (isLuma && response.isSuccessful) {
				claim(TunerAvailability.Unreported)
				claim(checkAdmission(admission))
			}
			response
		}.build()

	companion object {
		suspend fun discover(http: OkHttpClient, base: HttpUrl, uuid: String, clientAddresses: Set<String>): TunerCandidate? {
			val found = json(http, base, "discover.json") as? JsonObject ?: return null
			val id = found.text("DeviceID")?.takeIf { it.isNotBlank() } ?: return null
			if (!uuid.endsWith("/$id", ignoreCase = true)) return null
			val count = (found["TunerCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 1
			val lineup = json(http, base, "lineup.json") as? JsonArray
			return TunerCandidate(http, base, count, found.text("DeviceAuth") == "lumatv", clientAddresses.mapNotNull(::canonicalIP).toSet(), lineup)
		}

		private suspend fun json(http: OkHttpClient, base: HttpUrl, path: String): JsonElement? = withTimeoutOrNull(500) {
			suspendCancellableCoroutine { result ->
				val call = http.newCall(request(base, path))
				result.invokeOnCancellation { call.cancel() }
				call.enqueue(object : Callback {
					override fun onFailure(call: Call, e: IOException) = result.resume(null)
					override fun onResponse(call: Call, response: Response) {
						result.resume(response.use(::readJson))
					}
				})
			}
		}

		private fun request(base: HttpUrl, path: String): Request =
			Request.Builder().url(base.newBuilder().addPathSegment(path).build()).header("User-Agent", TUNER_AGENT).build()

		private fun readJson(response: Response): JsonElement? = try {
			val source = response.body?.source()
			if (!response.isSuccessful || source == null || source.request(1_048_577L)) null
			else CoreJson.parseToJsonElement(source.readUtf8())
		} catch (_: IOException) {
			null
		} catch (_: IllegalArgumentException) {
			null
		}
	}
}

internal sealed interface TunerAvailability {
	data object Free : TunerAvailability
	data object Ours : TunerAvailability
	data object Unreported : TunerAvailability
	data class Held(val message: String) : TunerAvailability
	data object Releasing : TunerAvailability
}

/** This one HTTP socket is the tuner lease; cancel it even if Media3 has not finished opening its source. */
@OptIn(UnstableApi::class)
internal class DirectTunerFeed(val tuner: TunerCandidate, private val url: HttpUrl) : Call.Factory {
	private val client = tuner.streamClient(::trackCheck) { admission = it }
	private var call: Call? = null
	private var check: Call? = null
	@Volatile private var admission: TunerAvailability? = null

	/** For the log: what the tuner said about the lease, `none` before it said anything. */
	val admissionName: String get() = admission?.let { it::class.simpleName } ?: "none"

	val refusal: String?
		get() = when (val state = admission) {
			is TunerAvailability.Held -> state.message
			TunerAvailability.Free -> "The tuner is unavailable; the TV may be using it."
			TunerAvailability.Unreported -> "The tuner did not confirm it could start this channel."
			else -> null
		}
	private var stopped = false

	/** A listener can deliver multiple errors for one tune; only the first can end or replace it. */
	@Synchronized
	fun recover(problem: DirectProblem): DirectProblem? {
		if (stopped) return null
		stop()
		return when (problem) {
			is DirectProblem.Refused -> problem
			DirectProblem.Fallback -> refusal?.let { DirectProblem.Refused(it, "no_claim") } ?: problem
		}
	}

	fun endProblem(): DirectProblem = if (tuner.isLuma)
		DirectProblem.Refused(refusal ?: "The tuner ended this live stream.", "tuner_ended")
	else DirectProblem.Fallback

	@Synchronized
	override fun newCall(request: Request): Call = client.newCall(request).also {
		if (stopped || call != null) it.cancel() else call = it
	}

	@Synchronized
	private fun trackCheck(next: Call) {
		if (stopped) next.cancel() else check = next
	}

	@Synchronized
	fun stop() {
		stopped = true
		call?.cancel()
		call = null
		check?.cancel()
		check = null
	}

	fun source(about: MediaMetadata): MediaSource = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(this).setUserAgent(TUNER_AGENT))
		.setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
			// A retry is another GET /auto/v… and can replace a live tuner session.
			override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
		})
		.createMediaSource(MediaItem.Builder().setUri(url.toString()).setMediaMetadata(about).build())
}

@OptIn(UnstableApi::class)
internal fun directProblem(errorCode: Int, cause: Throwable?): DirectProblem {
	val refused = generateSequence(cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
	if (refused != null) {
		val marker = refused.headerFields.entries.firstOrNull { it.key.equals("X-HDHomeRun-Error", ignoreCase = true) }
			?.value?.firstOrNull()
		if (marker == null && refused.responseCode in 300..399) return DirectProblem.Fallback
		return DirectProblem.Refused(tunerHttpRefusal(refused.responseCode, marker, refused.responseBody))
	}
	return if (errorCode in 2000..4999) DirectProblem.Fallback
	else DirectProblem.Refused("The tuner stream stopped unexpectedly.", "unsupported_error")
}

/** The connection to the tuner failed or timed out, as against a tuner that answered with a stream this tablet could not play. */
internal fun tunerLost(errorCode: Int): Boolean =
	errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
		errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

internal fun tunerHttpRefusal(status: Int, marker: String?, body: ByteArray): String {
	val message = body.decodeToString(endIndex = minOf(body.size, 240))
		.lineSequence().first().trim().takeUnless { it.isBlank() || '<' in it }
	return message ?: when {
		status == 403 || marker?.startsWith("811") == true -> "This channel is protected."
		status == 503 && marker == null || status == 409 || status == 423 || marker?.startsWith("805") == true ->
			"The tuner is already in use."
		else -> marker?.take(120) ?: "The tuner refused the channel."
	}
}

internal sealed interface DirectProblem {
	data object Fallback : DirectProblem
	data class Refused(val message: String, val reason: String = "http_refusal") : DirectProblem
}

@Serializable
internal class DirectPlan(val path: Path, val reason: String, val url: String? = null, val handover: RecordingHandover? = null) {
	@Serializable
	enum class Path { @SerialName("direct") Direct, @SerialName("plex") Plex, @SerialName("protected") Protected }
}

@Serializable
internal class RecordingHandover(val releaseAt: Double, val number: String? = null, val sameChannel: Boolean)

internal suspend fun MarqueeCore.planDirect(
	server: PlexServer, remembered: String?, channel: GuideChannel, tuner: TunerCandidate,
): DirectPlan = call("live.direct", serverParams(server, remembered) {
	put("channel", CoreJson.encodeToJsonElement(GuideChannel.serializer(), channel))
	put("tunerCount", tuner.count)
	tuner.lineup(channel.number)?.let { put("lineup", it) }
})

internal fun canReadLocalTuner(context: Context): Boolean = permitsDirectTuner(Build.VERSION.SDK_INT) {
	ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

internal inline fun permitsDirectTuner(sdk: Int, granted: () -> Boolean): Boolean = sdk < 37 || granted()

internal inline fun probeTunerIfAllowed(allowed: Boolean, probe: () -> TunerCandidate?): TunerCandidate? =
	if (allowed) probe() else null

private const val HDHOMERUN = "device://tv.plex.grabbers.hdhomerun/"
private const val TUNER_AGENT = "Marquee"

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port

private fun canonicalIP(value: String): String? {
	val address = value.substringBefore('%')
	return (if (':' in address) "http://[$address]/" else "http://$address/").toHttpUrlOrNull()?.host
}
