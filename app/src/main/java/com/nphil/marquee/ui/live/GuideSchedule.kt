package com.nphil.marquee.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nphil.marquee.R
import com.nphil.marquee.core.DvrMark
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.core.GuideWindow
import com.nphil.marquee.data.LiveLists
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.launch

/** Layout follows the room the shell gives the page, not the device's orientation or total screen size. */
internal fun usesGuideGrid(width: Dp, fontScale: Float): Boolean = width >= 600.dp && fontScale < 1.3f

@Composable
internal fun GuideSchedule(
	guide: GuideWindow,
	channels: List<GuideChannel>,
	lists: LiveLists,
	now: Double,
	cursor: MutableDoubleState,
	rows: LazyListState,
	favourite: (GuideChannel) -> Unit,
	watch: (GuideChannel) -> Unit,
	openDetails: ((GuideChannel, GuideProgramme) -> Unit)? = null,
	mark: (String, Double) -> DvrMark?,
	select: (GuideChannel, GuideProgramme) -> Unit,
) {
	val fontScale = LocalDensity.current.fontScale
	BoxWithConstraints(Modifier.fillMaxSize()) {
		if (usesGuideGrid(maxWidth, fontScale)) {
			WideSchedule(guide, channels, lists, now, cursor, rows, maxWidth, favourite, watch, openDetails, mark, select)
		} else CompactSchedule(guide, channels, lists, now, cursor, rows, favourite, watch, openDetails, mark, select)
	}
}

private const val SLOT_SECONDS = 1800.0
private val Slot = 150.dp
private val Rail = 148.dp
private val GridRow = 76.dp

@Composable
private fun CompactSchedule(
	guide: GuideWindow, channels: List<GuideChannel>, lists: LiveLists, now: Double,
	cursor: MutableDoubleState, rows: LazyListState,
	favourite: (GuideChannel) -> Unit, watch: (GuideChannel) -> Unit,
	openDetails: ((GuideChannel, GuideProgramme) -> Unit)?, mark: (String, Double) -> DvrMark?,
	select: (GuideChannel, GuideProgramme) -> Unit,
) {
	val at = cursor.doubleValue.takeUnless(Double::isNaN)?.coerceIn(guide.from, guide.to) ?: now
	val first = remember(guide.from) { floor(guide.from / SLOT_SECONDS) * SLOT_SECONDS }
	val count = ceil((guide.to - first) / SLOT_SECONDS).toInt()
	val nearest = ((at - first) / SLOT_SECONDS).toInt().coerceIn(0, maxOf(0, count - 1))
	val times = rememberLazyListState(nearest)
	LaunchedEffect(first, nearest) { if (count > 0) times.scrollToItem(nearest) }
	Column(Modifier.fillMaxSize().testTag("live-guide-list")) {
		TimeHeader(at, backToNow = { cursor.doubleValue = Double.NaN })
		LazyRow(state = times, horizontalArrangement = Arrangement.spacedBy(Space.s2), modifier = Modifier.fillMaxWidth()) {
			items(count) { index ->
				val time = first + index * SLOT_SECONDS
				val active = index == nearest
				TextAction(rememberGuideTime(time), { cursor.doubleValue = maxOf(time, guide.from) }, Modifier
					.clip(RoundedCornerShape(Radius.control)).background(if (active) lucent.glassRaised else lucent.canvas))
			}
		}
		HorizontalDivider(color = lucent.edge)
		LazyColumn(state = rows, modifier = Modifier.weight(1f)) {
			items(channels, key = { it.id }) { channel ->
				val (on, next) = guide.programmes(channel.id, at)
				Column(Modifier.fillMaxWidth().padding(horizontal = Space.inset, vertical = Space.s2)) {
					ChannelButton(channel, channel.id in lists.favourites, { watch(channel) }, { favourite(channel) })
					if (on == null && next == null) CompactProgramme("No schedule", null, at) { watch(channel) }
					if (on != null) CompactProgramme(
						if (on.isOn(now)) "On now" else "At this time", on, at,
						mark(channel.id, on.start), openDetails?.let { { it(channel, on) } },
					) { select(channel, on) }
					if (next != null) CompactProgramme(
						"Next · ${rememberGuideTime(next.start)}", next, at, mark(channel.id, next.start),
					) { select(channel, next) }
				}
				HorizontalDivider(color = lucent.edge)
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactProgramme(
	label: String, programme: GuideProgramme?, at: Double, mark: DvrMark? = null,
	details: (() -> Unit)? = null, select: () -> Unit,
) {
	Column(
		Modifier.fillMaxWidth().heightIn(min = Size.target).clip(RoundedCornerShape(Radius.control))
			.combinedClickable(role = Role.Button, onClickLabel = if (programme == null) "Watch channel" else null,
				onLongClick = details, onLongClickLabel = "Programme details", onClick = select)
			.padding(horizontal = Space.s3, vertical = Space.s1),
		verticalArrangement = Arrangement.spacedBy(Space.s1),
	) {
		Text(if (mark == null) label else "$label · ${mark.label}", style = MaterialTheme.typography.labelMedium, color = lucent.ink2)
		if (programme != null) {
			Text(programme.title, style = MaterialTheme.typography.titleSmall, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
			if (label == "On now") LinearProgressIndicator(
				{ programme.progress(at) }, Modifier.width(140.dp), color = lucent.live, trackColor = lucent.trackOff,
			)
		}
	}
}

@Composable
private fun TimeHeader(time: Double, backToNow: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(horizontal = Space.inset), verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Text(rememberGuideTime(time, includeDay = true), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = lucent.ink2)
		TextAction("Jump to Now", backToNow)
	}
}

@Composable
private fun WideSchedule(
	guide: GuideWindow, channels: List<GuideChannel>, lists: LiveLists, now: Double,
	cursor: MutableDoubleState, rows: LazyListState, viewport: Dp,
	favourite: (GuideChannel) -> Unit, watch: (GuideChannel) -> Unit,
	openDetails: ((GuideChannel, GuideProgramme) -> Unit)?, mark: (String, Double) -> DvrMark?,
	select: (GuideChannel, GuideProgramme) -> Unit,
) {
	val density = LocalDensity.current
	val pxPerSlot = with(density) { Slot.toPx() }
	val first = remember(guide.from) { floor(guide.from / SLOT_SECONDS) * SLOT_SECONDS }
	val timeline = Slot * ((guide.to - first) / SLOT_SECONDS).toFloat()
	val lanes = maxOf(1, ceil((viewport - Rail) / Slot).toInt())
	// Leave room for the Now line beside the pinned channels; a saved cursor is the actual left edge.
	val wanted = cursor.doubleValue.takeUnless(Double::isNaN) ?: (now - 600)
	fun position(time: Double) = (((time.coerceIn(first, guide.to) - first) / SLOT_SECONDS) * pxPerSlot).toInt()
	val scroll = rememberScrollState(position(wanted))
	val dragging by scroll.interactionSource.collectIsDraggedAsState()
	val leftSlot by remember(scroll, pxPerSlot) { derivedStateOf { (scroll.value / pxPerSlot).toInt() } }
	val leftTime = first + leftSlot * SLOT_SECONDS
	val rightTime = leftTime + (lanes + 1) * SLOT_SECONDS
	val leftMinute by remember(scroll, first, pxPerSlot, guide.from) {
		derivedStateOf { maxOf(guide.from, first + floor((scroll.value / pxPerSlot * SLOT_SECONDS) / 60) * 60) }
	}
	val scope = androidx.compose.runtime.rememberCoroutineScope()
	// An extended window can satisfy a cursor restored from the previous visit even if the first Plex response cannot.
	LaunchedEffect(first, timeline, pxPerSlot) { scroll.scrollTo(position(wanted)) }
	LaunchedEffect(scroll, first, pxPerSlot) {
		var userMoved = false
		snapshotFlow { dragging to scroll.isScrollInProgress }.collect { (dragged, moving) ->
			if (dragged) userMoved = true
			if (!dragged && !moving && userMoved) {
				cursor.doubleValue = first + scroll.value / pxPerSlot * SLOT_SECONDS
				userMoved = false
			}
		}
	}
	Column(Modifier.fillMaxSize().testTag("live-guide-grid")) {
		TimeHeader(leftMinute, backToNow = {
			cursor.doubleValue = Double.NaN
			scope.launch { scroll.scrollTo(position(now - 600)); cursor.doubleValue = Double.NaN }
		})
		Column(Modifier.weight(1f).horizontalScroll(scroll).width(Rail + timeline)) {
			Row(Modifier.fillMaxWidth().height(Size.target)) {
				Box(Modifier.offset { IntOffset(scroll.value, 0) }.zIndex(1f).width(Rail).height(Size.target).background(lucent.sheet)) {
					Text("Channel", Modifier.align(Alignment.CenterStart).padding(horizontal = Space.s3), style = MaterialTheme.typography.labelLarge, color = lucent.ink2)
				}
				Box(Modifier.width(timeline).height(Size.target).background(lucent.sheet).nowLine(now, first)) {
					for (index in leftSlot..(leftSlot + lanes + 1)) {
						val at = first + index * SLOT_SECONDS
						if (at >= guide.to) break
						Text(rememberGuideTime(at), Modifier.offset(x = Slot * index).width(Slot).padding(Space.s3),
							style = MaterialTheme.typography.labelLarge, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
			}
			HorizontalDivider(color = lucent.edge)
			LazyColumn(state = rows, modifier = Modifier.weight(1f)) {
				items(channels, key = { it.id }) { channel ->
					Row(Modifier.fillMaxWidth().height(GridRow)) {
						ChannelButton(channel, channel.id in lists.favourites, { watch(channel) }, { favourite(channel) },
							Modifier.offset { IntOffset(scroll.value, 0) }.zIndex(1f).width(Rail).height(GridRow).background(lucent.sheet), compact = true)
						Box(Modifier.height(GridRow).width(timeline).nowLine(now, first)) {
							val airings = guide.airings[channel.id].orEmpty()
							if (airings.isEmpty()) {
								TextAction("Watch channel · No schedule", { watch(channel) }, Modifier.offset { IntOffset(scroll.value, 0) }
									.align(Alignment.CenterStart).padding(horizontal = Space.s3))
							} else {
								for (index in guide.firstEndingAfter(channel.id, leftTime) until airings.size) {
									val programme = airings[index]
									if (programme.start >= rightTime || programme.start >= guide.to) break
									val start = maxOf(programme.start, first)
									val end = minOf(programme.end, guide.to)
									val x = Slot * ((start - first) / SLOT_SECONDS).toFloat()
									key(programme.id) {
										GridProgramme(programme, channel, programme.isOn(now), scroll, x,
											Modifier.offset(x = x).width(Slot * ((end - start) / SLOT_SECONDS).toFloat()).height(GridRow),
											openDetails?.let { { it(channel, programme) } },
											mark(channel.id, programme.start),
										) { select(channel, programme) }
									}
								}
							}
						}
					}
					HorizontalDivider(color = lucent.edge)
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridProgramme(
	programme: GuideProgramme, channel: GuideChannel, onNow: Boolean, scroll: ScrollState, x: Dp,
	modifier: Modifier, details: (() -> Unit)?, mark: DvrMark?, select: () -> Unit,
) {
	val shape = RoundedCornerShape(Radius.control)
	BoxWithConstraints(modifier.padding(Space.s1).clip(shape)
		.background(if (onNow) lucent.glassRaised else lucent.glass)
		.border(1.dp, if (onNow) lucent.accent else lucent.edge, shape)
		.combinedClickable(role = Role.Button, onClickLabel = if (onNow) "Watch channel" else "Programme details",
			onLongClickLabel = "Programme details", onLongClick = details, onClick = select)) {
		val from = rememberGuideTime(programme.start)
		val to = rememberGuideTime(programme.end)
		// Even a very short programme has its full title and time when focused by accessibility services.
		val description = "${programme.title}, ${channel.label}, $from to $to${if (onNow) ", on now" else ""}" +
			(mark?.let { ", ${it.label}" } ?: "")
		val width = maxWidth
		Box(Modifier.fillMaxSize().semantics { contentDescription = description }) {
			Column(Modifier.align(Alignment.CenterStart).fillMaxWidth().layout { child, room ->
				// Keep a still-visible title out from under the pinned channel column while its card slides behind it.
				val hidden = (scroll.value - x.roundToPx() - Space.s1.roundToPx()).coerceIn(0, room.maxWidth)
				val label = child.measure(room.copy(minWidth = 0, maxWidth = room.maxWidth - hidden))
				layout(room.maxWidth, label.height) { label.placeRelative(hidden, 0) }
			}.padding(horizontal = minOf(width / 8, Space.s3), vertical = Space.s1)) {
				Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
					if (mark != null && width >= 48.dp) GridRecordingMark(mark)
					Text(programme.title, style = MaterialTheme.typography.labelLarge, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
				}
				if (width >= 100.dp) Text("$from – $to", style = MaterialTheme.typography.labelSmall,
					color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

@Composable
private fun GridRecordingMark(mark: DvrMark) {
	val icon = when (mark) {
		DvrMark.Scheduled -> R.drawable.ic_schedule
		DvrMark.Series -> R.drawable.ic_video_library
		DvrMark.Recording -> R.drawable.ic_radio_button_unchecked
		DvrMark.Failed -> R.drawable.ic_error
		DvrMark.Recorded -> R.drawable.ic_check_circle
	}
	Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp),
		tint = if (mark == DvrMark.Failed) lucent.danger else if (mark == DvrMark.Recording) lucent.live else lucent.accentText)
}

@Composable
private fun Modifier.nowLine(now: Double, start: Double): Modifier {
	val ink = lucent.live
	return drawWithContent {
		drawContent()
		val logical = Slot.toPx() * ((now - start) / SLOT_SECONDS).toFloat()
		if (logical in 0f..size.width) {
			val x = if (layoutDirection == LayoutDirection.Ltr) logical else size.width - logical
			clipRect { drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx()) }
		}
	}
}