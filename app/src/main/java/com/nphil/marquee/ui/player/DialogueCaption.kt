package com.nphil.marquee.ui.player

import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.media3.common.text.Cue
import com.nphil.marquee.data.SubtitleEdge
import com.nphil.marquee.data.SubtitleLook

/** Only unplaced dialogue uses viewer geometry. Media3 owns sign placement, vertical text, bitmaps and overlapping cues. */
internal fun Cue.isPlainDialogue() = text != null && bitmap == null && position == Cue.DIMEN_UNSET && line == Cue.DIMEN_UNSET &&
	size == Cue.DIMEN_UNSET && verticalType == Cue.TYPE_UNSET && textSize == Cue.DIMEN_UNSET && !windowColorSet && shearDegrees == 0f

@Composable
internal fun DialogueCaption(cue: Cue, look: SubtitleLook, margin: Float, lift: () -> Float, modifier: Modifier) {
	Spacer(modifier.drawWithCache {
		val text = requireNotNull(cue.text)
		val unit = size.height / 1080f
		val pad = look.boxPadding.coerceIn(0, 40) * unit
		val radius = look.boxRadius.coerceIn(0, 40) * unit
		val edge = look.edgeWidth.coerceIn(1, 8) * unit
		val ink = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
			textSize = size.height * .0533f * look.size / 100f
			typeface = look.typeface()
			strokeJoin = Paint.Join.ROUND
		}
		val backing = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = look.boxArgb }
		val width = (size.width * .92f - 2 * pad).toInt().coerceAtLeast(1)
		val layout = StaticLayout.Builder.obtain(text, 0, text.length, ink, width)
			.setAlignment(cue.textAlignment ?: Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
			.setLineSpacing(0f, look.lineSpacing.coerceIn(70, 200) / 100f).build()
		var left = width.toFloat()
		var right = 0f
		for (i in 0 until layout.lineCount) {
			left = minOf(left, layout.getLineLeft(i)); right = maxOf(right, layout.getLineRight(i))
		}
		onDrawBehind {
			val canvas = drawContext.canvas.nativeCanvas
			val top = if (look.atTop) size.height * margin else size.height * (1f - margin - lift()) - layout.height
			val save = canvas.save()
			canvas.translate((size.width - width) / 2, top.coerceIn(0f, (size.height - layout.height).coerceAtLeast(0f)))
			if (look.box && look.windowBox) canvas.drawRoundRect(left-pad, -pad, right+pad, layout.height+pad, radius, radius, backing)
			else if (look.box) for (i in 0 until layout.lineCount) {
				canvas.drawRoundRect(layout.getLineLeft(i)-pad, layout.getLineTop(i)-pad, layout.getLineRight(i)+pad, layout.getLineBottom(i)+pad, radius, radius, backing)
			}
			ink.clearShadowLayer()
			val outline = look.edge == SubtitleEdge.Outline
			if (outline) {
				ink.style = Paint.Style.STROKE; ink.strokeWidth = 2 * edge; ink.color = Color.BLACK
				layout.draw(canvas)
			}
			ink.style = Paint.Style.FILL; ink.color = look.textArgb
			when (look.edge) {
				SubtitleEdge.DropShadow -> ink.setShadowLayer(.5f, edge, edge, Color.BLACK)
				SubtitleEdge.SoftShadow -> ink.setShadowLayer(2 * edge, 0f, edge, 0x99000000.toInt())
				SubtitleEdge.Raised, SubtitleEdge.Depressed -> {
					val raised = look.edge == SubtitleEdge.Raised
					ink.setShadowLayer(edge, -edge, -edge, if (raised) Color.LTGRAY else Color.BLACK); layout.draw(canvas)
					ink.setShadowLayer(edge, edge, edge, if (raised) Color.BLACK else Color.LTGRAY)
				}
				else -> Unit
			}
			layout.draw(canvas)
			canvas.restoreToCount(save)
		}
	})
}
