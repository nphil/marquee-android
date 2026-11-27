package com.nphil.marquee.television

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nphil.marquee.bigscreen.*
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.browse.posterLine
import com.nphil.marquee.core.SectionQuery
import com.nphil.marquee.core.logoPath
import com.nphil.marquee.core.progress
import com.nphil.marquee.core.showsWatched
import com.nphil.marquee.core.unwatchedBadge
import com.nphil.marquee.ui.components.PosterBadge
import com.nphil.marquee.ui.detail.marked
import com.nphil.marquee.ui.detail.LogoArt
import com.nphil.marquee.ui.browse.LocalPosterLabels

private val focusIn = tween<Float>(220, easing = LinearOutSlowInEasing)
private val focusOut = tween<Float>(160, easing = LinearOutSlowInEasing)
private val shelfTravel = tween<Float>(210, easing = LinearOutSlowInEasing)

private object TvSpace {
    val edge = 32.dp
    val gap = 10.dp
    val inset = 16.dp
    val card = RoundedCornerShape(10.dp)
}

@Composable internal fun Action(title: String, run: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Text(title, Modifier.onFocusChanged { focus = it.isFocused }.clip(CircleShape).background(if (focus) lucent.glassRaised else lucent.sheet)
        .clickable(onClick = run).padding(horizontal = TvSpace.inset, vertical = TvSpace.gap), color = if (focus) lucent.accentText else lucent.ink)
}

private fun LazyListState.fullyShows(index: Int): Boolean = layoutInfo.let { layout ->
    layout.visibleItemsInfo.any { it.index == index && it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset }
}

private suspend fun LazyListState.reveal(index: Int) {
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) { animateScrollToItem(index); return }
    val distance = when {
        item.offset < layout.viewportStartOffset -> item.offset - layout.viewportStartOffset
        else -> (item.offset + item.size - layout.viewportEndOffset).coerceAtLeast(0)
    }
    if (distance != 0) animateScrollBy(distance.toFloat(), shelfTravel)
}

internal val LocalTvWatchedChanges = staticCompositionLocalOf<Map<String, Boolean>> { emptyMap() }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun TvBrowse(page: GlassesPage, unobscured: Boolean, trailerRemote: TvTrailerTransport) {
    // The grid is shared with the external-display client. Reading its stamp observes a move, not an animation frame.
    @Suppress("UNUSED_VARIABLE") val moved = page.stamp
    val focused = page.focusedTile
    val hero = page.hero
    val rows = page.rows
    val hasNav = rows.firstOrNull()?.id == "menu"
    // Focus responds immediately. On Home, don't lay out a new synopsis or decode artwork for every intermediate key repeat.
    var preview by remember { mutableStateOf(focused) }
    LaunchedEffect(focused, hasNav) { if (hasNav && preview != null) kotlinx.coroutines.delay(240); preview = focused }
    val tile = if (hasNav) preview else focused
    val media = tile?.item
    val first = if (hasNav) 1 else 0
    val index = page.focus.row
    val list = rememberLazyListState(cacheWindow = remember { androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(220.dp, 220.dp) })
    val column = page.focus.column
    LaunchedEffect(index, first) { if (index in first until rows.size) list.reveal(index - first) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val art = hero?.backdrop ?: media?.art ?: media?.grandparentArt
        // Delay artwork changes on held arrows: do not fetch full-width art for each title the remote passes.
        var shown by remember { mutableStateOf(art) }; LaunchedEffect(art) { if (!hasNav) kotlinx.coroutines.delay(280); shown = art }
        val canvas = lucent.canvas
        val across = remember(canvas) { Brush.horizontalGradient(listOf(canvas, canvas.copy(alpha = .90f), canvas.copy(alpha = .2f))) }
        val down = remember(canvas) { Brush.verticalGradient(listOf(canvas.copy(alpha = .08f), canvas.copy(alpha = .2f), canvas)) }
        shown?.let {
            // Reuse the shaded backdrop while focus and shelves move. Blending three full-screen layers every frame is expensive on TV GPUs.
            Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                PlexArt(it, this@BoxWithConstraints.maxWidth / 2, this@BoxWithConstraints.maxHeight / 2, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(across).background(down))
            }
        }
        page.trailer?.let { trailer ->
            if (unobscured && index == 0) key(trailer.ratingKey) {
                com.nphil.marquee.playback.TrailerPreview(trailer, true, Modifier.fillMaxSize(), remember(trailerRemote) { trailerRemote::attach })
                if (!trailerRemote.full) Box(Modifier.fillMaxSize().background(across).background(down))
            }
        }
        androidx.activity.compose.BackHandler(trailerRemote.full) { trailerRemote.collapse() }
        if (trailerRemote.full) {
            val name = page.trailer?.title?.trim()?.takeUnless { it.isEmpty() || it.equals("Trailer", ignoreCase = true) }
            TvTrailerHud(trailerRemote, name ?: page.hero?.title ?: page.title)
            return@BoxWithConstraints
        }
        val railWidth = maxWidth - TvSpace.edge * 2
        Column(Modifier.fillMaxSize().padding(horizontal = TvSpace.edge, vertical = TvSpace.inset), verticalArrangement = Arrangement.spacedBy(TvSpace.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.gap)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    // The adaptive launcher artwork includes transparent mask padding around the folded M.
                    androidx.compose.foundation.Image(painterResource(com.nphil.marquee.R.mipmap.ic_launcher_foreground), "Marquee", Modifier.requiredSize(62.dp))
                }
                if (hasNav) {
                    val strip = rememberLazyListState()
                    val col = page.focus.column(inRow = 0)
                    LaunchedEffect(col) { if (col in rows[0].tiles.indices && !strip.fullyShows(col)) strip.animateScrollToItem(col) }
                    LazyRow(Modifier.weight(1f).clip(CircleShape).background(lucent.sheet), state = strip, contentPadding = PaddingValues(4.dp)) {
                        itemsIndexed(rows[0].tiles, key = { _, t -> t.id }) { i, t ->
                            val active = index == 0 && col == i
                            Text(t.title, Modifier.clip(CircleShape).background(if (active) lucent.glassRaised else lucent.sheet).clickable { page.focusOn(0, i); t.select() }.padding(horizontal = TvSpace.inset, vertical = 6.dp), color = if (active) lucent.accentText else lucent.ink)
                        }
                    }
                } else Text(page.title, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth(.74f).then(if (hasNav) Modifier.heightIn(min = 109.dp) else Modifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val title = hero?.title ?: media?.title ?: page.query.ifEmpty { tile?.title ?: page.title }
                val logo = if (hero != null) hero.logo else if (hasNav) media?.logoPath else null
                if (logo != null) key(logo) { LogoArt(logo, title, 270.dp, 54.dp) }
                else Text(title, color = lucent.ink, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (hero?.meta ?: tile?.subtitle)?.let { Text(it, color = lucent.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                (hero?.overview ?: media?.summary)?.takeIf { it.isNotBlank() }?.let { Text(it, color = lucent.ink2, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            when {
                page.problem != null -> Text(page.problem.orEmpty(), color = lucent.ink)
                rows.size == first && page.loading -> CircularProgressIndicator(color = lucent.accent)
                rows.isEmpty() -> Text("Nothing here yet", color = lucent.ink2)
                else -> LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(TvSpace.inset), contentPadding = PaddingValues(vertical = TvSpace.gap)) {
                    items(rows.size - first, key = { rows[it + first].id }) { offset -> val r = offset + first; Shelf(page, rows[r], r, if (index == r) column else -1, railWidth) }
                }
            }
        }
    }
}

@Composable private fun Shelf(page: GlassesPage, row: GlassesRow, r: Int, col: Int, railWidth: androidx.compose.ui.unit.Dp) {
    val width = when (row.shape) { TileShape.Poster -> 108.dp; TileShape.Landscape -> 176.dp; TileShape.Key -> 48.dp; TileShape.Card -> 140.dp }
    val height = when (row.shape) { TileShape.Poster -> 152.dp; TileShape.Landscape -> 99.dp; else -> 48.dp }
    val count = row.tiles.size
    val padding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)
    Column {
        row.title?.let { Text(it, color = lucent.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        if (width * count + TvSpace.gap * (count - 1).coerceAtLeast(0) + 10.dp <= railWidth) {
            // A library grid already splits titles into screen-width rows; it needs only the outer vertical scroller.
            Row(Modifier.padding(padding), horizontalArrangement = Arrangement.spacedBy(TvSpace.gap)) {
                row.tiles.forEachIndexed { c, t -> key(t.id) { TvTile(t, c == col, width, height, row.shape == TileShape.Poster, page.posterSort) { page.focusOn(r,c); t.select() } } }
            }
        } else {
            val rail = rememberLazyListState()
            LaunchedEffect(col) { if (col in row.tiles.indices) rail.reveal(col) }
            LazyRow(state = rail, horizontalArrangement = Arrangement.spacedBy(TvSpace.gap), contentPadding = padding) {
                itemsIndexed(row.tiles, key = { _, t -> t.id }) { c, t -> TvTile(t, c == col, width, height, row.shape == TileShape.Poster, page.posterSort) { page.focusOn(r,c); t.select() } }
            }
        }
    }
}

@Composable private fun TvTile(t: GlassesTile, selected: Boolean, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, poster: Boolean, sort: SectionQuery.Sort?, select: () -> Unit) {
    val labels = if (poster && t.captionItem != null) LocalPosterLabels.current else null
    val item = t.item
    val localMark = item?.let { LocalTvWatchedChanges.current[it.ratingKey] }
    val markedItem = if (item == null || localMark == null) item else remember(item, localMark) { item.marked(localMark) }
    val progress = if (markedItem == null) t.progress else markedItem.progress
    val remaining = markedItem?.unwatchedBadge
    val watched = markedItem?.showsWatched == true
    val scale = animateFloatAsState(if (selected) 1.05f else 1f, if (selected) focusIn else focusOut, label = "remote focus")
    Column(Modifier.width(width).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.semantics {
        this.selected = selected
        if (labels?.showsTitle == false) contentDescription = t.title
        when {
            remaining != null -> stateDescription = if (remaining == 1) "1 unwatched episode" else "$remaining unwatched episodes"
            watched -> stateDescription = "Watched"
            progress != null -> stateDescription = "In progress"
        }
    }.clickable(onClick = select)) {
        Box(Modifier.fillMaxWidth().height(height).clip(TvSpace.card).then(if (selected) Modifier.border(2.dp, lucent.accent, TvSpace.card) else Modifier).background(if (selected) lucent.glassRaised else lucent.sheet), contentAlignment = Alignment.Center) {
            when (val picture = t.art) {
                is GlassesArt.Plex -> PlexArt(picture.path, width, height)
                is GlassesArt.Symbol -> Icon(painterResource(picture.icon), contentDescription = t.title, tint = lucent.ink)
                GlassesArt.None -> Text(t.title, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PosterBadge(remaining, watched)
            progress?.let { Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it).height(3.dp).background(lucent.accent)) }
        }
        if (t.art !is GlassesArt.None && labels?.showsTitle != false) Text(t.title, Modifier.padding(top = 3.dp), color = if (selected) lucent.accentText else lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        if (labels != null) remember(t, labels, sort) { t.captionItem?.posterLine(labels, sort, null) }?.let { line ->
            Text(line.text, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
    }
}

@Composable internal fun TvOptions(panel: GlassesPanel?, nav: GlassesNavigator) {
    AnimatedVisibility(panel != null, enter = androidx.compose.animation.fadeIn(tween(150)), exit = androidx.compose.animation.fadeOut(tween(110))) {
        Box(Modifier.fillMaxSize().background(lucent.scrim), contentAlignment = Alignment.CenterEnd) {
            var last by remember { mutableStateOf(panel) }
            SideEffect { if (panel != null) last = panel }
            (panel ?: last)?.let { shown ->
                Box(Modifier.animateEnterExit(
                    enter = slideInHorizontally(tween(210, easing = LinearOutSlowInEasing)) { it / 5 },
                    exit = slideOutHorizontally(tween(140, easing = FastOutLinearInEasing)) { it / 5 },
                )) { TvPanel(shown, nav) }
            }
        }
    }
}

@Composable private fun TvPanel(panel: GlassesPanel, nav: GlassesNavigator) {
    val state = rememberLazyListState()
    LaunchedEffect(panel.focus) { if (panel.focus in panel.options.indices) state.reveal(panel.focus + 1) }
    LazyColumn(Modifier.width(310.dp).fillMaxHeight().background(lucent.sheet).padding(TvSpace.inset), state = state) {
        item { Text(panel.title, color = lucent.ink, style = MaterialTheme.typography.titleLarge) }
        itemsIndexed(panel.options, key = { _, o -> o.id }) { i, o ->
            Text((if (o.checked) "✓  " else "") + o.title, Modifier.fillMaxWidth().clip(TvSpace.card)
                .background(if (i == panel.focus) lucent.glassRaised else lucent.sheet)
                .semantics { selected = i == panel.focus }
                .clickable { panel.focus = i; nav.handle(GlassesInput.Select) }
                .padding(TvSpace.inset), color = if (i == panel.focus) lucent.accentText else lucent.ink)
        }
    }
}
