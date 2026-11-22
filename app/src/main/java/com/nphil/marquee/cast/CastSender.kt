package com.nphil.marquee.cast

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.nphil.marquee.core.CastPlan
import com.nphil.marquee.core.CastStatus
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.episodeCode
import com.nphil.marquee.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs

/** The app's Cast SDK connection. Only an open player can own media; discovery also runs on the settings page. Main thread. */
@Stable
internal class CastSender(context: Context, private val settings: AppSettings, private val scope: CoroutineScope) : CastTransport {
	private val app = context.applicationContext
	private val router by lazy { MediaRouter.getInstance(app) }
	private var sdk: CastContext? = null
	private var initializing = false
	private var searches = 0
	private var discovering = false
	private var receiverConfigured = true
	private var routeTimeout: Job? = null
	private var starting: CastSession? = null
	private var session: CastSession? = null
	private var endingPosition: Long? = null
	private var client: RemoteMediaClient? = null
	private var player: ((CastEvent) -> Unit)? = null
	private var load: CastLoadStamp? = null
	private var serial = 0

	override var enabled by mutableStateOf(false)
		private set
	override var devices: List<CastReceiver> by mutableStateOf(emptyList())
		private set
	override var needsPermission by mutableStateOf(false)
		private set
	override var discoveryProblem: String? by mutableStateOf(null)
		private set
	override var connectionProblem: String? by mutableStateOf(null)
		private set
	override var connecting: CastReceiver? by mutableStateOf(null)
		private set
	override var connected: CastReceiver? by mutableStateOf(null)
		private set

	override val mediaSessionId: Int? get() = load?.serial

	override val positionMs: Long?
		get() {
			val remote = ownedClient() ?: return null
			return remote.approximateStreamPosition.takeIf { remote.isPlaying || remote.isPaused || remote.isBuffering }
		}

	private val routeCallback = object : MediaRouter.Callback() {
		override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
		override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
		override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
	}

	private val sessions = object : SessionManagerListener<CastSession> {
		override fun onSessionStarting(session: CastSession) {
			if (connecting?.id?.nativeId == router.selectedRoute.id) starting = session
		}
		override fun onSessionStarted(session: CastSession, sessionId: String) = joined(session)
		override fun onSessionStartFailed(session: CastSession, error: Int) {
			if (session === starting) connectionFailed()
		}
		override fun onSessionEnding(session: CastSession) {
			if (session === this@CastSender.session) endingPosition = positionMs
		}
		override fun onSessionEnded(session: CastSession, error: Int) {
			if (session === starting) connectionFailed() else if (session === this@CastSender.session) leave(endSession = false)
		}
		override fun onSessionResuming(session: CastSession, sessionId: String) = onSessionStarting(session)
		override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = joined(session)
		override fun onSessionResumeFailed(session: CastSession, error: Int) = onSessionStartFailed(session, error)
		override fun onSessionSuspended(session: CastSession, reason: Int) {
			if (session === starting) connectionFailed() else if (session === this@CastSender.session) leave(stopping = false)
		}
	}

	private val mediaCallback = object : RemoteMediaClient.Callback() {
		override fun onStatusUpdated() = publishStatus()
		override fun onMetadataUpdated() {
			// A second sender can replace the title without changing its play state.
			if (client?.mediaInfo != null) publishStatus()
		}
	}

	init {
		scope.launch {
			settings.castEnabled.collect { on ->
				enabled = on
				if (!on) {
					connectionProblem = null
					leave()
				}
				refreshDiscovery()
			}
		}
	}

	override fun setOptIn(on: Boolean) {
		scope.launch { settings.setCastEnabled(on) }
	}

	override fun beginSearch() {
		if (searches++ == 0) discoveryProblem = null
		refreshDiscovery()
	}

	override fun endSearch() {
		searches--
		refreshDiscovery()
	}

	override fun refreshDiscovery() {
		needsPermission = enabled && Build.VERSION.SDK_INT >= 37 &&
			ContextCompat.checkSelfPermission(app, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
		val wanted = enabled && searches > 0 && !needsPermission
		val cast = sdk
		if (cast == null) {
			if (wanted && !initializing && discoveryProblem == null) initialize()
			return
		}
		if (!wanted && discovering) {
			discovering = false
			router.removeCallback(routeCallback)
			devices = emptyList()
		}
		// Changing the receiver app ends its session. Keep it configured when the player goes behind the lock screen.
		val keepReceiver = wanted || connecting != null || session != null
		if (keepReceiver != receiverConfigured) {
			receiverConfigured = keepReceiver
			cast.setReceiverApplicationId(if (keepReceiver) DEFAULT_MEDIA_RECEIVER_APPLICATION_ID else "")
		}
		if (wanted && !discovering) {
			val selector = cast.mergedSelector ?: return
			discovering = true
			router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
			publishDevices()
		}
	}

	private fun initialize() {
		if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
			unavailable()
			return
		}
		initializing = true
		try {
			CastContext.getSharedInstance(app, Dispatchers.IO.asExecutor())
				.addOnSuccessListener { cast ->
					initializing = false
					sdk = cast
					cast.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
					refreshDiscovery()
				}
				.addOnFailureListener { unavailable() }
		} catch (_: RuntimeException) {
			unavailable()
		}
	}

	private fun unavailable() {
		initializing = false
		discoveryProblem = "Chromecast needs Google Play services with Cast support."
	}

	private fun publishDevices() {
		if (!discovering) return
		val selector = sdk?.mergedSelector ?: return
		devices = router.routes.mapNotNull { route ->
			if (!route.isEnabled || route.isDefaultOrBluetooth() || !route.matchesSelector(selector)) return@mapNotNull null
			val device = CastDevice.getFromBundle(route.extras)
			if (device != null && !device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)) return@mapNotNull null
			CastReceiver(CastRouteId(CastProvider.Chromecast, route.id), route.name, device?.modelName?.ifBlank { null })
		}.sortedBy { it.name }
	}

	override fun connect(receiver: CastReceiver, events: (CastEvent) -> Unit, beforeConnect: () -> Unit) {
		if (player != null && player !== events) return
		if (!enabled || !discovering || connecting != null || connected?.id == receiver.id) return
		if (receiver.id.provider != CastProvider.Chromecast) return
		val route = router.routes.firstOrNull { it.id == receiver.id.nativeId && it.isEnabled }
		if (route == null) {
			connectionProblem = "That Chromecast is no longer available."
			return
		}
		beforeConnect()
		connectionProblem = null
		if (session != null) {
			val position = positionMs
			stopMedia()
			attach(null)
			session = null
			endingPosition = null
			connected = null
			player?.invoke(CastEvent.Switching(position))
		}
		player = events
		connecting = receiver
		routeTimeout = scope.launch {
			delay(30_000)
			connectionFailed()
		}
		if (route.isSelected) {
			val selected = sdk?.sessionManager?.currentCastSession
			if (selected?.isConnected == true) joined(selected) else route.select()
		} else {
			route.select()
		}
	}

	private fun joined(joined: CastSession) {
		if (joined === session) return
		val target = connecting
		if (!enabled || target == null) {
			// The player may have closed while Cast was launching the receiver.
			val manager = sdk?.sessionManager ?: return
			if (manager.currentCastSession === joined) manager.endCurrentSession(false)
			return
		}
		if (router.selectedRoute.id != target.id.nativeId || (starting != null && starting !== joined)) return
		val remote = joined.remoteMediaClient
		if (remote == null) {
			connectionFailed()
			return
		}
		routeTimeout?.cancel()
		starting = null
		endingPosition = null
		session = joined
		val receiver = target.copy(model = joined.castDevice?.modelName?.ifBlank { null } ?: target.model)
		connecting = receiver
		attach(remote)
		player?.invoke(CastEvent.Connected(receiver))
	}

	/** The chooser selects a receiver only after the player has confirmed this title can play on it. */
	override fun activate(): Boolean {
		if (session?.isConnected != true || ownedClient() == null) return false
		connecting?.let {
			connected = it
			connecting = null
		}
		return true
	}

	override fun rejectHandoff(message: String) {
		connectionProblem = message
		leave()
	}

	override fun cancelPending(events: (CastEvent) -> Unit) {
		if (player === events && connecting != null) disconnect()
	}

	private fun connectionFailed() {
		connectionProblem = "The Chromecast could not connect."
		leave(stopping = false)
	}

	override fun disconnect(stopping: Boolean) {
		connectionProblem = null
		leave(stopping)
	}

	override fun playerClosed(events: (CastEvent) -> Unit) {
		if (player !== events) return
		player = null
		leave()
	}

	private fun leave(stopping: Boolean = true, endSession: Boolean = true) {
		if (connecting == null && session == null) return
		val position = positionMs ?: endingPosition
		val stopMine = stopping && canStopMedia()
		val routeId = (connecting ?: connected)?.id?.nativeId
		val ending = session ?: starting
		val events = player
		routeTimeout?.cancel()
		attach(null)
		load = null
		session = null
		endingPosition = null
		starting = null
		connecting = null
		connected = null
		player = null
		if (endSession) {
			val manager = sdk?.sessionManager
			if (ending != null && manager != null && manager.currentCastSession === ending) manager.endCurrentSession(stopMine)
			else if (router.selectedRoute.id == routeId) router.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
		}
		refreshDiscovery()
		events?.invoke(CastEvent.Disconnected(position))
	}

	private fun attach(remote: RemoteMediaClient?) {
		client?.unregisterCallback(mediaCallback)
		client = remote
		remote?.registerCallback(mediaCallback)
	}

	override fun load(plan: CastPlan, autoplay: Boolean): Boolean {
		val remote = client ?: return false
		val opened = plan.plan.stream ?: return false
		val previous = remote.mediaInfo?.contentId?.takeIf { load?.canStop(it) == true }
		val stamp = CastLoadStamp(++serial, UUID.randomUUID().toString(), previous)
		val metadata = MediaMetadata(if (plan.plan.item.type == MediaType.Episode) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE)
		val item = plan.plan.item
		metadata.putString(MediaMetadata.KEY_TITLE, item.grandparentTitle ?: item.title)
		if (item.grandparentTitle != null) metadata.putString(MediaMetadata.KEY_SUBTITLE, listOfNotNull(item.episodeCode, item.title).joinToString(" · "))
		plan.poster?.let { metadata.addImage(WebImage(Uri.parse(it), 480, 720)) }
		val media = MediaInfo.Builder(stamp.id)
			.setContentUrl(opened.url)
			.setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
			.setContentType(plan.contentType)
			.setMetadata(metadata)
		if (plan.plan.duration > 0) media.setStreamDuration((plan.plan.duration * 1000).toLong())
		if (opened.kind == PlayStream.StreamKind.Hls) {
			media.setHlsSegmentFormat(HlsSegmentFormat.TS)
			media.setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
		}
		load = stamp
		remote.load(MediaLoadRequestData.Builder()
			.setMediaInfo(media.build())
			.setAutoplay(autoplay)
			.setCurrentTime((plan.plan.startAt * 1000).toLong())
			.build(),
		).setResultCallback { result ->
			if (load !== stamp || stamp.ended) return@setResultCallback
			if (result.status.isSuccess) publishStatus() else {
				publishStatus(stamp, "idle", "error", plan.plan.startAt, 0.0)
			}
		}
		return true
	}

	override fun setPlaying(playing: Boolean): Boolean {
		val remote = ownedClient() ?: return false
		if (playing) remote.play() else remote.pause()
		return true
	}

	override fun seek(ms: Long): Boolean {
		val remote = ownedClient() ?: return false
		remote.seek(MediaSeekOptions.Builder().setPosition(ms).build())
		return true
	}

	override suspend fun positionBeforeHandoff(ms: Long): Boolean {
		val remote = ownedClient() ?: return false
		if (abs(remote.approximateStreamPosition - ms) < 500) return true
		return withTimeoutOrNull(8_000) {
			suspendCancellableCoroutine { done ->
				remote.seek(MediaSeekOptions.Builder().setPosition(ms).setResumeState(MediaSeekOptions.RESUME_STATE_PAUSE).build())
					.setResultCallback { result -> done.resume(result.status.isSuccess && remote === ownedClient()) }
			}
		} ?: false
	}

	fun adjustVolume(by: Double) {
		val receiver = session ?: return
		try {
			receiver.setVolume((receiver.volume + by).coerceIn(0.0, 1.0))
		} catch (_: IOException) {
			// The route can disappear while a volume key is pressed.
		} catch (_: IllegalStateException) {
			// Play services already closed the volume channel.
		}
	}

	fun toggleMute() {
		val receiver = session ?: return
		try {
			receiver.setMute(!receiver.isMute)
		} catch (_: IOException) {
			// The route can disappear while a volume key is pressed.
		} catch (_: IllegalStateException) {
			// Play services already closed the volume channel.
		}
	}

	override fun stopMedia() {
		val stop = canStopMedia()
		load = null
		if (stop) client?.stop()
	}

	private fun canStopMedia(): Boolean = load?.canStop(client?.mediaInfo?.contentId) == true

	private fun ownedClient(): RemoteMediaClient? = client?.takeIf { load?.owns(it.mediaInfo?.contentId) == true }

	private fun publishStatus() {
		val remote = client ?: return
		val stamp = load ?: return
		if (stamp.ended) return
		val status = remote.mediaStatus
		val idle = status == null || status.playerState == MediaStatus.PLAYER_STATE_IDLE
		val owner = stamp.observe(status?.mediaInfo?.contentId, idle)
		if (owner == CastLoadStamp.Status.Ignore) return
		if (owner == CastLoadStamp.Status.Replaced) {
			publishStatus(stamp, "idle", "interrupted", 0.0, 0.0)
			return
		}
		val words = when (status?.playerState) {
			MediaStatus.PLAYER_STATE_PLAYING -> "playing"
			MediaStatus.PLAYER_STATE_PAUSED -> "paused"
			MediaStatus.PLAYER_STATE_BUFFERING -> "buffering"
			MediaStatus.PLAYER_STATE_LOADING -> "loading"
			MediaStatus.PLAYER_STATE_IDLE, null -> "idle"
			else -> return
		}
		val why = when {
			status == null -> "cancelled"
			!idle -> null
			else -> when (status.idleReason) {
				MediaStatus.IDLE_REASON_FINISHED -> "finished"
				MediaStatus.IDLE_REASON_CANCELED -> "cancelled"
				MediaStatus.IDLE_REASON_INTERRUPTED -> "interrupted"
				MediaStatus.IDLE_REASON_ERROR -> "error"
				else -> return
			}
		}
		publishStatus(stamp, words, why, remote.approximateStreamPosition / 1000.0, status?.playbackRate ?: 0.0)
	}

	private fun publishStatus(stamp: CastLoadStamp, state: String, idle: String?, position: Double, rate: Double) {
		val progress = CastStatus(stamp.serial, state, idle, position, rate)
		if (progress == stamp.lastStatus) return
		stamp.lastStatus = progress
		stamp.ended = idle != null
		player?.invoke(CastEvent.Progress(progress))
	}
}

/** Android's public status API does not expose the media session ID. Echo a unique content ID in each load instead. */
internal class CastLoadStamp(val serial: Int, val id: String, private val previous: String?) {
	enum class Status { Ignore, Current, Replaced }

	private var seen = false
	var ended = false
	var lastStatus: CastStatus? = null

	fun owns(contentId: String?): Boolean = contentId == id && !ended

	fun canStop(contentId: String?): Boolean = contentId == id || (!seen && contentId != null && contentId == previous)

	fun observe(contentId: String?, idle: Boolean): Status = when {
		contentId == id -> {
			seen = true
			Status.Current
		}
		!seen || (contentId != null && contentId == previous) -> Status.Ignore
		contentId != null -> Status.Replaced
		idle -> Status.Current
		else -> Status.Ignore
	}
}
