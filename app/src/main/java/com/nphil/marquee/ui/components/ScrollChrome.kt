package com.nphil.marquee.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.theme.HeaderStyle
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.roundToInt

internal val LocalHeaderStyle = compositionLocalOf { HeaderStyle.Soft }
internal val LocalShowsScrollBars = compositionLocalOf { true }
private val LocalPageScroll = staticCompositionLocalOf<PageScroll?> { null }

private class PageScroll {
	val state = mutableStateOf<ScrollableState?>(null)
	val hostTop = mutableIntStateOf(0)
	val sourceTop = mutableIntStateOf(0)
}

/** Material's bar stays in place; only its edge over the scrolling page changes. */
@Composable
internal fun BrowseScaffold(
	topBar: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable (PaddingValues) -> Unit,
) {
	val page = remember { PageScroll() }
	val style = LocalHeaderStyle.current
	val canvas = lucent.canvas
	val rule = lucent.edgeRaised
	val glass = lucent.glassRaised.compositeOver(canvas)
	Scaffold(modifier = modifier, topBar = topBar, containerColor = canvas) { padding ->
		CompositionLocalProvider(LocalPageScroll provides page) {
			Box(
				Modifier.fillMaxSize()
					.onGloballyPositioned { page.hostTop.intValue = it.positionInRoot().y.roundToInt() }
					.headerEdge(page, style, padding.calculateTopPadding(), canvas, rule, glass),
			) {
				content(padding)
			}
		}
	}
}

/** Standard vertical browse list; horizontal shelves keep their indicator hidden. */
@Composable
internal fun BrowseList(
	modifier: Modifier = Modifier,
	state: LazyListState = rememberLazyListState(),
	contentPadding: PaddingValues = PaddingValues(0.dp),
	verticalArrangement: Arrangement.Vertical = Arrangement.Top,
	horizontalAlignment: Alignment.Horizontal = Alignment.Start,
	content: LazyListScope.() -> Unit,
) {
	LazyColumn(
		modifier.pageScroll(state, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
		state = state,
		contentPadding = contentPadding,
		verticalArrangement = verticalArrangement,
		horizontalAlignment = horizontalAlignment,
		content = content,
	)
}

/** A passive thumb, sized and positioned by Compose's own scroll metrics. */
@Composable
internal fun Modifier.pageScroll(
	state: ScrollableState,
	top: Dp = 0.dp,
	bottom: Dp = 0.dp,
	end: Dp = Space.s1,
): Modifier {
	val page = LocalPageScroll.current
	DisposableEffect(page, state) {
		page?.state?.value = state
		onDispose { if (page != null && page.state.value === state) page.state.value = null }
	}
	val placement = if (page == null) this else onGloballyPositioned {
		page.sourceTop.intValue = it.positionInRoot().y.roundToInt()
	}
	if (!LocalShowsScrollBars.current) return placement
	val ink = lucent.ink2
	val alpha = animateFloatAsState(
		if (state.isScrollInProgress) 1f else 0f,
		MaterialTheme.motionScheme.fastEffectsSpec(),
		label = "scroll bar",
	)
	return placement.drawWithCache {
		val thickness = 3.dp.toPx()
		val startY = top.toPx()
		val travel = size.height - startY - bottom.toPx()
		val x = if (layoutDirection == LayoutDirection.Ltr) size.width - end.toPx() - thickness else end.toPx()
		val minimum = Space.s6.toPx()
		onDrawWithContent {
			drawContent()
			val opacity = alpha.value
			val metrics = state.scrollIndicatorState
			if (opacity <= 0f || metrics == null || travel <= 0f || (!state.canScrollForward && !state.canScrollBackward)) {
				return@onDrawWithContent
			}
			val view = metrics.viewportSize
			val full = metrics.contentSize
			val offset = metrics.scrollOffset
			if (view <= 0 || full <= view || full == Int.MAX_VALUE || offset == Int.MAX_VALUE) return@onDrawWithContent
			val length = (travel * view / full).coerceIn(minimum.coerceAtMost(travel), travel)
			val position = (offset.toFloat() / (full - view)).coerceIn(0f, 1f)
			drawRoundRect(
				ink, Offset(x, startY + position * (travel - length)), Size(thickness, length),
				cornerRadius = CornerRadius(thickness / 2), alpha = opacity,
			)
		}
	}
}

private fun Modifier.headerEdge(page: PageScroll, style: HeaderStyle, inset: Dp, canvas: Color, rule: Color, glass: Color) =
	drawWithCache {
		// A search field, filter bar or tab row above the scrolling body stays sharp.
		val y = maxOf(inset.roundToPx(), page.sourceTop.intValue - page.hostTop.intValue)
		val width = size.width.roundToInt()
		val height = minOf(Space.s6.roundToPx(), size.height.roundToInt() - y)
		val frost = style == HeaderStyle.Blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && width > 0 && height > 0
		val layer = if (frost) obtainGraphicsLayer() else null
		val band = if (frost) obtainGraphicsLayer().apply {
			clip = true
			renderEffect = BlurEffect(Space.s2.toPx(), Space.s2.toPx(), TileMode.Clamp)
		} else null
		val mask = if (frost) obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen } else null
		val bandSize = IntSize(width, height.coerceAtLeast(0))
		val maskBrush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), endY = height.toFloat())
		// Android 10 and 11 can draw the glass tint but do not offer a backdrop blur.
		val coat = if (style == HeaderStyle.Blur) glass.copy(alpha = 0.82f) else canvas
		val fade = Brush.verticalGradient(listOf(coat, coat.copy(alpha = 0f)), startY = y.toFloat(), endY = y + height.toFloat())
		onDrawWithContent {
			if (page.state.value?.canScrollBackward != true || height <= 0) {
				drawContent()
			} else if (layer != null && band != null && mask != null) {
				// Record once; only the shallow strip, not the page or the bar text, gets a blur surface.
				layer.record { this@onDrawWithContent.drawContent() }
				drawLayer(layer)
				band.record(size = bandSize) { translate(top = -y.toFloat()) { drawLayer(layer) } }
				mask.record(size = bandSize) {
					drawLayer(band)
					drawRect(glass.copy(alpha = 0.6f))
					drawRect(maskBrush, blendMode = BlendMode.DstIn)
				}
				translate(top = y.toFloat()) { drawLayer(mask) }
			} else {
				drawContent()
				if (style == HeaderStyle.Hard) {
					drawRect(rule, Offset(0f, y.toFloat()), Size(size.width, 1.dp.toPx()))
				} else {
					drawRect(fade, Offset(0f, y.toFloat()), Size(size.width, height.toFloat()))
				}
			}
		}
	}
