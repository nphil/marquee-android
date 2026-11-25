package com.nphil.marquee.playback

import android.os.PowerManager
import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// What Enhance Picture does to a title and when it holds back. This file is only decisions, no player and no GL, so the
// rules can be tested. The names (scalers, pause and unavailable reasons, the stats words) are the iPhone's.

/** The scaler Enhance Picture asks for. Off turns the whole feature off, whatever the strengths are. */
enum class EnhanceUpscaler(val title: String) { Off("Off"), Automatic("Automatic"), LineArt("Line art") }

/**
 * The Enhance Picture setting. Every strength is from 0 to 1 and 0 is off. [noiseReduction] is a small edge-preserving
 * denoise, [deblock] smooths the seams of compression blocks and [vivid] lifts local contrast and colour for SDR.
 */
@Immutable
data class PictureEnhance(
	val upscaler: EnhanceUpscaler = EnhanceUpscaler.Off,
	val sharpen: Float = DEFAULT_STRENGTH,
	val deband: Float = DEFAULT_STRENGTH,
	val noiseReduction: Float = 0f,
	val deblock: Float = 0f,
	val vivid: Float = 0f,
) {
	val isOn: Boolean get() = upscaler != EnhanceUpscaler.Off

	/** Whether any strength is above 0, so the finish pass has work even where there is nothing to scale. */
	val touchesPicture: Boolean get() = sharpen > 0f || deband > 0f || noiseReduction > 0f || deblock > 0f || vivid > 0f

	companion object {
		const val DEFAULT_STRENGTH = 0.3f
	}
}

/** The kernel of the scale pass. */
internal enum class ScaleKernel(val define: String) { Lanczos("LANCZOS"), Bicubic("BICUBIC") }

/** A scaler of the chain. [raw] and [label] are what the iPhone logs and shows; [steepens] adds the line-art edge pass. */
internal enum class EnhanceScaler(val raw: String, val label: String, val kernel: ScaleKernel, val steepens: Boolean = false) {
	LineArt("line-art", "Line-art scaler", ScaleKernel.Lanczos, steepens = true),
	Lanczos("lanczos", "GPU Lanczos scaler", ScaleKernel.Lanczos),
	Bicubic("bicubic", "GPU bicubic scaler", ScaleKernel.Bicubic),
	;

	companion object {
		/**
		 * The scalers [upscaler] may use, best first: the governor steps down it before it gives the chain up. Automatic
		 * has its strongest scaler for a low-resolution source ([sourceHeight] up to [LOW_RESOLUTION_LINES]), where the
		 * upscale is seen; for a larger picture it takes the cheaper one.
		 */
		fun ladder(upscaler: EnhanceUpscaler, sourceHeight: Int): List<EnhanceScaler> = when (upscaler) {
			EnhanceUpscaler.Off -> emptyList()
			EnhanceUpscaler.Automatic -> if (sourceHeight <= LOW_RESOLUTION_LINES) listOf(Lanczos, Bicubic) else listOf(Bicubic)
			EnhanceUpscaler.LineArt -> listOf(LineArt, Lanczos)
		}
	}
}

/** A size in pixels. [toString] is the form the log uses. */
internal data class PixelSize(val width: Int, val height: Int) {
	val pixels: Long get() = width.toLong() * height
	val words: String get() = "$width×$height"
	override fun toString() = "${width}x$height"
}

/** What the chain does to the picture now: [output] is the scaler's size, the same as [source] when there is none. */
internal data class EnhancePlan(
	val scaler: EnhanceScaler?,
	val source: PixelSize,
	val output: PixelSize,
	val sharpen: Float,
	val deband: Float,
	val noiseReduction: Float,
	val deblock: Float,
	val vivid: Float,
) {
	/** How far line art is steepened: 0 for any scaler that does not. */
	val steepen: Float get() = if (scaler?.steepens == true) LINE_ART_STEEPEN else 0f

	/** Whether the finish pass has anything to do. */
	val finishes: Boolean get() = sharpen > 0f || deband > 0f || noiseReduction > 0f || deblock > 0f || vivid > 0f || steepen > 0f
}

/** Why the chain cannot run on this video; the video plays unchanged. */
internal enum class EnhanceUnavailable(val raw: String, val message: String, val short: String) {
	HighDynamicRange("highDynamicRange", "HDR and Dolby Vision play without enhancement.", "HDR"),
	NoGpu("noGPU", "This device cannot run the enhancement shaders.", "No GPU"),
	UnsupportedPicture("unsupportedPicture", "This video's picture format is not supported by the enhancer.", "Unsupported picture"),
	SoftwareRenderer("softwareRenderer", "Video decoded in software plays without enhancement.", "Software decoding"),
	Tunnelling("tunnelling", "Tunnelled playback plays without enhancement.", "Tunnelling"),
	SecureSurface("secureSurface", "Protected video plays without enhancement.", "Protected video"),
}

/** Why the chain stands aside for a while; it comes back by itself. */
internal enum class EnhancePause(val raw: String, val message: String) {
	PowerSaving("powerSaving", "the tablet is hot or in Battery Saver"),
	DroppedFrames("droppedFrames", "it cannot keep up"),
}

internal sealed interface EnhanceState {
	/** Nothing to do, and [reason] why, for the log. */
	data class Idle(val reason: String) : EnhanceState
	data class Running(val plan: EnhancePlan) : EnhanceState
	data class Paused(val reason: EnhancePause) : EnhanceState
	data class Unavailable(val reason: EnhanceUnavailable) : EnhanceState
}

/** The state's name in the log. */
internal val EnhanceState.name: String
	get() = when (this) {
		is EnhanceState.Idle -> "idle"
		is EnhanceState.Running -> "running"
		is EnhanceState.Paused -> "paused"
		is EnhanceState.Unavailable -> "unavailable"
	}

/** What the state says about itself in the log, when it does. */
internal val EnhanceState.cause: String?
	get() = when (this) {
		is EnhanceState.Idle -> reason
		is EnhanceState.Running -> null
		is EnhanceState.Paused -> reason.raw
		is EnhanceState.Unavailable -> reason.raw
	}

/** The one line the stats screen shows. */
internal fun EnhanceState.summary(): String = when (this) {
	is EnhanceState.Idle -> "Waiting for a picture, or it already fills the screen"
	is EnhanceState.Running ->
		"Running · ${plan.scaler?.label ?: "No scaler"} · ${plan.source.words} → ${plan.output.words}" +
			" · sharpen ${trimmed(plan.sharpen.toDouble(), 2)} · deband ${trimmed(plan.deband.toDouble(), 2)}" +
			(if (plan.noiseReduction > 0f) " · noise reduction ${trimmed(plan.noiseReduction.toDouble(), 2)}" else "") +
			(if (plan.deblock > 0f) " · deblock ${trimmed(plan.deblock.toDouble(), 2)}" else "") +
			(if (plan.vivid > 0f) " · vivid ${trimmed(plan.vivid.toDouble(), 2)}" else "")
	is EnhanceState.Paused -> "Paused · ${reason.message}"
	is EnhanceState.Unavailable -> "Unavailable · ${reason.message}"
}

/** What the decision depends on besides the setting. [source] is the picture with square pixels. */
internal data class EnhanceFacts(
	val source: PixelSize? = null,
	val display: PixelSize? = null,
	val hdr: Boolean = false,
	val blocker: EnhanceUnavailable? = null,
	val powerShed: Boolean = false,
)

/**
 * What the chain does with a picture, in the order a refusal is the most telling: not asked for, cannot run here, no
 * picture yet, a power or speed pause, then the plan. [ceiling] is how far down the scaler ladder the governor has put
 * it and [overloaded] says it has given the chain up for now.
 */
internal fun decideEnhance(options: PictureEnhance, facts: EnhanceFacts, ceiling: Int, overloaded: Boolean): EnhanceState {
	if (!options.isOn) return EnhanceState.Idle("off")
	facts.blocker?.let { return EnhanceState.Unavailable(it) }
	if (facts.hdr) return EnhanceState.Unavailable(EnhanceUnavailable.HighDynamicRange)
	val source = facts.source ?: return EnhanceState.Idle("waiting")
	if (source.width < MIN_SIDE || source.height < MIN_SIDE) return EnhanceState.Unavailable(EnhanceUnavailable.UnsupportedPicture)
	if (facts.powerShed) return EnhanceState.Paused(EnhancePause.PowerSaving)
	if (overloaded) return EnhanceState.Paused(EnhancePause.DroppedFrames)
	val ladder = EnhanceScaler.ladder(options.upscaler, source.height)
	val output = upscaledSize(source, facts.display)
	val scaler = if (output != null) ladder[min(ceiling, ladder.lastIndex)] else null
	if (scaler == null && !options.touchesPicture) return EnhanceState.Idle("nothingToDo")
	return EnhanceState.Running(
		EnhancePlan(
			scaler, source, output ?: source,
			options.sharpen, options.deband, options.noiseReduction, options.deblock, options.vivid,
		),
	)
}

/**
 * The size the scale pass draws: the picture fitted to the [display], kept under 4x and under the output's pixel limit.
 * Null when that is under 1.1x, which is not worth a pass of its own.
 */
internal fun upscaledSize(source: PixelSize, display: PixelSize?): PixelSize? {
	if (display == null || display.width <= 0 || display.height <= 0) return null
	val fit = min(display.width.toDouble() / source.width, display.height.toDouble() / source.height)
	if (fit < MIN_UPSCALE) return null
	val limit = min(fit, min(MAX_SCALE, sqrt(MAX_OUTPUT_PIXELS / source.pixels.toDouble())))
	if (limit < MIN_UPSCALE) return null
	return PixelSize((source.width * limit).roundToInt(), (source.height * limit).roundToInt())
}

/** The largest difference from the ring's average that deband still smooths, as a fraction of full scale: 2 to 12 levels of 255. */
internal fun debandThreshold(strength: Float): Float = (2f + 10f * strength) / 255f

/** How far the deband ring reaches, in output pixels: wider on a stretched picture, where one source step spans [scale] pixels. */
internal fun debandRadius(strength: Float, scale: Float): Float = (4f + 12f * strength) * max(1f, scale)

/**
 * The sharpen weight the finish pass uses, 0 when off. A strength of 0.5 is the weight the old scale gave 1.0 (0.2) and 1.0
 * is twice that strength; the shader holds the result to the local minimum and maximum, so the larger weight adds no halos.
 */
internal fun sharpenWeight(strength: Float): Float = if (strength > 0f) 0.02f + 0.36f * strength else 0f

/** How far a neighbour may differ from the pixel (as a distance in RGB) and still be averaged in by Noise Reduction; 0 when off. */
internal fun noiseSigma(strength: Float): Float = if (strength > 0f) 0.015f + 0.085f * strength else 0f

/** How much of the filtered pixel Noise Reduction takes over the original. */
internal fun noiseBlend(strength: Float): Float = 0.5f + 0.5f * strength

/** The largest step across an 8x8 block boundary that Deblock still takes for a block edge, as a fraction of full scale: 3 to 20 levels of 255; 0 when off. */
internal fun deblockThreshold(strength: Float): Float = if (strength > 0f) (3f + 17f * strength) / 255f else 0f

/** Whether the picture is already about the size of the view, so that upscaling adds little. */
internal fun nearScreenResolution(source: PixelSize?, display: PixelSize?): Boolean =
	source != null && display != null && display.height > 0 && source.height >= display.height

/** Sources up to this many lines are the ones Automatic gives its strongest scaler. */
internal const val LOW_RESOLUTION_LINES = 720

/**
 * The frame the effect chain is given, from the decoder's [width] and [height] and the file's pixel aspect ratio: Media3
 * widens the picture by it (or heightens it), so the chain always works on square pixels. Null before the size is known.
 */
internal fun squareSource(width: Int, height: Int, pixelAspect: Float): PixelSize? = when {
	width <= 0 || height <= 0 -> null
	pixelAspect > 1f -> PixelSize((width * pixelAspect).toInt(), height)
	pixelAspect in 0.01f..<1f -> PixelSize(width, (height / pixelAspect).toInt())
	else -> PixelSize(width, height)
}

/** Why a stream cannot take the chain before it opens, most telling first; null when nothing stops it. */
internal fun enhanceBlocker(hdr: Boolean, softwareRenderer: Boolean, tunnelling: Boolean, secure: Boolean): EnhanceUnavailable? = when {
	hdr -> EnhanceUnavailable.HighDynamicRange
	softwareRenderer -> EnhanceUnavailable.SoftwareRenderer
	tunnelling -> EnhanceUnavailable.Tunnelling
	secure -> EnhanceUnavailable.SecureSurface
	else -> null
}

/** Whether the tablet is too hot or in Battery Saver for extra GPU work. */
internal fun enhancePowerShed(thermalStatus: Int, batterySaver: Boolean): Boolean =
	batterySaver || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

/**
 * Watches the frames the player drew and dropped and says when the chain costs more than the GPU has. It steps down the
 * scaler ladder first, then stands aside for a cooldown that doubles, and gives up for the title after the last one.
 * Frames come in batches, so a window ends once 30 have been seen, and a window with about a quarter of them dropped
 * counts as overload. The first frames after any change carry set-up cost and are not counted.
 */
internal class EnhanceGovernor {
	enum class Verdict { None, Demoted, Paused, Retired }

	/** How far down the ladder the chain may go no higher than; 0 allows every rung. */
	var ceiling = 0
		private set
	private var strikes = 0
	private var pausedUntil: Long? = null
	private var retired = false
	private var warmUp = WARM_UP_FRAMES
	private var recorded = 0
	private var missed = 0

	val isPaused: Boolean get() = retired || pausedUntil != null

	/**
	 * Takes the frames [rendered] and [dropped] since the last call. [rank] is the rung that drew them, null when no
	 * scaler was in use, and [lowestRank] the last rung of the ladder. [now] is a clock in milliseconds.
	 */
	fun record(rendered: Int, dropped: Int, rank: Int?, lowestRank: Int, now: Long): Verdict {
		if (isPaused) return Verdict.None
		val frames = rendered + dropped
		if (warmUp > 0) {
			warmUp -= frames
			return Verdict.None
		}
		recorded += frames
		missed += dropped
		if (recorded < WINDOW) return Verdict.None
		val overloaded = missed * WINDOW >= OVERLOAD_MISSED * recorded
		recorded = 0
		missed = 0
		if (!overloaded) return Verdict.None
		restartCount()
		if (rank != null && rank < lowestRank) {
			ceiling = rank + 1
			return Verdict.Demoted
		}
		strikes++
		if (strikes > COOLDOWNS_MS.size) {
			retired = true
			return Verdict.Retired
		}
		pausedUntil = now + COOLDOWNS_MS[strikes - 1]
		return Verdict.Paused
	}

	/** True once, when a pause has run its course. */
	fun resume(now: Long): Boolean {
		val until = pausedUntil ?: return false
		if (now < until) return false
		pausedUntil = null
		restartCount()
		return true
	}

	/** Forgets the frames seen so far, for a change that makes them unrepresentative: a seek, a new rung, a new size. */
	fun restartCount() {
		recorded = 0
		missed = 0
		warmUp = WARM_UP_FRAMES
	}

	/** Starts over with the whole ladder: a new title or a new scaler setting. */
	fun reset() {
		ceiling = 0
		strikes = 0
		pausedUntil = null
		retired = false
		restartCount()
	}

	companion object {
		const val WINDOW = 30
		const val OVERLOAD_MISSED = 8
		const val WARM_UP_FRAMES = 10
		val COOLDOWNS_MS = longArrayOf(10_000, 20_000, 40_000)
	}
}

private const val MIN_SIDE = 2
private const val MIN_UPSCALE = 1.1
private const val MAX_SCALE = 4.0
private const val MAX_OUTPUT_PIXELS = 2560.0 * 1440.0
private const val LINE_ART_STEEPEN = 0.75f
