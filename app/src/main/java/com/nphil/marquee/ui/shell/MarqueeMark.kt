package com.nphil.marquee.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.theme.mix
import kotlinx.coroutines.launch

/**
 * The Marquee "M" from the launcher icon, redrawn as four flat slabs that can pull apart and settle. The
 * lavender faces take the theme's accent and the gold ones its warning colour, so the mark follows the theme.
 *
 * Each change of [toggles] after the first composition plays the pull-apart; [expanding] sets its direction
 * (the slabs push outwards on true and pinch inwards on false). Nothing moves on the first composition, so a
 * restored sidebar does not replay it, and nothing moves when the system animation scale is 0.
 *
 * [size] is the square the M is drawn in. The glyph fills most of its width and leaves a little room for the
 * slabs to move; it is not the launcher icon's padded square.
 */
@Composable
fun MarqueeMark(
	size: Dp,
	modifier: Modifier = Modifier,
	toggles: Int = 0,
	expanding: Boolean = true,
) {
	val theme = lucent
	val slabs = remember { buildSlabs() }
	val tones = remember(theme.accent, theme.warning) {
		Array(Tone.entries.size) { Tone.entries[it].paint(theme.accent, theme.warning) }
	}
	// 0 is the assembled M; 1 and -1 are fully pulled apart when expanding and collapsing.
	val apart = remember { List(slabs.size) { Animatable(0f) } }
	val settle = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
	var armed by remember { mutableStateOf(false) }

	LaunchedEffect(toggles) {
		if (!armed) {
			armed = true
			return@LaunchedEffect
		}
		val direction = if (expanding) 1f else -1f
		for (index in slabs.indices) {
			// The sweep follows the pane: left to right as it opens, back again as it closes. The stagger is the
			// tween's own delay, so one cancelled effect stops every slab at once.
			val place = if (expanding) slabs[index].rank else slabs.lastIndex - slabs[index].rank
			launch {
				apart[index].animateTo(direction, tween(PullApartMillis, place * StaggerMillis, LinearOutSlowInEasing))
				apart[index].animateTo(0f, settle)
			}
		}
	}

	Spacer(
		modifier
			.size(size)
			.drawWithCache {
				val unit = this.size.minDimension / Frame
				// The glyph is not centred in the art; shift its own centre onto the box's.
				val originX = this.size.width / 2f - GlyphCentre.x * unit
				val originY = this.size.height / 2f - GlyphCentre.y * unit
				onDrawBehind {
					withTransform({
						translate(originX, originY)
						scale(unit, unit, Offset.Zero)
					}) {
						for (index in slabs.indices) {
							val slab = slabs[index]
							val t = apart[index].value
							withTransform({
								translate((slab.shift.x + Drift) * t, slab.shift.y * t)
								rotate(slab.tilt * t, slab.pivot)
							}) {
								for (face in slab.facets.indices) {
									val facet = slab.facets[face]
									drawPath(facet.path, tones[facet.tone.ordinal])
								}
							}
						}
					}
				}
			},
	)
}

/**
 * Art units across the box. The M is about 670 units wide in the launcher icon's 1024-unit space, so this leaves
 * about 45 either side for the slabs to move into.
 */
private const val Frame = 760f

/** Where the glyph's visual centre sits in the art. */
private val GlyphCentre = Offset(520f, 502f)

/** Every slab leans a little along the pane's growth, in art units. */
private const val Drift = 12f

private const val PullApartMillis = 150
private const val StaggerMillis = 30

/** The flat shades of the icon's faces, each a fixed shift of the accent or the warning colour. */
private enum class Tone {
	Face,
	Bevel,
	LegFront,
	Shadow,
	Sliver,
	Gold,
	GoldLeg,
	;

	// Measured against the icon art on its rose-pine accent and warning: darker faces keep their hue with a lower
	// value and more saturation, the bevel and sliver are tints. Multiplying saturation keeps grey themes grey.
	fun paint(accent: Color, warning: Color): Color = when (this) {
		Face -> accent.shade(value = 0.96f, saturation = 1f)
		Bevel -> mix(accent, Color.White, 0.33f)
		LegFront -> accent.shade(value = 0.905f, saturation = 1.24f)
		Shadow -> accent.shade(value = 0.58f, saturation = 1.45f)
		Sliver -> mix(accent, Color.White, 0.15f)
		Gold -> warning.shade(value = 0.99f, saturation = 0.88f)
		GoldLeg -> warning.shade(value = 0.855f, saturation = 1.23f)
	}
}

private fun Color.shade(value: Float, saturation: Float): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.colorToHSV(toArgb(), hsv)
	return Color.hsv(hsv[0], (hsv[1] * saturation).coerceAtMost(1f), (hsv[2] * value).coerceAtMost(1f))
}

private class Facet(val tone: Tone, val path: Path)

/**
 * One piece of the M that moves as a unit. [shift] and [tilt] (degrees, clockwise) are where it ends up when fully
 * pulled apart, in art units, turning about [pivot]; [rank] is its place from the left for the stagger.
 */
private class Slab(val rank: Int, val pivot: Offset, val shift: Offset, val tilt: Float, val facets: List<Facet>)

private fun facet(tone: Tone, vararg xy: Float) = Facet(
	tone,
	Path().apply {
		moveTo(xy[0], xy[1])
		for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
		close()
	},
)

/**
 * Back to front. Faces that the icon hides behind a neighbour reach under it here, so a pulled-apart slab shows
 * more of itself where the neighbour moved away and a seam never shows the background. A slab may tuck under the
 * one drawn above it, but never past another slab's place in the order.
 */
private fun buildSlabs() = listOf(
	Slab(
		rank = 3,
		pivot = Offset(775f, 575f),
		shift = Offset(52f, 12f),
		tilt = -4.5f,
		facets = listOf(
			facet(Tone.GoldLeg, 697f, 440f, 852f, 334f, 852f, 802f, 697f, 684f),
		),
	),
	Slab(
		rank = 2,
		pivot = Offset(690f, 415f),
		shift = Offset(38f, -38f),
		tilt = 4f,
		facets = listOf(
			// The underside of the gold slab is dark accent in the art, not gold.
			facet(Tone.Shadow, 560f, 540f, 697f, 440f, 697f, 527f, 515f, 686f),
			facet(Tone.Sliver, 515f, 440f, 725f, 285f, 735f, 300f, 535f, 472f),
			facet(Tone.Gold, 512f, 465f, 760f, 257f, 852f, 333f, 697f, 487f, 580f, 578f),
		),
	),
	Slab(
		rank = 0,
		pivot = Offset(270f, 570f),
		shift = Offset(-52f, 12f),
		tilt = 4.5f,
		facets = listOf(
			facet(Tone.Shadow, 320f, 400f, 400f, 400f, 400f, 646f, 320f, 703f),
			facet(Tone.LegFront, 185f, 330f, 335f, 330f, 335f, 693f, 185f, 808f),
		),
	),
	Slab(
		rank = 1,
		pivot = Offset(385f, 440f),
		shift = Offset(-38f, -38f),
		tilt = -4f,
		facets = listOf(
			facet(Tone.Face, 185f, 196f, 210f, 202f, 515f, 438f, 522f, 457f, 590f, 570f, 515f, 685f, 185f, 375f),
			facet(Tone.Bevel, 185f, 196f, 210f, 202f, 515f, 438f, 500f, 450f, 185f, 230f),
		),
	),
)
