package com.nphil.marquee.fakeplex

import android.content.Context
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.core.PlexConnection
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.fakeplex.router.FakePlexRouter
import com.nphil.marquee.fakeplex.router.FakeReply
import com.nphil.marquee.fakeplex.router.FakeRequest
import com.nphil.marquee.fakeplex.router.Fixtures
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The longest a [FakePlexServer.Hold] keeps a request, so a test that forgets to release one cannot hang the run. */
private const val HOLD_SECONDS = 60L

/**
 * The iPhone app's fake Plex (`core/UITests/FakePlex/`) on the emulator: a [MockWebServer] answering from the fixture
 * tree with [FakePlexRouter]'s rules, and an [OkHttpClient] whose interceptor sends every request to it, whatever its
 * host. Core's real code runs unchanged over `NativeCore.install(NativeTransport(client))` ([install]).
 *
 * The app's fixed fake hosts need no mapping: the router picks the fixture folder from the host the app asked for
 * (`plex.tv`, `discover.provider.plex.tv`, `seerr...`, `bazarr...`, anything else is the Plex server), so a test for
 * Seerr just uses `http://seerr.local:5055` and finds its files under `seerr/`. `hosts.json` lists the hosts that
 * cannot be reached; a request to one fails to connect, as it does on the iPhone.
 *
 * The fixtures are the androidTest assets (`assets.srcDir` of `core/UITests/FakePlex` in app/build.gradle.kts), so a
 * file at `core/UITests/FakePlex/pms/library/metadata/105.json` is the asset `pms/library/metadata/105.json`. Writes
 * and watch marks are remembered per [FakePlexServer], so each test starts from untouched fixtures; the files never
 * change. [requests] lists what the app asked for as `"METHOD url"` with the URL as the app wrote it, in order.
 */
class FakePlexServer private constructor(fixtures: Fixtures) {
	val router = FakePlexRouter(fixtures)

	private val log = CopyOnWriteArrayList<String>()

	private val mock = MockWebServer().apply {
		dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
		}
		start()
	}

	/** Every request the app made, `"GET http://192.0.2.10:32400/:/scrobble?identifier=...&key=108"`, oldest first. */
	val requests: List<String> get() = log.toList()

	/** Requests whose URL path is [path], as `"METHOD url"`; narrow by [method] or [host] when needed. */
	fun requestsTo(path: String, method: String? = null, host: String? = null): List<String> = log.filter { entry ->
		val (m, url) = entry.split(' ', limit = 2)
		val parsed = url.toHttpUrl()
		("/" + parsed.pathSegments.joinToString("/")) == path && (method == null || m == method) && (host == null || parsed.host == host)
	}

	fun clearRequests() = log.clear()

	private val holds = CopyOnWriteArrayList<Hold>()

	/**
	 * Keeps every request whose URL [matches] waiting, as a slow network does, until the returned [Hold] is released:
	 * a test that films a page opening decides at which frame an answer arrives.
	 */
	fun hold(matches: (HttpUrl) -> Boolean): Hold = Hold(matches).also { holds += it }

	/** What [hold] keeps back. */
	class Hold internal constructor(internal val matches: (HttpUrl) -> Boolean) {
		private val open = CountDownLatch(1)

		/** Lets the requests waiting for it, and any that come later, through. */
		fun release() = open.countDown()

		internal fun await() {
			open.await(HOLD_SECONDS, TimeUnit.SECONDS)
		}
	}

	/** Routes every request to the fake; share it with `NativeTransport`, Coil or Media3 as the test needs. */
	val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Rewrite()).build()

	/** The fixture tree's Plex server, "Living Room", at [SERVER_URL]. */
	val server = PlexServer(
		name = "Living Room",
		clientIdentifier = "fake-server",
		accessToken = SERVER_TOKEN,
		isOwned = true,
		isHome = true,
		isPresent = true,
		httpsRequired = false,
		connections = listOf(PlexConnection(uri = SERVER_URL, isLocal = true)),
	)

	/** The address a screen remembers for [server]. */
	val remembered: String = SERVER_URL

	/** An account (plex.tv) token; the fixtures take any. */
	val token: String = ACCOUNT_TOKEN

	/** The verified owner of the fixture credentials, as returned by the fixture's /api/v2/user. */
	val profile = PlexHomeUser(1, "fake-owner", "Jordan", isAdmin = true, isProtected = false, isRestricted = false, isGuest = false)

	/** Makes Core's transport this fake's [client]. */
	fun install() = NativeCore.install(NativeTransport(client))

	fun stop() {
		holds.forEach { it.release() }
		mock.shutdown()
	}

	private fun answer(request: RecordedRequest): MockResponse {
		val url = request.requestUrl
		val original = request.getHeader(ORIGINAL)
		if (url == null || original == null) return MockResponse().setResponseCode(400)
		val target = original.toHttpUrl()
		log += "${request.method} $original"
		val headers = request.headers.toMultimap().mapValues { it.value.first() }
		val reply = router.answer(
			FakeRequest(
				method = request.method.orEmpty(),
				host = target.host,
				path = "/" + target.pathSegments.joinToString("/"),
				query = (0 until target.querySize).map { target.queryParameterName(it) to target.queryParameterValue(it) },
				headers = headers,
			),
		) ?: return MockResponse().setResponseCode(502)
		return response(reply)
	}

	private fun response(reply: FakeReply) = MockResponse().apply {
		setResponseCode(reply.status)
		setHeader("Content-Type", reply.contentType)
		reply.headers.forEach { (name, value) -> setHeader(name, value) }
		setBody(Buffer().write(reply.body))
	}

	private inner class Rewrite : Interceptor {
		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			if (!router.isReachable(request.url.host)) throw ConnectException("${request.url.host} is unreachable in the fixtures")
			holds.firstOrNull { it.matches(request.url) }?.await()
			val target: HttpUrl = request.url.newBuilder().scheme("http").host("127.0.0.1").port(mock.port).build()
			return chain.proceed(request.newBuilder().url(target).header(ORIGINAL, request.url.toString()).build())
		}
	}

	companion object {
		const val SERVER_URL = "http://192.0.2.10:32400"
		const val SERVER_TOKEN = "fake-server-token"
		const val ACCOUNT_TOKEN = "fake-account-token"

		private const val ORIGINAL = "X-FakePlex-Original"

		/** Serves the fixtures bundled in the androidTest APK; pass `InstrumentationRegistry.getInstrumentation().context`. */
		fun start(context: Context): FakePlexServer = FakePlexServer { path ->
			try {
				context.assets.open(path).use { it.readBytes() }
			} catch (_: IOException) {
				null
			}
		}

		/** Serves any fixture source, for tests of the harness itself. */
		fun start(fixtures: Fixtures): FakePlexServer = FakePlexServer(fixtures)
	}
}
