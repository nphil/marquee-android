package com.nphil.marquee

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.core.ClientIdentity
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.core.PlexConnection
import com.nphil.marquee.core.PlexServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * Runs MarqueeCore's real connection probe through the Swift bridge on the device: Core asks every
 * address for /identity over the app's OkHttp transport and keeps the one that is this server.
 */
@RunWith(AndroidJUnit4::class)
class BridgeTest {
	private val plex = MockWebServer().apply {
		dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest) = if (request.path == "/identity") {
				MockResponse()
					.setHeader("Content-Type", "application/json")
					.setBody("""{"MediaContainer":{"size":0,"machineIdentifier":"bridge-test"}}""")
			} else {
				MockResponse().setResponseCode(404)
			}
		}
	}

	@After
	fun tearDown() = plex.shutdown()

	@Test
	fun connectPicksTheAddressThatAnswersAsThisServer() = runBlocking {
		val refused = ServerSocket(0).use { it.localPort }
		val answering = plex.url("/").toString().trimEnd('/')
		NativeCore.install(NativeTransport(OkHttpClient()))
		val core = MarqueeCore {
			ClientIdentity("bridge-test-client", "Marquee", "test", "Android", "test", "emulator", "emulator")
		}
		val server = PlexServer(
			name = "Bridge Test",
			clientIdentifier = "bridge-test",
			accessToken = "token",
			isOwned = true,
			isHome = false,
			isPresent = true,
			httpsRequired = false,
			connections = listOf(
				PlexConnection(uri = "http://127.0.0.1:$refused", isLocal = true),
				PlexConnection(uri = answering, isLocal = true),
			),
		)

		val reach = core.connect(server, remembered = null)

		Log.i("MarqueeBridgeTest", "Swift libraries loaded in ${NativeCore.loadMillis} ms; probe: ${reach.lines}")
		assertEquals(answering, reach.connection?.uri)
		assertTrue(reach.lines.isNotEmpty())
	}
}
