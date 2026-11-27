package com.nphil.marquee.bigscreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.data.SubtitleLook
import com.nphil.marquee.playback.Offer
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.PlayerPhase
import com.nphil.marquee.playback.title
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.player.CaptionView
import com.nphil.marquee.ui.player.CountdownRing
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The big screen's type scale (DESIGN §8.9), in design points of the 1080-high canvas. Bold Text still applies as on the phone. */
internal enum class GlassesText(val size: Float, val weight: FontWeight) {
	Display(76f, FontWeight.Bold),
	Title(52f, FontWeight.Bold),
	Headline(40f, FontWeight.SemiBold),
	Body(32f, FontWeight.Medium),
	Label(28f, FontWeight.Medium),
	Caption(24f, FontWeight.Normal),
	;

	val style: TextStyle get() = TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = (size * 1.2f).sp)
}

/** Shows [text] in one of the big screen's roles; colour always comes from the theme. */
@Composable
private fun GlassesLabel(text: String, role: GlassesText, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null) {
	Text(text, style = role.style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = modifier)
}

/**
 * What the display shows: Marquee's own canvas, 1080 design points high, scaled to the display's pixels and centred.
 * The theme is the active one's dark side, as the phone remote's is; glasses draw on black, which is see-through.
 */
@Composable
internal fun GlassesRoot(container: AppContainer, center: GlassesCenter) {
	val layout = center.layout
	val window = LocalWindowInfo.current.containerSize
	val scale = layout.scale(window.width.toDouble(), window.height.toDouble()).toFloat().takeIf { it > 0f } ?: 1f
	GlassesTheme(container) {
		val background = if (layout.usesBlackCanvas) Color.Black else lucent.canvas
		Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
			CompositionLocalProvider(LocalDensity provides Density(density = scale, fontScale = 1f)) {
				Box(Modifier.size(layout.width.toFloat().dp, layout.height.toFloat().dp)) {
					val navigator = center.navigator
					if (navigator != null) GlassesNavigatorView(navigator, center, container, layout)
					VolumeBar(center, layout)
				}
			}
		}
	}
}

/** The active theme's dark side, which the big screen and the phone remote both follow. */
@Composable
internal fun GlassesTheme(container: AppContainer, content: @Composable () -> Unit) {
	val choice by container.settings.themeChoice.collectAsStateWithLifecycle(ThemeChoice())
	val theme = remember(choice.darkId) { container.themes.resolve(choice.darkId, ThemeMode.Dark) }
	MarqueeTheme(theme, container.typography, content)
}

/** The player or the page on top, with the panel and the toast over it. */
@Composable
private fun GlassesNavigatorView(navigator: GlassesNavigator, center: GlassesCenter, container: AppContainer, layout: BigScreenLayout) {
	val link = navigator.link
	val page = navigator.page
	Box(Modifier.fillMaxSize()) {
		if (link != null) {
			GlassesPlayerView(link.model, navigator, container, layout)
		} else if (page != null) {
			key(page) { GlassesPageView(page, layout) }
		}
		val panel = navigator.panel
		AnimatedVisibility(
			visible = panel != null,
			modifier = Modifier.align(Alignment.CenterEnd),
			enter = fadeIn() + slideInHorizontally { it / 3 },
			exit = fadeOut() + slideOutHorizontally { it / 3 },
		) {
			// The last panel stays drawn while it fades out.
			val shown = remember { arrayOfNulls<GlassesPanel>(1) }
			if (panel != null) shown[0] = panel
			shown[0]?.let { GlassesPanelView(it, layout) }
		}
		navigator.toast?.let { Toast(it, layout, Modifier.align(Alignment.BottomCenter)) }
	}
}

// MARK: Pages

private val GlassesTile.shapeOf: Nothing? get() = null

private val TileShape.hasCaption: Boolean get() = this == TileShape.Poster || this == TileShape.Landscape

/** The tiles and their captions, without the row's title. */
private val TileShape.fullHeight: Float
	get() = height + if (hasCaption) GlassesCanvas.CAPTION_GAP + GlassesCanvas.CAPTION + GlassesCanvas.SUBCAPTION else 0f

/** The letter board's rows sit close; other rows keep the full gap. */
private val TileShape.gapBelow: Float get() = if (this == TileShape.Key) GlassesCanvas.TITLE_GAP else GlassesCanvas.ROW_GAP

private val GlassesRow.fullHeight: Float get() = (if (title == null) 0f else GlassesCanvas.ROW_TITLE + GlassesCanvas.TITLE_GAP) + shape.fullHeight

/** A page: its title, the opened title or the focused one named large, then the rows. */
@Composable
private fun GlassesPageView(page: GlassesPage, layout: BigScreenLayout) {
	// Every focus move counts here, which the grid itself does not announce.
	@Suppress("UNUSED_VARIABLE") val moved = page.stamp
	Box(Modifier.fillMaxSize()) {
		page.hero?.backdrop?.let { Backdrop(it) }
		Column(
			Modifier.fillMaxSize().padding(horizontal = layout.marginX.toFloat().dp, vertical = layout.marginY.toFloat().dp),
		) {
			// An opened title names itself in the hero below; the line keeps its height.
			val heading = page.query.ifEmpty { page.title }
			GlassesLabel(
				heading, GlassesText.Title, if (page.query.isEmpty()) lucent.ink2 else lucent.ink,
				Modifier.height(GlassesCanvas.HEADER.dp).graphicsLayer { alpha = if (heading == page.hero?.title) 0f else 1f },
			)
			val hero = page.hero
			if (hero != null) HeroView(hero) else FocusLine(page)
			val problem = page.problem
			when {
				problem != null -> Notice(R.drawable.ic_error, "Couldn\u2019t load", problem, Modifier.fillMaxSize())
				page.rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
					GlassesLabel(if (page.loading) "Loading\u2026" else "Nothing here yet", GlassesText.Headline, lucent.ink2)
				}
				else -> RowsView(page)
			}
		}
	}
}

@Composable
private fun FocusLine(page: GlassesPage) {
	Column(Modifier.height(GlassesCanvas.FOCUS_LINE.dp)) {
		GlassesLabel(
			page.focusedTile?.title ?: " ", GlassesText.Headline, lucent.ink,
			Modifier.height((GlassesCanvas.CAPTION + GlassesCanvas.CAPTION_GAP).dp),
		)
		GlassesLabel(page.focusedTile?.subtitle ?: " ", GlassesText.Body, lucent.ink2)
	}
}

/** The opened title's artwork at the top right, fading into the see-through black. */
@Composable
private fun Backdrop(path: String) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
		PlexArt(
			path, GlassesCanvas.BACKDROP.first.dp, GlassesCanvas.BACKDROP.second.dp,
			Modifier
				.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
				.drawWithContent {
					drawContent()
					drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
					drawRect(Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
				},
			ContentScale.Crop,
		)
	}
}

@Composable
private fun HeroView(hero: GlassesHero) {
	Column(Modifier.height(GlassesCanvas.HERO_HEIGHT.dp), verticalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp)) {
		GlassesLabel(hero.title, GlassesText.Display, lucent.ink)
		hero.meta?.let { GlassesLabel(it, GlassesText.Body, lucent.ink2) }
		hero.overview?.let { GlassesLabel(it, GlassesText.Body, lucent.ink, Modifier.width(GlassesCanvas.OVERVIEW_WIDTH.dp), maxLines = 3) }
	}
}

/** Where each row sits and how far the rows scroll for a focus at any row. */
private class RowsLayout(rows: List<GlassesRow>, height: Float) {
	val tops: List<Float>
	private val pins: List<Float>
	private val limit: Float

	init {
		val tops = ArrayList<Float>()
		var y = 0f
		for (row in rows) {
			tops += y
			y += row.fullHeight + row.shape.gapBelow
		}
		this.tops = tops
		pins = rows.indices.map { if (rows[it].shape == TileShape.Key) 0f else tops[it] }
		val total = (tops.lastOrNull() ?: 0f) + (rows.lastOrNull()?.fullHeight ?: 0f)
		limit = max(total - height + GlassesCanvas.FOCUS_ROOM, 0f)
	}

	/** The shift that holds row [row] at the top; the letter board stays put while it has the focus. */
	fun shift(row: Int): Float = if (pins.isEmpty()) 0f else min(pins[row.coerceIn(0, pins.size - 1)], limit)
}

/** Cuts the rows off above and below only: a focused tile at the edge grows into the margin. */
private class RowsClip(private val reach: Float) : Shape {
	override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
		val gap = reach * density.density
		return Outline.Rectangle(Rect(-gap, 0f, size.width + gap, size.height))
	}
}

private val glide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The rows, the focused one held at the top, and in each row the focused tile kept second from the left once the row runs past the edge. */
@Composable
private fun RowsView(page: GlassesPage) {
	BoxWithConstraints(Modifier.fillMaxSize().clip(RowsClip(GlassesCanvas.TILE_GAP))) {
		val rows = page.rows
		val height = maxHeight.value
		val width = maxWidth.value
		val layout = remember(rows, height) { RowsLayout(rows, height) }
		val focused = min(page.focus.row, max(rows.size - 1, 0))
		val shift by animateFloatAsState(layout.shift(focused), glide, label = "rows")
		// Rows either side of the focus too: a flick lets the page lag a little behind it.
		val shown = max(focused - 2, 0) until min(focused + 6, rows.size)
		for (index in shown) {
			val row = rows[index]
			key(row.id) {
				Box(Modifier.offset { IntOffset(0, ((layout.tops[index] - shift + GlassesCanvas.FOCUS_ROOM) * density).toInt()) }) {
					RowView(row, index, page, index == focused, width)
				}
			}
		}
	}
}

@Composable
private fun RowView(row: GlassesRow, index: Int, page: GlassesPage, focused: Boolean, width: Float) {
	val step = row.shape.width + GlassesCanvas.TILE_GAP
	val length = row.tiles.size * step - GlassesCanvas.TILE_GAP
	val column = page.focus.column(inRow = index)
	val shift by animateFloatAsState(min(max(column - 1, 0) * step, max(length - width, 0f)), glide, label = "columns")
	Column(Modifier.width(width.dp), verticalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp)) {
		row.title?.let {
			GlassesLabel(it, GlassesText.Headline, if (focused) lucent.ink else lucent.ink2, Modifier.height(GlassesCanvas.ROW_TITLE.dp), align = null)
		}
		Row(
			Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset { IntOffset((-shift * density).toInt(), 0) },
			horizontalArrangement = Arrangement.spacedBy(GlassesCanvas.TILE_GAP.dp),
		) {
			row.tiles.forEachIndexed { at, tile ->
				key(tile.id) { TileView(tile, row.shape, focused && at == column) }
			}
		}
	}
}

/** A tile: its picture or its words on a raised surface, and under a picture the title and a line. The focused tile grows and takes the one highlight. */
@Composable
private fun TileView(tile: GlassesTile, shape: TileShape, focused: Boolean) {
	val corners = RoundedCornerShape((if (shape == TileShape.Key) GlassesCanvas.RADIUS / 2 else GlassesCanvas.RADIUS).dp)
	val grown by animateFloatAsState(if (focused) GlassesCanvas.FOCUS_SCALE else 1f, spring(stiffness = Spring.StiffnessMedium), label = "grow")
	Column(Modifier.graphicsLayer { /* the focused tile draws over its neighbours */ }, verticalArrangement = Arrangement.spacedBy(GlassesCanvas.CAPTION_GAP.dp)) {
		Box(
			Modifier
				.size(shape.width.dp, shape.height.dp)
				.graphicsLayer { scaleX = grown; scaleY = grown }
				.clip(corners)
				.background(lucent.glassRaised)
				.then(if (focused) Modifier.border(GlassesCanvas.FOCUS_RING.dp, lucent.focus, corners) else Modifier),
			contentAlignment = Alignment.Center,
		) {
			TileFace(tile, shape)
			tile.progress?.let { Progress(it, shape.width, Modifier.align(Alignment.BottomCenter)) }
			if (focused) Box(Modifier.fillMaxSize().border(GlassesCanvas.FOCUS_RING.dp, lucent.focus, corners))
		}
		if (shape.hasCaption) {
			Column(Modifier.width(shape.width.dp)) {
				GlassesLabel(tile.title, GlassesText.Label, if (focused) lucent.ink else lucent.ink2, Modifier.height(GlassesCanvas.CAPTION.dp))
				GlassesLabel(tile.subtitle ?: " ", GlassesText.Caption, lucent.ink3, Modifier.height(GlassesCanvas.SUBCAPTION.dp))
			}
		}
	}
}

@Composable
private fun TileFace(tile: GlassesTile, shape: TileShape) {
	when (val art = tile.art) {
		is GlassesArt.Plex -> PlexArt(art.path, shape.width.dp, shape.height.dp)
		is GlassesArt.Symbol -> if (shape == TileShape.Key) {
			Icon(painterResource(art.icon), null, tint = lucent.ink, modifier = Modifier.size(GlassesText.Headline.size.dp))
		} else {
			Row(
				Modifier.fillMaxSize().padding(horizontal = GlassesCanvas.TILE_GAP.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp),
			) {
				Icon(painterResource(art.icon), null, tint = lucent.accentText, modifier = Modifier.size(GlassesText.Headline.size.dp))
				Column(Modifier.weight(1f)) {
					GlassesLabel(tile.title, GlassesText.Headline, lucent.ink)
					tile.subtitle?.let { GlassesLabel(it, GlassesText.Caption, lucent.ink2) }
				}
			}
		}
		GlassesArt.None -> GlassesLabel(
			tile.title, if (shape == TileShape.Key) GlassesText.Headline else GlassesText.Label, lucent.ink,
			Modifier.padding(horizontal = GlassesCanvas.TITLE_GAP.dp), align = TextAlign.Center,
		)
	}
}

@Composable
private fun Progress(fraction: Float, width: Float, modifier: Modifier) {
	Box(
		modifier.padding(GlassesCanvas.TITLE_GAP.dp).fillMaxWidth().height(GlassesCanvas.BAR_HEIGHT.dp)
			.clip(CircleShape).background(lucent.trackOff),
		contentAlignment = Alignment.CenterStart,
	) {
		Box(Modifier.fillMaxHeight().width(((width - GlassesCanvas.TILE_GAP) * fraction.coerceIn(0f, 1f)).dp).clip(CircleShape).background(lucent.accent))
	}
}

// MARK: Panel, toast, notices

/** A title's options or the player's tracks, down the right of the screen. */
@Composable
private fun GlassesPanelView(panel: GlassesPanel, layout: BigScreenLayout) {
	val lines = remember(panel.options) { panelLines(panel.options) }
	val focusTop = lines.firstOrNull { it.option?.first == panel.focus }?.top ?: 0f
	Column(
		Modifier
			.padding(vertical = layout.marginY.toFloat().dp)
			.padding(end = layout.marginX.toFloat().dp)
			.width(GlassesCanvas.PANEL_WIDTH.dp)
			.fillMaxHeight()
			.clip(RoundedCornerShape(GlassesCanvas.RADIUS.dp))
			.background(lucent.sheet)
			.padding(GlassesCanvas.TILE_GAP.dp),
		verticalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp),
	) {
		GlassesLabel(panel.title, GlassesText.Headline, lucent.ink, maxLines = 2)
		BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
			val shift by animateFloatAsState(max(focusTop + GlassesCanvas.OPTION * 2 - maxHeight.value, 0f), glide, label = "panel")
			for (line in lines) {
				key(line.id) {
					Box(Modifier.offset { IntOffset(0, ((line.top - shift) * density).toInt()) }) {
						val entry = line.option
						if (entry != null) OptionLine(entry.second, entry.first == panel.focus) else SectionLine(line.section.orEmpty())
					}
				}
			}
		}
	}
}

@Composable
private fun OptionLine(option: GlassesOption, focused: Boolean) {
	val ink = if (focused) lucent.focusInk else lucent.ink
	Row(
		Modifier.fillMaxWidth().height(GlassesCanvas.OPTION.dp)
			.clip(RoundedCornerShape((GlassesCanvas.RADIUS / 2).dp))
			.background(if (focused) lucent.focus else Color.Transparent)
			.padding(horizontal = GlassesCanvas.TILE_GAP.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp),
	) {
		GlassesLabel(option.title, GlassesText.Body, ink, Modifier.weight(1f))
		if (option.checked) Icon(painterResource(R.drawable.ic_check), null, tint = ink, modifier = Modifier.size(GlassesText.Body.size.dp))
	}
}

@Composable
private fun SectionLine(title: String) {
	GlassesLabel(
		title, GlassesText.Caption, lucent.ink2,
		Modifier.height((GlassesCanvas.SUBCAPTION + GlassesCanvas.TITLE_GAP).dp).padding(horizontal = GlassesCanvas.TILE_GAP.dp).fillMaxWidth(),
	)
}

private class PanelLine(val id: String, val top: Float, val section: String? = null, val option: Pair<Int, GlassesOption>? = null)

/** Each option, with a heading where its section starts. */
private fun panelLines(options: List<GlassesOption>): List<PanelLine> {
	val lines = ArrayList<PanelLine>()
	var top = 0f
	var section: String? = null
	options.forEachIndexed { index, option ->
		val heading = option.section
		if (heading != null && heading != section) {
			lines += PanelLine("section.$heading", top, section = heading)
			top += GlassesCanvas.SUBCAPTION + GlassesCanvas.TITLE_GAP
		}
		section = heading
		lines += PanelLine(option.id, top, option = index to option)
		top += GlassesCanvas.OPTION
	}
	return lines
}

@Composable
private fun Toast(text: String, layout: BigScreenLayout, modifier: Modifier) {
	Box(
		modifier.padding(bottom = layout.marginY.toFloat().dp).height(GlassesCanvas.OPTION.dp)
			.clip(CircleShape).background(lucent.glassRaised).padding(horizontal = GlassesCanvas.TILE_GAP.dp),
		contentAlignment = Alignment.Center,
	) {
		GlassesLabel(text, GlassesText.Body, lucent.ink)
	}
}

/** A failure or an empty state in the middle of the screen. */
@Composable
private fun Notice(icon: Int, title: String, message: String, modifier: Modifier = Modifier) {
	Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
		Icon(painterResource(icon), null, tint = lucent.warning, modifier = Modifier.size(GlassesCanvas.GLYPH.dp))
		Box(Modifier.height(GlassesCanvas.TITLE_GAP.dp))
		GlassesLabel(title, GlassesText.Headline, lucent.ink, align = TextAlign.Center)
		GlassesLabel(message, GlassesText.Body, lucent.ink2, Modifier.width(GlassesCanvas.OVERVIEW_WIDTH.dp), maxLines = 3, align = TextAlign.Center)
	}
}

/**
 * The level as the volume buttons, or the remote's two fingers, change it. Panel care: 380 x 10 design points at the
 * top right, up for 2 s after the last change and then faded out; nothing else on the screen stays still around it.
 */
@Composable
private fun VolumeBar(center: GlassesCenter, layout: BigScreenLayout) {
	var visible by remember { mutableStateOf(false) }
	val stamp = center.volumeStamp
	LaunchedEffect(stamp) {
		if (stamp == 0) return@LaunchedEffect
		visible = true
		delay(VOLUME_DWELL_MS)
		visible = false
	}
	Box(Modifier.fillMaxSize().padding(horizontal = layout.marginX.toFloat().dp, vertical = layout.marginY.toFloat().dp), contentAlignment = Alignment.TopEnd) {
		AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
			Row(
				Modifier.clip(CircleShape).background(lucent.glass).padding(horizontal = GlassesCanvas.TILE_GAP.dp, vertical = GlassesCanvas.TITLE_GAP.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp),
			) {
				Icon(
					painterResource(if (center.volume <= 0f) R.drawable.ic_volume_off else R.drawable.ic_volume_up), null,
					tint = lucent.ink, modifier = Modifier.size(GlassesText.Body.size.dp),
				)
				Box(Modifier.width(GlassesCanvas.VOLUME_WIDTH.dp).height(GlassesCanvas.BAR_HEIGHT.dp).clip(CircleShape).background(lucent.trackOff), contentAlignment = Alignment.CenterStart) {
					Box(Modifier.fillMaxHeight().width((GlassesCanvas.VOLUME_WIDTH * center.volume.coerceIn(0f, 1f)).dp).background(lucent.accent))
				}
			}
		}
	}
}

private const val VOLUME_DWELL_MS = 2_000L

// MARK: Player

/** How long the player's bar stays after a press while the title plays. Panel care: it hides, so no bright bar sits in one place. */
private const val CHROME_DWELL_MS = 3_000L
private const val POSITION_POLL_MS = 250L

/**
 * The player on the big screen (DESIGN §8.9): the picture fitted to the canvas, subtitles in the profile's style, and
 * while the controls show, the title above and the timeline below on glass.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun GlassesPlayerView(model: PlayerModel, navigator: GlassesNavigator, container: AppContainer, layout: BigScreenLayout) {
	val engine = model.player
	var chrome by remember { mutableStateOf(true) }
	val stamp = navigator.chromeStamp
	LaunchedEffect(stamp) {
		chrome = true
		delay(CHROME_DWELL_MS)
		chrome = false
	}
	val scrubbed = navigator.scrubTime
	val tv = navigator.link?.takeIf { navigator.isTelevision }
	val candidate = model.offers.offer.takeIf { tv != null }
	var raised by remember(candidate) { mutableStateOf(candidate != null && !(candidate is Offer.UpNext && candidate.carried)) }
	LaunchedEffect(candidate) { if (raised) { delay(8_000); raised = false } }
	val controls = chrome || scrubbed != null || !model.isPlaying || model.phase != PlayerPhase.Playing
	val tvOffer = candidate.takeIf { (raised || controls || model.offers.countdown != null) && navigator.panel == null && scrubbed == null }
	androidx.compose.runtime.SideEffect { tv?.visibleOffer = tvOffer }
	val profile by remember(container) { container.session.state.map { it.user?.uuid }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
	val look by remember(container, profile) { container.settings.subtitleLook(profile) }.collectAsStateWithLifecycle(SubtitleLook())
	LaunchedEffect(model, look.reveal) { model.tracks.setReveal(look.reveal) }
	Box(Modifier.fillMaxSize().background(Color.Black)) {
		if (engine != null) {
			AndroidView(
				factory = { context ->
					PlayerView(context).apply {
						useController = false
						resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
						setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
						subtitleView?.visibility = android.view.View.GONE
					}
				},
				update = { it.player = engine; it.resizeMode = tv?.aspect?.resizeMode ?: AspectRatioFrameLayout.RESIZE_MODE_FIT },
				onRelease = { it.player = null },
				modifier = Modifier.fillMaxSize(),
			)
			CaptionView(cues = model.tracks.cues, look = look, controlsUp = controls, modifier = Modifier.fillMaxSize())
		}
		AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
			var positionMs by remember { mutableLongStateOf(model.movieMs()) }
			LaunchedEffect(model) { while (true) { positionMs = model.movieMs(); delay(POSITION_POLL_MS) } }
			PlayerOsd(model, positionMs, scrubbed, layout, tvOffer != null)
		}
		if (controls && model.showsStats && layout.kind == DisplayKind.Tv) {
			val lines = remember(stamp, model.phase) { model.stats().all.filter { it.label in setOf("Route", "Video", "Picture", "Start", "Frames", "Buffer") } }
			Column(Modifier.align(Alignment.TopEnd).padding(horizontal = layout.marginX.toFloat().dp, vertical = layout.marginY.toFloat().dp)
				.width(580.dp).clip(RoundedCornerShape(GlassesCanvas.RADIUS.dp)).background(lucent.sheet).padding(GlassesCanvas.TILE_GAP.dp)) {
				lines.forEach { GlassesLabel("${it.label}: ${it.value}", GlassesText.Caption, lucent.ink, maxLines = 2) }
			}
		}
		PlayerCenter(model, scrubbed, navigator.isTelevision)
		if (tv != null && navigator.panel == null) com.nphil.marquee.ui.player.SubtitleSearchBanner(model.subtitleFinder, Modifier.align(Alignment.TopCenter).padding(top = layout.marginY.toFloat().dp))
		if (tv != null && tvOffer != null && scrubbed == null && navigator.panel == null) {
			val watch = tv.watchFocus === tvOffer
			Row(Modifier.align(Alignment.BottomEnd).padding(end = layout.marginX.toFloat().dp,
				bottom = layout.marginY.toFloat().dp + if (controls) (GlassesCanvas.OPTION * 2).dp else 0.dp),
				horizontalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp)) {
				IntroButton(if (tvOffer is Offer.SkipIntro) "Watch Intro" else "Watch Credits", watch, { tv.finishOffer(true) })
				IntroButton(when (tvOffer) { is Offer.UpNext -> "Next Episode"; is Offer.SkipCredits -> "Skip Credits"; is Offer.SkipIntro -> "Skip Intro" }, !watch, { tv.finishOffer(false) }) {
					model.offers.countdown?.let { CountdownRing(it, if (watch) lucent.ink else lucent.accentInk, Modifier.size(GlassesText.Body.size.dp)) }
				}
			}
		}
	}
}

@Composable
private fun IntroButton(label: String, active: Boolean, press: () -> Unit, trailing: @Composable () -> Unit = {}) {
	Row(Modifier.height(GlassesCanvas.OPTION.dp).clip(CircleShape).background(if (active) lucent.accent else lucent.sheet)
		.semantics { selected = active }.clickable(role = Role.Button, onClick = press).padding(horizontal = GlassesCanvas.TILE_GAP.dp),
		horizontalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
		GlassesLabel(label, GlassesText.Headline, if (active) lucent.accentInk else lucent.ink)
		trailing()
	}
}

@Composable
private fun PlayerOsd(model: PlayerModel, positionMs: Long, scrub: Double?, layout: BigScreenLayout, introControls: Boolean) {
	Column(Modifier.fillMaxSize().padding(horizontal = layout.marginX.toFloat().dp, vertical = layout.marginY.toFloat().dp)) {
		Column(
			Modifier.clip(RoundedCornerShape(GlassesCanvas.RADIUS.dp)).background(lucent.glass).padding(GlassesCanvas.TILE_GAP.dp),
			verticalArrangement = Arrangement.spacedBy(GlassesCanvas.CAPTION_GAP.dp),
		) {
			GlassesLabel(model.title, GlassesText.Title, lucent.ink)
			model.subtitle?.let { GlassesLabel(it, GlassesText.Body, lucent.ink2) }
			if (layout.kind == DisplayKind.Tv) model.plan?.let { GlassesLabel(it.route.title(), GlassesText.Label, lucent.accentText) }
		}
		Box(Modifier.weight(1f))
		if (!introControls) offerText(model)?.let { text ->
			Box(Modifier.fillMaxWidth().padding(bottom = GlassesCanvas.ROW_GAP.dp), contentAlignment = Alignment.CenterEnd) {
				Box(
					Modifier.height(GlassesCanvas.OPTION.dp).clip(CircleShape).background(lucent.accent).padding(horizontal = GlassesCanvas.TILE_GAP.dp),
					contentAlignment = Alignment.Center,
				) {
					GlassesLabel(text, GlassesText.Headline, lucent.accentInk)
				}
			}
		}
		Timeline(model, positionMs, scrub)
		if (layout.kind == DisplayKind.Tv && !introControls) {
			val help = if (scrub == null) "OK  Play / pause     ·     ← →  Seek     ·     ↓  Options"
				else "← →  Preview     ·     OK  Jump here     ·     Back  Cancel" + if (model.plan?.chapters.isNullOrEmpty()) "" else "     ·     ↑ ↓  Chapters"
			GlassesLabel(help, GlassesText.Caption, lucent.ink, Modifier.clip(CircleShape).background(lucent.glass).padding(horizontal = GlassesCanvas.TILE_GAP.dp, vertical = GlassesCanvas.CAPTION_GAP.dp))
		}
	}
}

@Composable
private fun Timeline(model: PlayerModel, positionMs: Long, scrub: Double?) {
	val duration = model.durationMs / 1000.0
	val time = scrub ?: (positionMs / 1000.0)
	val fraction = if (duration > 0) (time / duration).coerceIn(0.0, 1.0).toFloat() else 0f
	Column(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(GlassesCanvas.RADIUS.dp)).background(lucent.glass).padding(GlassesCanvas.TILE_GAP.dp),
		verticalArrangement = Arrangement.spacedBy(GlassesCanvas.TITLE_GAP.dp),
	) {
		BoxWithConstraints(Modifier.fillMaxWidth().height(GlassesCanvas.BAR_HEIGHT.dp).clip(CircleShape).background(lucent.trackOff)) {
			Box(Modifier.fillMaxHeight().width(maxWidth * fraction).background(lucent.accent))
		}
		Row(Modifier.fillMaxWidth()) {
			GlassesLabel(clock(time), GlassesText.Body, if (scrub == null) lucent.ink else lucent.accentText, Modifier.weight(1f))
			GlassesLabel("-" + clock(max(duration - time, 0.0)), GlassesText.Body, lucent.ink2)
		}
	}
}

@Composable
private fun PlayerCenter(model: PlayerModel, scrub: Double?, television: Boolean) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		when (val phase = model.phase) {
			is PlayerPhase.Failed -> Notice(R.drawable.ic_error, "Couldn\u2019t play", phase.message)
			PlayerPhase.Starting -> LoadingIndicator(Modifier.size(GlassesCanvas.GLYPH.dp), color = lucent.ink)
			else -> when {
				scrub != null && television -> Column(Modifier.clip(RoundedCornerShape(GlassesCanvas.RADIUS.dp)).background(lucent.sheet).padding(GlassesCanvas.TITLE_GAP.dp), horizontalAlignment = Alignment.CenterHorizontally) {
					val frames = model.previews
					val image = remember(frames, frames?.revision, scrub) { frames?.frame(scrub) }
					image?.let { androidx.compose.foundation.Image(it, "Seek preview", Modifier.size(384.dp, 216.dp), contentScale = ContentScale.Fit) }
					GlassesLabel(clock(scrub), GlassesText.Title, lucent.accentText)
					val chapters = model.plan?.chapters.orEmpty()
					val index = com.nphil.marquee.ui.player.chapterAt(chapters, (scrub * 1000).toLong())
					chapters.getOrNull(index)?.let { GlassesLabel(com.nphil.marquee.ui.player.chapterName(it, index), GlassesText.Body, lucent.ink) }
				}
				model.isBuffering -> LoadingIndicator(Modifier.size(GlassesCanvas.GLYPH.dp), color = lucent.ink)
				scrub != null -> Box(Modifier.clip(RoundedCornerShape(GlassesCanvas.RADIUS.dp)).background(lucent.glass).padding(GlassesCanvas.TILE_GAP.dp)) {
					GlassesLabel(clock(scrub), GlassesText.Display, lucent.ink)
				}
				!model.isPlaying -> Box(Modifier.size((GlassesCanvas.GLYPH * 2).dp).clip(CircleShape).background(lucent.glass), contentAlignment = Alignment.Center) {
					Icon(painterResource(R.drawable.ic_pause), null, tint = lucent.ink, modifier = Modifier.size(GlassesCanvas.GLYPH.dp))
				}
			}
		}
	}
}

private fun offerText(model: PlayerModel): String? = when (val offer = model.offers.offer) {
	is Offer.SkipIntro -> "Tap to Skip Intro"
	is Offer.SkipCredits -> "Tap to Skip Credits"
	is Offer.UpNext -> "Tap for Next: ${offer.next.title}"
	null -> null
}

/** [seconds] as `m:ss`, or `h:mm:ss` from an hour up. */
internal fun clock(seconds: Double): String {
	val whole = max(seconds, 0.0).toInt()
	val (hours, minutes, rest) = Triple(whole / 3600, whole / 60 % 60, whole % 60)
	return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest) else String.format(Locale.ROOT, "%d:%02d", minutes, rest)
}
