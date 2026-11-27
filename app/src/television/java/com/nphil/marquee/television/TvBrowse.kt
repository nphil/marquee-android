package com.nphil.marquee.television

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nphil.marquee.bigscreen.*
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.theme.lucent

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

@Composable internal fun TvBrowse(page: GlassesPage) {
    // The grid is shared with the external-display client. Reading its stamp observes a move, not an animation frame.
    @Suppress("UNUSED_VARIABLE") val moved = page.stamp
    val tile = page.focusedTile
    val media = tile?.item
    val hero = page.hero
    val rows = page.rows
    val hasNav = rows.firstOrNull()?.id == "menu"
    val first = if (hasNav) 1 else 0
    val index = page.focus.row
    val list = rememberLazyListState()
    LaunchedEffect(index, first) { if (index in first until rows.size) list.animateScrollToItem(index - first) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val art = hero?.backdrop ?: media?.art ?: media?.grandparentArt
        // Delay artwork changes on held arrows: do not fetch full-width art for each title the remote passes.
        var shown by remember { mutableStateOf(art) }; LaunchedEffect(art) { kotlinx.coroutines.delay(140); shown = art }
        val canvas = lucent.canvas
        shown?.let { PlexArt(it, maxWidth, maxHeight, Modifier.fillMaxSize()); Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(canvas, canvas.copy(alpha = .90f), canvas.copy(alpha = .2f))))); Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(canvas.copy(alpha = .08f), canvas.copy(alpha = .2f), canvas)))) }
        Column(Modifier.fillMaxSize().padding(horizontal = TvSpace.edge, vertical = TvSpace.inset), verticalArrangement = Arrangement.spacedBy(TvSpace.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.gap)) {
                Text("Marquee", color = lucent.ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                if (hasNav) {
                    val strip = rememberLazyListState()
                    val col = page.focus.column(inRow = 0)
                    LaunchedEffect(col) { if (col in rows[0].tiles.indices) strip.animateScrollToItem(col) }
                    LazyRow(Modifier.weight(1f).clip(CircleShape).background(lucent.sheet), state = strip, contentPadding = PaddingValues(4.dp)) {
                        itemsIndexed(rows[0].tiles, key = { _, t -> t.id }) { i, t ->
                            val active = index == 0 && col == i
                            Text(t.title, Modifier.clip(CircleShape).background(if (active) lucent.glassRaised else lucent.sheet).clickable { page.focusOn(0, i); t.select() }.padding(horizontal = TvSpace.inset, vertical = 6.dp), color = if (active) lucent.accentText else lucent.ink)
                        }
                    }
                } else Text(page.title, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth(.74f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(hero?.title ?: media?.title ?: page.query.ifEmpty { tile?.title ?: page.title }, color = lucent.ink, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (hero?.meta ?: tile?.subtitle)?.let { Text(it, color = lucent.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                (hero?.overview ?: media?.summary)?.takeIf { it.isNotBlank() }?.let { Text(it, color = lucent.ink2, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            when {
                page.problem != null -> Text(page.problem.orEmpty(), color = lucent.ink)
                rows.size == first && page.loading -> CircularProgressIndicator(color = lucent.accent)
                rows.isEmpty() -> Text("Nothing here yet", color = lucent.ink2)
                else -> LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(TvSpace.inset), contentPadding = PaddingValues(vertical = TvSpace.gap)) {
                    items(rows.size - first, key = { rows[it + first].id }) { offset -> val r = offset + first; Shelf(page, rows[r], r, index == r) }
                }
            }
        }
    }
}

@Composable private fun Shelf(page: GlassesPage, row: GlassesRow, r: Int, active: Boolean) {
    @Suppress("UNUSED_VARIABLE") val moved = page.stamp
    val rail = rememberLazyListState(); val col = page.focus.column(inRow = r)
    LaunchedEffect(col, active) { if (active && col in row.tiles.indices && rail.layoutInfo.visibleItemsInfo.none { it.index == col && it.offset >= 0 && it.offset + it.size <= rail.layoutInfo.viewportEndOffset }) rail.animateScrollToItem((col - 1).coerceAtLeast(0)) }
    Column {
        row.title?.let { Text(it, color = lucent.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        LazyRow(state = rail, horizontalArrangement = Arrangement.spacedBy(TvSpace.gap), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)) {
            itemsIndexed(row.tiles, key = { _, t -> t.id }) { c, t ->
                val selected = active && c == col; val scale = animateFloatAsState(if (selected) 1.055f else 1f, spring(dampingRatio = 1f), label = "remote focus")
                val width = when (row.shape) { TileShape.Poster -> 108.dp; TileShape.Landscape -> 176.dp; TileShape.Key -> 48.dp; TileShape.Card -> 140.dp }
                val height = when (row.shape) { TileShape.Poster -> 152.dp; TileShape.Landscape -> 99.dp; else -> 48.dp }
                Column(Modifier.width(width).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.semantics { this.selected = selected }.clickable { page.focusOn(r,c); t.select() }) {
                    Box(Modifier.fillMaxWidth().height(height).clip(TvSpace.card).background(if (selected) lucent.glassRaised else lucent.sheet), contentAlignment = Alignment.Center) {
                        when (val picture = t.art) {
                            is GlassesArt.Plex -> PlexArt(picture.path, width, height)
                            is GlassesArt.Symbol -> Icon(painterResource(picture.icon), contentDescription = t.title, tint = lucent.ink)
                            GlassesArt.None -> Text(t.title, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        t.progress?.let { Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it).height(3.dp).background(lucent.accent)) }
                    }
                    if (t.art !is GlassesArt.None) Text(t.title, Modifier.padding(top = 3.dp), color = if (selected) lucent.accentText else lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable internal fun TvPanel(panel: GlassesPanel, nav: GlassesNavigator) {
    val state = rememberLazyListState()
    LaunchedEffect(panel.focus) { if (panel.focus in panel.options.indices) state.animateScrollToItem(panel.focus.coerceAtLeast(0)) }
    Box(Modifier.fillMaxSize().background(lucent.scrim), contentAlignment = Alignment.CenterEnd) {
        LazyColumn(Modifier.width(310.dp).fillMaxHeight().background(lucent.sheet).padding(TvSpace.inset), state = state) {
            item { Text(panel.title, color = lucent.ink, style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(panel.options, key = { _, o -> o.id }) { i, o -> Text((if (o.checked) "✓  " else "") + o.title, Modifier.fillMaxWidth().clip(TvSpace.card).background(if (i == panel.focus) lucent.glassRaised else lucent.sheet).clickable { panel.focus = i; nav.handle(GlassesInput.Select) }.padding(TvSpace.inset), color = if (i == panel.focus) lucent.accentText else lucent.ink) }
        }
    }
}
