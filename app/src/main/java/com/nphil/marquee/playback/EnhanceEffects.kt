package com.nphil.marquee.playback

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import com.nphil.marquee.R

// The Media3 side of Enhance Picture: two GL passes the player runs on each decoded frame. The shaders are in res/raw
// (enhance_scale.glsl, enhance_finish.glsl) and are this app's own work: a textbook Lanczos and Catmull-Rom kernel, an
// edge-aware sharpen after the published idea of contrast-adaptive sharpening, a ring average for debanding and a
// shock-filter style edge steepening for line art.

/**
 * The strengths the finish pass reads at every frame, so a slider moves the picture without rebuilding the chain.
 * [scale] is how much the scale pass stretched the picture; it sets how far the deband ring reaches.
 */
internal class EnhanceTuning {
	@Volatile var sharpen = 0f
	@Volatile var deband = 0f
	@Volatile var steepen = 0f
	@Volatile var scale = 1f

	fun set(plan: EnhancePlan?) {
		sharpen = plan?.sharpen ?: 0f
		deband = plan?.deband ?: 0f
		steepen = plan?.steepen ?: 0f
		scale = if (plan?.scaler != null) plan.output.width.toFloat() / plan.source.width else 1f
	}
}

/**
 * The GL time of each pass, bracketed with glFinish on the first frames and on every 120th after them. It is what the pass
 * took with the GPU idle before it, so it says what the pass costs, not whether the device keeps up.
 */
internal class EnhanceTimings {
	private val nanos = LongArray(PASSES)
	private val frames = IntArray(PASSES)

	@Synchronized
	fun add(pass: Int, ns: Long) {
		nanos[pass] += ns
		frames[pass]++
	}

	@Synchronized
	fun clear() {
		nanos.fill(0)
		frames.fill(0)
	}

	/** The mean GL time of one frame through the passes in use, in milliseconds; null until each was timed on enough frames. */
	@Synchronized
	fun frameMs(scale: Boolean, finish: Boolean): Double? {
		if (!scale && !finish) return null
		var total = 0.0
		for (pass in 0 until PASSES) {
			if (!(if (pass == SCALE) scale else finish)) continue
			if (frames[pass] < MIN_SAMPLES) return null
			total += nanos[pass].toDouble() / frames[pass] / 1_000_000
		}
		return total
	}

	companion object {
		const val SCALE = 0
		const val FINISH = 1
		private const val PASSES = 2
		private const val MIN_SAMPLES = 5

		/** Whether frame number [index] of a pass is one to time. */
		fun sampled(index: Int): Boolean = index < 6 || index % 120 == 0
	}
}

/** The passes the chain runs: what [EnhanceChain.of] makes of a plan. Two chains that are equal need no new effects. */
internal data class EnhanceChain(val kernel: ScaleKernel?, val display: PixelSize?, val finish: Boolean) {
	@OptIn(UnstableApi::class)
	fun effects(tuning: EnhanceTuning, timings: EnhanceTimings): List<Effect> = listOfNotNull(
		kernel?.let { EnhanceScaleEffect(it, display, timings) },
		if (finish) EnhanceFinishEffect(tuning, timings) else null,
	)

	companion object {
		val NONE = EnhanceChain(null, null, false)

		fun of(plan: EnhancePlan?, display: PixelSize?): EnhanceChain {
			if (plan == null) return NONE
			val kernel = plan.scaler?.kernel
			val finish = plan.sharpen > 0f || plan.deband > 0f || plan.steepen > 0f
			return EnhanceChain(kernel, display.takeIf { kernel != null }, finish)
		}
	}
}

/** The upscale pass. It draws the picture at [upscaledSize] of what it is given, or as it is when that is not worth it. */
@OptIn(UnstableApi::class)
internal class EnhanceScaleEffect(
	private val kernel: ScaleKernel,
	private val display: PixelSize?,
	private val timings: EnhanceTimings,
) : GlEffect {
	// HDR frames arrive in a different colour space than the shaders are written for; they pass through untouched.
	override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
		if (useHdr) PassthroughShaderProgram() else ScaleProgram(context, kernel, display, timings)
}

/** The finishing pass: deband, sharpen and, for line art, edge steepening, at the strengths in [tuning] at each frame. */
@OptIn(UnstableApi::class)
internal class EnhanceFinishEffect(
	private val tuning: EnhanceTuning,
	private val timings: EnhanceTimings,
) : GlEffect {
	override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
		if (useHdr) PassthroughShaderProgram() else FinishProgram(context, tuning, timings)
}

@OptIn(UnstableApi::class)
private abstract class EnhanceProgram(
	context: Context,
	fragment: Int,
	defines: List<String>,
	private val timings: EnhanceTimings,
	private val pass: Int,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {
	private val program: GlProgram
	protected var frames = 0
		private set

	init {
		program = try {
			GlProgram(shader(context, R.raw.enhance_vertex, emptyList()), shader(context, fragment, defines))
		} catch (e: GlUtil.GlException) {
			throw VideoFrameProcessingException(e)
		}
		program.setBufferAttribute(
			"aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
		)
	}

	protected abstract fun bind(program: GlProgram)

	// Every pixel of the output is drawn, so the texture needs no clear first.
	override fun shouldClearTextureBuffer() = false

	override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
		try {
			val timed = EnhanceTimings.sampled(frames)
			// Waits for what was queued before this pass, so the bracket below holds this pass alone.
			if (timed) GLES20.glFinish()
			val start = System.nanoTime()
			program.use()
			program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
			bind(program)
			program.bindAttributesAndUniforms()
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
			GlUtil.checkGlError()
			if (timed) {
				GLES20.glFinish()
				timings.add(pass, System.nanoTime() - start)
			}
			frames++
		} catch (e: GlUtil.GlException) {
			throw VideoFrameProcessingException(e, presentationTimeUs)
		}
	}

	override fun release() {
		super.release()
		try {
			program.delete()
		} catch (e: GlUtil.GlException) {
			throw VideoFrameProcessingException(e)
		}
	}
}

@OptIn(UnstableApi::class)
private class ScaleProgram(
	context: Context,
	kernel: ScaleKernel,
	private val display: PixelSize?,
	timings: EnhanceTimings,
) : EnhanceProgram(context, R.raw.enhance_scale, listOf(kernel.define), timings, EnhanceTimings.SCALE) {
	private val sourceSize = FloatArray(2)

	override fun configure(inputWidth: Int, inputHeight: Int): Size {
		sourceSize[0] = inputWidth.toFloat()
		sourceSize[1] = inputHeight.toFloat()
		val out = upscaledSize(PixelSize(inputWidth, inputHeight), display)
		return if (out == null) Size(inputWidth, inputHeight) else Size(out.width, out.height)
	}

	override fun bind(program: GlProgram) {
		program.setFloatsUniform("uSourceSize", sourceSize)
	}
}

@OptIn(UnstableApi::class)
private class FinishProgram(
	context: Context,
	private val tuning: EnhanceTuning,
	timings: EnhanceTimings,
) : EnhanceProgram(context, R.raw.enhance_finish, emptyList(), timings, EnhanceTimings.FINISH) {
	private val size = FloatArray(2)

	override fun configure(inputWidth: Int, inputHeight: Int): Size {
		size[0] = inputWidth.toFloat()
		size[1] = inputHeight.toFloat()
		return Size(inputWidth, inputHeight)
	}

	override fun bind(program: GlProgram) {
		val deband = tuning.deband
		program.setFloatsUniform("uSize", size)
		program.setFloatUniform("uSharpen", tuning.sharpen)
		program.setFloatUniform("uSteepen", tuning.steepen)
		program.setFloatUniform("uDebandThreshold", if (deband > 0f) debandThreshold(deband) else 0f)
		program.setFloatUniform("uDebandRadius", debandRadius(deband, tuning.scale))
		program.setFloatUniform("uFrame", (frames % NOISE_FRAMES).toFloat())
	}

	private companion object {
		const val NOISE_FRAMES = 64
	}
}

/** A shader's source: the raw resource behind the #version line and the [defines] its variants are told apart by. */
private fun shader(context: Context, resource: Int, defines: List<String>): String = buildString {
	append("#version 100\n")
	defines.forEach { append("#define ").append(it).append('\n') }
	context.resources.openRawResource(resource).bufferedReader().use { append(it.readText()) }
}
