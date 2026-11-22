package com.nphil.marquee.playback

import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.RevealCue
import com.nphil.marquee.core.RevealWord
import com.nphil.marquee.core.revealWords
import com.nphil.marquee.data.SubtitleReveal
import kotlin.math.roundToInt

/** Only newly shown cues or changed timings cross the bridge; fading runs on the Android playback clock. */
@OptIn(UnstableApi::class)
internal class CaptionReveal(private val core: MarqueeCore, private val timing: PlayerWordTiming) {
	private var frames: List<CaptionFrame> = emptyList()
	private var mode = SubtitleReveal.Off
	private var lines: Array<Line?> = emptyArray()
	private var painted: List<Cue> = emptyList()

	private class Line(val index: Int, val input: RevealCue) {
		var words: List<RevealWord> = emptyList()
		var last: Cue? = null
		var alpha = IntArray(0)
	}

	/** The cues and whether a word is fading or still waiting to appear. */
	var animating = false
		private set

	fun reset() {
		frames = emptyList()
		lines = emptyArray()
		painted = emptyList()
		animating = false
	}

	suspend fun draw(shown: List<CaptionFrame>, due: Long, reveal: SubtitleReveal): List<Cue> {
		var changed = frames != shown || mode != reveal
		if (changed) {
			frames = shown
			mode = reveal
			lines = arrayOfNulls(shown.size)
		}
		animating = false
		if (reveal != SubtitleReveal.Off) {
			var newLines: MutableList<Line>? = null
			for (i in shown.indices) {
				val frame = shown[i]
				val caption = frame.cue.text ?: continue
				// SubRip supplies positioning only when the file asks for it; stream decoders can also supply defaults.
				if (frame.durationMs != null && (frame.cue.position != Cue.DIMEN_UNSET || frame.cue.line != Cue.DIMEN_UNSET)) continue
				val old = lines[i]
				val text = old?.input?.text ?: caption.toString()
				val measured = timing.starts(frame.startMs, text)
				if (old != null && old.input.measured == measured) continue
				val line = Line(i, RevealCue(text, frame.durationMs?.toDouble(), measured))
				val needed = newLines ?: ArrayList<Line>().also { newLines = it }
				needed += line
				changed = true
			}
			newLines?.let { needed ->
				val allWords = try {
					core.revealWords(reveal, needed.map { it.input })
				} catch (_: CoreException) {
					emptyList()
				}
				needed.forEachIndexed { i, line ->
					line.words = allWords.getOrNull(i).orEmpty()
					line.alpha = IntArray(line.words.size) { -1 }
					lines[line.index] = line
				}
			}
			for (i in shown.indices) {
				val line = lines[i] ?: continue
				if (line.words.isEmpty()) continue
				val frame = shown[i]
				var repaint = false
				var masked = false
				line.words.forEachIndexed { word, span ->
					val alpha = wordAlpha(due - frame.startMs, span.atMs)
					masked = masked || alpha < 255
					if (line.alpha[word] != alpha) {
						line.alpha[word] = alpha
						repaint = true
					}
				}
				animating = animating || masked
				if (repaint) {
					line.last = if (masked) paint(frame.cue, line.words, line.alpha) else null
					changed = true
				}
			}
		}
		if (changed) painted = shown.mapIndexed { i, frame -> lines[i]?.last ?: frame.cue }
		return painted
	}

	private fun paint(cue: Cue, words: List<RevealWord>, alpha: IntArray): Cue {
		val text = SpannableString(checkNotNull(cue.text))
		words.forEachIndexed { i, word ->
			if (alpha[i] < 255 && word.start in 0 until word.end && word.end <= text.length) {
				text.setSpan(WordOpacity(alpha[i]), word.start, word.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
		}
		return cue.buildUpon().setText(text).build()
	}
}

/** Media3 strips foreground-colour spans from its outline pass; opacity has to apply to both passes. */
private class WordOpacity(private val alpha: Int) : CharacterStyle(), UpdateAppearance {
	override fun updateDrawState(paint: TextPaint) {
		paint.alpha = (paint.alpha * alpha + 127) / 255
		if (alpha == 0) paint.clearShadowLayer()
	}
}

internal fun wordAlpha(elapsed: Long, starts: Double): Int =
	((elapsed - starts) * (255 / 120.0)).coerceIn(0.0, 255.0).roundToInt()
