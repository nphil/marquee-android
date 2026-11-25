package com.nphil.marquee.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory

/**
 * Enhance Picture for the title player. It puts the effect chain into the player as a title opens and then keeps the
 * chain matched to the options, the screen and the device: [decideEnhance] says what should run and this applies it.
 * [rebuild] opens the title again from where it was, because Media3 builds its effect pipeline only when the player
 * prepares: turning the setting on mid-title, or dropping the chain after the GPU or the stream refused it, needs that.
 * Everything runs on the main thread.
 */
@OptIn(UnstableApi::class)
internal class EnhanceController(app: Context, private val rebuild: () -> Unit) : Player.Listener {
	private val power = app.getSystemService(PowerManager::class.java)
	private val handler = Handler(Looper.getMainLooper())
	private val tuning = EnhanceTuning()
	private val timings = EnhanceTimings()
	private val governor = EnhanceGovernor()
	private val settleSize = Runnable { refresh("display") }

	private var engine: ExoPlayer? = null
	private var item: String? = null
	private var options = PictureEnhance()
	private var facts = EnhanceFacts()
	private var state: EnhanceState = EnhanceState.Idle("off")

	/** What the title proved unfit for the chain while it played; it holds until another title opens. */
	private var blocked: EnhanceUnavailable? = null

	/** The player was prepared with the pipeline in it; only then can a chain be set. */
	private var installed = false
	private var rebuilding = false
	private var chain = EnhanceChain.NONE

	/** The player is showing the title full screen, in the foreground. */
	private var visible = false
	private var background = false

	private var lastRendered = 0
	private var lastDropped = 0

	// The probe line is made once per title, from the first plan that ran, with the GL time once it was measured.
	private var probed = false
	private var probePlan: EnhancePlan? = null
	private var probeWait = 0

	/** The player is open on a stream, set up before it prepares. [plan] is what the stream was chosen as. */
	fun attach(engine: ExoPlayer, key: String, setting: PictureEnhance, plan: PlayPlan) {
		if (key != item) {
			item = key
			blocked = null
			probed = false
			governor.reset()
		}
		this.engine = engine
		options = setting
		val blocker = blocked ?: enhanceBlocker(
			hdr = plan.isHdr(),
			softwareRenderer = plan.decodedBySoftwareRenderer(),
			tunnelling = (engine.trackSelectionParameters as? DefaultTrackSelector.Parameters)?.tunnelingEnabled == true,
			secure = engine.currentMediaItem?.localConfiguration?.drmConfiguration != null,
		)
		facts = EnhanceFacts(display = pixelSize(engine.surfaceSize.width, engine.surfaceSize.height), blocker = blocker)
		state = EnhanceState.Idle("off")
		chain = EnhanceChain.NONE
		rebuilding = false
		probePlan = null
		probeWait = 0
		lastRendered = 0
		lastDropped = 0
		timings.clear()
		engine.addListener(this)
		installed = setting.isOn && blocker == null
		// An empty chain is what makes Media3 build the pipeline when it prepares; the chain itself is set as it is decided.
		if (installed) engine.setVideoEffects(emptyList())
		refresh("opened")
	}

	/** The player is going. */
	fun detach() {
		flushProbe()
		handler.removeCallbacksAndMessages(null)
		engine?.removeListener(this)
		engine = null
		installed = false
		state = EnhanceState.Idle("off")
	}

	/** The profile changed the setting. Strengths apply to the next frame; a new scaler starts the ladder over. */
	fun setOptions(setting: PictureEnhance) {
		if (setting == options) return
		if (setting.upscaler != options.upscaler) governor.reset()
		options = setting
		refresh("options")
	}

	/** The title is on screen full size and the player is the page in front. */
	fun setVisible(on: Boolean) {
		if (on == visible) return
		visible = on
		refresh(if (on) "visible" else "hidden")
	}

	/** The app is behind another or the screen is off. */
	fun setBackground(on: Boolean) {
		if (on == background) return
		background = on
		refresh(if (on) "background" else "foreground")
	}

	/**
	 * Called every second. Reads the power state and the frames drawn and dropped, and finishes the probe. [decoder] is the
	 * name of the video decoder the player picked.
	 */
	fun tick(decoder: String?) {
		val engine = engine ?: return
		var why: String? = null
		if (installed && facts.blocker == null && decoder?.contains("ffmpeg", ignoreCase = true) == true) {
			// The app's own software renderer takes no effects: the title plays as it is.
			facts = facts.copy(blocker = EnhanceUnavailable.SoftwareRenderer)
			why = "decoder"
		}
		val shed = enhancePowerShed(power.currentThermalStatus, power.isPowerSaveMode)
		if (shed != facts.powerShed) {
			facts = facts.copy(powerShed = shed)
			why = "power"
		}
		if (governor.resume(SystemClock.elapsedRealtime())) why = "resume"
		why?.let(::refresh)
		feed(engine)
		finishProbe(engine)
	}

	/** The line the stats screen shows; null while the setting is off or nothing plays. */
	fun summary(): String? = if (engine != null && options.isOn) state.summary() else null

	/**
	 * Takes a playback error the chain caused. The chain is then marked unusable for the title and the player opens again
	 * without it. False for any other error, which the model deals with.
	 */
	fun failed(error: PlaybackException): Boolean {
		if (!installed) return false
		if (error.errorCode != PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED &&
			error.errorCode != PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
		) {
			return false
		}
		blocked = EnhanceUnavailable.NoGpu
		changed(EnhanceState.Unavailable(EnhanceUnavailable.NoGpu), error.errorCodeName)
		rebuilding = true
		rebuild()
		return true
	}

	override fun onVideoSizeChanged(videoSize: VideoSize) {
		val source = squareSource(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
		if (source == facts.source) return
		facts = facts.copy(source = source)
		governor.restartCount()
		refresh("size")
	}

	override fun onSurfaceSizeChanged(width: Int, height: Int) {
		val display = pixelSize(width, height)
		if (display == facts.display) return
		val first = facts.display == null
		facts = facts.copy(display = display)
		if (first) {
			refresh("display")
		} else {
			// A window being dragged or a screen turning reports many sizes; the chain follows the last.
			handler.removeCallbacks(settleSize)
			handler.postDelayed(settleSize, SIZE_SETTLE_MS)
		}
	}

	override fun onTracksChanged(tracks: Tracks) {
		val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
		val format = (0 until video.length).firstOrNull(video::isTrackSelected)?.let(video::getTrackFormat) ?: return
		val hdr = format.isHdr()
		if (hdr == facts.hdr) return
		facts = facts.copy(hdr = hdr)
		if (hdr) blocked = EnhanceUnavailable.HighDynamicRange
		refresh("tracks")
	}

	override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
		// The frames after a seek come with a refilled buffer and are not a measure of the chain.
		if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) governor.restartCount()
	}

	/** Decides again and puts what changed into the player. [why] is what asked, for the log. */
	private fun refresh(why: String) {
		val engine = engine ?: return
		handler.removeCallbacks(settleSize)
		val decided = if (awaitsPipeline) EnhanceState.Idle("restart") else decideEnhance(options, facts, governor.ceiling, governor.isPaused)
		val next = if (!isActive && (decided is EnhanceState.Running || decided is EnhanceState.Paused)) EnhanceState.Idle("inactive") else decided
		val plan = (next as? EnhanceState.Running)?.plan
		tuning.set(plan)
		val wanted = EnhanceChain.of(plan, facts.display)
		if (installed && wanted != chain) {
			flushProbe()
			chain = wanted
			timings.clear()
			governor.restartCount()
			engine.setVideoEffects(wanted.effects(tuning, timings))
		}
		if (next != state) changed(next, why)
		// A prepared player takes its effect pipeline neither in nor out: either way the title opens again, from where it was.
		if (!rebuilding && ((installed && blocked != null) || (awaitsPipeline && isActive))) {
			rebuilding = true
			rebuild()
		}
	}

	private val isActive get() = visible && !background

	/** The setting is on and the title fit for it, but the player was prepared without the pipeline, as the setting was off. */
	private val awaitsPipeline get() = !installed && options.isOn && facts.blocker == null && blocked == null

	private fun changed(next: EnhanceState, why: String) {
		val before = state
		state = next
		Diag.info(DiagCategory.Player, "enhance.state", "state" to next.name, "reason" to (next.cause ?: why))
		if (before is EnhanceState.Running && next !is EnhanceState.Running) flushProbe()
		if (next is EnhanceState.Idle || probed || probePlan != null) return
		if (next is EnhanceState.Running) {
			probePlan = next.plan
			probeWait = 0
		} else {
			probed = true
			Diag.info(DiagCategory.Player, "enhance-probe", "reason" to (next.cause ?: why), "result" to next.name)
		}
	}

	/** Counts the frames the player drew and dropped since the last second and hands them to the governor. */
	private fun feed(engine: ExoPlayer) {
		val counters = engine.videoDecoderCounters ?: return
		counters.ensureUpdated()
		val rendered = counters.renderedOutputBufferCount
		val dropped = counters.droppedBufferCount
		val newRendered = rendered - lastRendered
		val newDropped = dropped - lastDropped
		lastRendered = rendered
		lastDropped = dropped
		// A count that went down belongs to a new decoder: there is nothing to compare it with this time.
		if (newRendered < 0 || newDropped < 0 || !engine.isPlaying) return
		val plan = (state as? EnhanceState.Running)?.plan ?: return
		val ladder = EnhanceScaler.ladder(options.upscaler)
		val rank = plan.scaler?.let(ladder::indexOf)
		when (governor.record(newRendered, newDropped, rank, ladder.lastIndex, SystemClock.elapsedRealtime())) {
			EnhanceGovernor.Verdict.None -> Unit
			EnhanceGovernor.Verdict.Demoted -> refresh("overload")
			EnhanceGovernor.Verdict.Paused, EnhanceGovernor.Verdict.Retired -> refresh("overload")
		}
	}

	/** Logs the probe once the GL time of the running passes is known, or after four seconds of playing without it. */
	private fun finishProbe(engine: ExoPlayer) {
		if (probePlan == null) return
		if (engine.isPlaying) probeWait++
		if (timings.frameMs(chain.kernel != null, chain.finish) != null || probeWait >= PROBE_WAIT_TICKS) flushProbe()
	}

	private fun flushProbe() {
		val plan = probePlan ?: return
		probePlan = null
		probed = true
		val frameMs = timings.frameMs(chain.kernel != null, chain.finish)
		val fields = buildList {
			add("result" to "running")
			add("scaler" to (plan.scaler?.raw ?: "none"))
			add("source" to plan.source.toString())
			add("output" to plan.output.toString())
			add("sharpen" to trimmed(plan.sharpen.toDouble(), 2))
			add("deband" to trimmed(plan.deband.toDouble(), 2))
			if (frameMs != null) add("frameMs" to trimmed(frameMs, 2))
		}
		Diag.info(DiagCategory.Player, "enhance-probe", *fields.sortedBy { it.first }.toTypedArray())
	}

	private companion object {
		const val SIZE_SETTLE_MS = 300L
		const val PROBE_WAIT_TICKS = 4
	}
}

private fun pixelSize(width: Int, height: Int) = if (width > 0 && height > 0) PixelSize(width, height) else null

/** Whether a track is high dynamic range by its colour transfer or as a Dolby Vision stream. */
@OptIn(UnstableApi::class)
private fun Format.isHdr(): Boolean =
	ColorInfo.isTransferHdr(colorInfo) || sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
		codecs?.let { it.startsWith("dvh") || it.startsWith("dav1") } == true

/**
 * Whether the plan's picture is high dynamic range, by its badge, its Dolby Vision tag or its colour transfer. A picture
 * the server converts is not asked: it arrives as whatever the conversion made, and the track tells at run time.
 */
internal fun PlayPlan.isHdr(): Boolean {
	if (badge.range != null) return true
	if (route.reencodesVideo) return false
	return videoStream()?.let { it.dolbyVision != null || it.dynamicRange() != "SDR" } == true
}

/** Whether the app's own software renderer, which takes no effects, decodes the video: MPEG-2 on a tablet with no decoder for it. */
@OptIn(UnstableApi::class)
internal fun PlayPlan.decodedBySoftwareRenderer(): Boolean =
	!route.reencodesVideo && videoStream()?.codec.equals("mpeg2video", ignoreCase = true) &&
		DeviceDecoders.installed.platformLacks(MimeTypes.VIDEO_MPEG2)
