package com.nphil.marquee.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.container
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.downloads.DownloadScope
import com.nphil.marquee.downloads.downloadScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppSessionIdentityTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val container = context.container
	private var jobs: CoroutineScope? = null
	private val secrets = SecretStore(context, "session-test-${UUID.randomUUID()}")
	private val fixture = Fixture()

	@After
	fun tearDown() = runBlocking {
		jobs?.coroutineContext?.job?.cancelAndJoin()
		secrets.clear()
		NativeCore.install(NativeTransport(container.http))
	}

	@Test
	fun legacyLaunchWaitsForVerificationThenTheSavedOwnerCanOpenDownloadsOffline() = runBlocking {
		secrets.write("plex.token", "owner")
		val stale = PlexServer("Fixture", "session-server", "child-server", isOwned = true, isHome = true,
			isPresent = true, httpsRequired = false, connections = emptyList())
		secrets.write("plex.servers", CoreJson.encodeToString(listOf(stale)))
		fixture.offline = true
		installFixture()
		withContext(Dispatchers.Main) {
			val app = openSession()
			val waiting = withTimeout(5_000) { app.state.first { it.phase is AppSession.Phase.Failed } }
			assertTrue(waiting.needsProfileVerification)
			assertTrue(waiting.servers.isEmpty())
			assertNull(waiting.downloadScope)
			assertEquals("owner", app.accountToken)

			fixture.offline = false
			app.retry()
			val ready = withTimeout(5_000) { app.state.first { it.phase == AppSession.Phase.Ready } }
			assertEquals(DownloadScope("session-server", "owner", verified = true), ready.downloadScope)
			assertNotEquals(DownloadScope("session-server", "owner"), ready.downloadScope)
			assertEquals("owner-server", ready.server?.accessToken)
			assertFalse(ready.needsProfileVerification)

			val server = requireNotNull(ready.server)
			assertEquals(ready.downloadScope, ready.copy(servers = listOf(server.copy(accessToken = null))).downloadScope)
			fixture.offline = true
			assertEquals(ready.downloadScope, openSession().state.value.downloadScope)
		}
	}

	@Test
	fun interruptedSwitchKeepsOldBindingAndACompletedSwitchKeepsOnlyTheNewOne() = runBlocking {
		installFixture()
		withContext(Dispatchers.Main) {
			secrets.write("plex.token", "owner")
			val app = openSession()
			withTimeout(5_000) { app.state.first { it.phase == AppSession.Phase.Ready } }
			fixture.rejectChildServers = true
			val failed = runCatching { app.switchUser(child, pin = null) }.exceptionOrNull() as? CoreException
			assertEquals(CoreException.Kind.Unreachable, failed?.kind)

			fixture.offline = true
			val afterInterruption = openSession()
			assertEquals("owner", afterInterruption.accountToken)
			assertEquals("owner", afterInterruption.state.value.downloadScope?.profile)
			assertEquals("owner-server", afterInterruption.state.value.server?.accessToken)

			fixture.offline = false
			fixture.rejectChildServers = false
			afterInterruption.switchUser(child, pin = null)
			fixture.offline = true
			val afterSwitch = openSession()
			assertEquals("child", afterSwitch.accountToken)
			assertEquals("child", afterSwitch.state.value.downloadScope?.profile)
			assertEquals("child-server", afterSwitch.state.value.server?.accessToken)
			assertNull(afterSwitch.state.value.copy(user = child.copy(uuid = " ")).downloadScope)
		}
	}

	private fun installFixture() {
		NativeCore.install(NativeTransport(OkHttpClient.Builder().addInterceptor(fixture).build()))
	}

	private suspend fun openSession(): AppSession {
		jobs?.coroutineContext?.job?.cancelAndJoin()
		val scope = MainScope().also { jobs = it }
		return AppSession(container.core, secrets, container.settings, scope).also { it.restore() }
	}

	private class Fixture : Interceptor {
		@Volatile var offline = false
		@Volatile var rejectChildServers = false

		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val token = request.header("X-Plex-Token").orEmpty()
			if (offline || rejectChildServers && token == "child" && request.url.encodedPath == "/api/v2/resources") {
				throw ConnectException("fixture unavailable")
			}
			val body = when (request.url.encodedPath) {
				"/api/v2/resources" -> resources(token)
				"/api/v2/user" -> """{"id":1,"uuid":"$token","title":"Fixture"}"""
				"/api/v2/home/users" -> """{"users":[]}"""
				"/api/v2/home/users/child/switch" -> """{"authToken":"child"}"""
				"/identity" -> """{"MediaContainer":{"machineIdentifier":"session-server"}}"""
				else -> return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Fixture")
					.body("".toResponseBody()).build()
			}
			return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
				.body(body.toResponseBody()).build()
		}
	}

	private companion object {
		val child = PlexHomeUser(2, "child", "Child", isAdmin = false, isProtected = false, isRestricted = true, isGuest = false)

		fun resources(profile: String) = """[
			{"name":"Fixture","provides":"server","product":"Plex Media Server","clientIdentifier":"session-server",
			"accessToken":"$profile-server","presence":true,"owned":true,
			"connections":[{"uri":"http://session.invalid:32400","local":true}]}
		]""".trimIndent()
	}
}
