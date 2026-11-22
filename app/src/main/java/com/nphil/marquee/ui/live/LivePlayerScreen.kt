package com.nphil.marquee.ui.live

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.core.GuideWindow
import com.nphil.marquee.data.LiveLists
import com.nphil.marquee.data.PlaybackPrefs
import com.nphil.marquee.live.LivePlayerModel
import com.nphil.marquee.live.LivePlayerPhase
import com.nphil.marquee.live.liveChannels
import com.nphil.marquee.playback.HideSystemBars
import com.nphil.marquee.playback.LocalPlaybackHttp
import com.nphil.marquee.ui.WatchLive
import com.nphil.marquee.ui.player.PlayerAspect
import com.nphil.marquee.ui.player.GlassButton
import com.nphil.marquee.ui.player.GlassDisc
import com.nphil.marquee.ui.player.GlassPill
import com.nphil.marquee.ui.player.PictureInPicture
import com.nphil.marquee.ui.player.PlayerInk
import com.nphil.marquee.ui.player.WindowAction
import com.nphil.marquee.ui.player.rememberPictureInPicture
import com.nphil.marquee.ui.player.rememberPlayerAccent
import com.nphil.marquee.ui.player.shadowed
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun LivePlayerDestination(container: AppContainer, route: WatchLive, back: () -> Unit) {
	val place by container.browse.source.place.collectAsStateWithLifecycle()
	val active by container.live.guide.collectAsStateWithLifecycle()
	val guide = active?.takeIf { it.place == place && it.key == route.scope }
	val context = LocalContext.current
	val http = LocalPlaybackHttp.current ?: container.http
	if (guide == null) {
		// The saved route is scoped to the previous server and profile. Never reuse it after a profile change.
		LaunchedEffect(active, place) { if (active != null && active?.place == place) back() }
		Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
			CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading Live TV" })
		}
		return
	}
	val model: LivePlayerModel = viewModel { LivePlayerModel(container, context, http, guide, route.channel) }
	val preferences by remember(guide) { container.settings.playback(guide.place.profile) }
		.collectAsStateWithLifecycle(PlaybackPrefs())
	val close = { model.close(); back() }
	LivePlayer(model, preferences, close)
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LivePlayer(model: LivePlayerModel, preferences: PlaybackPrefs, close: () -> Unit) {
	HideSystemBars()
	KeepLiveGuideCurrent(model.guide)
	val player = model.player
	val pip = rememberPictureInPicture(player, model.phase == LivePlayerPhase.Ready, model.playing, false, preferences.skipInterval) {
		when (it) {
			WindowAction.Back -> model.skip(-preferences.skipInterval)
			WindowAction.Forward -> model.skip(preferences.skipInterval)
			WindowAction.PlayPause -> model.toggle()
			else -> Unit
		}
	}
	val activity = LocalActivity.current
	LaunchedEffect(model, preferences.keepsPlaying) { model.background(preferences.keepsPlaying) }
	LaunchedEffect(model) { model.start() }
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
		if (activity?.isChangingConfigurations != true) model.leftScreen(pip.active)
	}
	var showGuide by rememberSaveable { mutableStateOf(false) }
	var showControls by rememberSaveable { mutableStateOf(true) }
	var fits by rememberSaveable { mutableStateOf(true) }
	var action by remember { mutableStateOf(0) }
	var details by remember { mutableStateOf<Pair<GuideChannel, GuideProgramme>?>(null) }
	val notice = model.phase
	val controlsDwell = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
		3_000L, containsIcons = true, containsText = true, containsControls = true,
	) ?: 3_000L
	LaunchedEffect(model.playing, model.status, showGuide, showControls, action, notice, controlsDwell) {
		if (model.playing && model.status == null && notice == LivePlayerPhase.Ready && !showGuide && showControls) {
			delay(controlsDwell)
			showControls = false
		}
	}
	BackHandler {
		if (showGuide) { showGuide = false; showControls = true; action++ } else close()
	}
	val schedule = model.guide.guide
	val quick = showGuide && schedule != null && !pip.active
	val controls = !pip.active && !quick && (showControls || model.status != null || !model.playing || notice != LivePlayerPhase.Ready)
	val at = model.guide.now - model.position.behind / 1000.0
	val airing = model.channel?.let { schedule?.programmes(it.id, at)?.first }
	val fontScale = LocalDensity.current.fontScale
	BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
		// A trailing guide leaves the whole height of the picture on a tablet. Short or narrow windows keep a lower panel.
		val side = quick && maxWidth >= 680.dp && (maxHeight < 520.dp || fontScale < 1.3f)
		val panelWidth = if (side) minOf(420.dp, maxWidth * .48f) else maxWidth
		val panelHeight = if (side) maxHeight else minOf(maxHeight * .78f, maxOf(300.dp, maxHeight * .60f))
		Box(Modifier.fillMaxSize().padding(end = if (side) panelWidth else 0.dp, bottom = if (quick && !side) panelHeight else 0.dp)) {
			if (player != null) LiveVideo(model, pip, if (fits) PlayerAspect.Fit else PlayerAspect.Fill)
			if (!pip.active) {
				Box(Modifier.fillMaxSize().combinedClickable(
					onClickLabel = if (quick) "Close guide" else if (showControls) "Hide controls" else "Show controls",
					onClick = {
						if (quick) showGuide = false else showControls = !showControls
						action++
					},
					onDoubleClick = { if (!quick) fits = !fits },
				).pointerInput(quick, schedule != null, model) {
					if (quick) return@pointerInput
					var travelled = Offset.Zero
					detectDragGestures(onDragStart = { travelled = Offset.Zero }, onDragEnd = {
						when {
							abs(travelled.x) > abs(travelled.y) && abs(travelled.x) > 56.dp.toPx() -> {
								val steps = (travelled.x / 110.dp.toPx()).roundToInt()
								model.step(if (steps != 0) -steps else if (travelled.x > 0) -1 else 1)
								showControls = true
								action++
							}
							travelled.y < -60.dp.toPx() && schedule != null -> showGuide = true
							travelled.y > 140.dp.toPx() -> close()
						}
					}) { _, delta -> travelled += delta }
				})
				if (controls) {
					LiveChrome(model, airing, at, preferences.skipInterval, pip, close, { showGuide = true }, { action++ })
				}
			}
		}
		if (quick && schedule != null) {
			val lists by model.guide.lists.collectAsStateWithLifecycle(LiveLists())
			LiveQuickGuide(
				schedule, lists, model.channel?.id, model.guide.now,
				Modifier.align(if (side) Alignment.CenterEnd else Alignment.BottomCenter).width(panelWidth).height(panelHeight),
				dismiss = { showGuide = false; showControls = true; action++ }, favourite = model.guide::toggleFavourite,
				watch = { model.tune(it); showGuide = false; showControls = true; action++ },
				open = { channel, programme -> details = channel to programme },
			)
		}
	}
	details?.let { (channel, programme) ->
		ProgrammeDetails(channel, programme, model.guide.now, dismiss = { details = null }, watch = {
			model.tune(channel)
			details = null
			showGuide = false
			showControls = true
			action++
		})
	}
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveVideo(model: LivePlayerModel, pip: PictureInPicture, aspect: PlayerAspect) {
	AndroidView(
		factory = { context -> PlayerView(context).apply {
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			useController = false
			setKeepContentOnPlayerReset(true)
		} },
		update = {
			it.player = model.player
			it.keepScreenOn = model.playing
			it.resizeMode = aspect.resizeMode
		},
		onRelease = { it.player = null },
		modifier = Modifier.fillMaxSize().onGloballyPositioned { pip.videoBounds(it.boundsInWindow()) },
	)
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveChrome(
	model: LivePlayerModel, airing: GuideProgramme?, at: Double, seconds: Int, pip: PictureInPicture,
	close: () -> Unit, guide: () -> Unit, used: () -> Unit,
) {
	val accent = rememberPlayerAccent()
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
		Row(
			Modifier.fillMaxWidth().background(PlayerInk.topScrim).padding(Space.s3),
			verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			GlassButton(R.drawable.ic_close, "Close player", close)
			Column(Modifier.weight(1f)) {
				Text(model.channel?.label ?: "Live TV", style = shadowed(MaterialTheme.typography.titleMedium),
					color = PlayerInk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
				airing?.let {
					Text(it.title, style = shadowed(MaterialTheme.typography.bodyMedium), color = PlayerInk.ink2,
						maxLines = 2, overflow = TextOverflow.Ellipsis)
					val from = rememberGuideTime(it.start)
					val to = rememberGuideTime(it.end)
					LinearProgressIndicator(
						{ it.progress(at) }, Modifier.width(120.dp).semantics { contentDescription = "$from to $to" },
						color = accent, trackColor = PlayerInk.trackOff,
					)
				}
				if (model.phase == LivePlayerPhase.Ready) model.status?.let {
					Text(it, style = shadowed(MaterialTheme.typography.bodyMedium), color = PlayerInk.ink2)
				}
			}
			if (pip.supported && model.phase == LivePlayerPhase.Ready) GlassButton(R.drawable.ic_picture_in_picture, "Picture in picture", pip::enter)
		}
		Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
			when (val phase = model.phase) {
				is LivePlayerPhase.Failed -> StatusCard(phase.message, "Try again", model::retry)
				LivePlayerPhase.Away -> StatusCard("Live TV stopped while the player was away.", "Watch again", model::retry)
				LivePlayerPhase.Tuning -> StatusCard(model.status ?: "Tuning ${model.channel?.label.orEmpty()}", loading = true)
				LivePlayerPhase.Buffering -> StatusCard(model.status ?: "Buffering…", loading = true)
				LivePlayerPhase.Ready -> Transport(model, seconds, used)
			}
		}
		Column(Modifier.fillMaxWidth().background(PlayerInk.bottomScrim).padding(Space.s3)) {
			if (model.phase == LivePlayerPhase.Ready && model.position.reach > 0L) {
				val position = model.position
				var held by remember(model.channel?.id) { mutableStateOf<Float?>(null) }
				Slider(held ?: (1f - position.behind.toFloat() / position.reach).coerceIn(0f, 1f),
					onValueChange = { held = it; used() }, onValueChangeFinished = {
						held?.let { model.seekBehind((position.reach * (1 - it)).roundToLong()) }
						held = null
					},
					modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Live buffer" },
					colors = SliderDefaults.colors(thumbColor = PlayerInk.ink, activeTrackColor = accent, inactiveTrackColor = PlayerInk.trackOff),
				)
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
				if (model.phase == LivePlayerPhase.Ready) {
					val behind = model.position.behind
					val live = behind < 3000L && !model.paused
					GlassPill(if (live) "LIVE" else if (behind >= 3000L) "Go live · ${lag(behind)} behind" else "Go live",
						onClick = { model.goLive(); used() }, fill = if (live) accent else null,
						content = if (live) PlayerInk.onAccent else PlayerInk.ink)
				}
				model.lastChannel?.let { old -> GlassButton(R.drawable.ic_restart_alt, "Last channel, ${old.label}", { model.tune(old); used() }) }
				if (model.guide.guide?.channels?.isNotEmpty() == true) GlassPill("Guide", guide, icon = R.drawable.ic_chapters)
			}
		}
	}
}

@Composable
private fun StatusCard(message: String, action: String? = null, perform: () -> Unit = {}, loading: Boolean = false) {
	Column(
		Modifier.clip(RoundedCornerShape(Radius.card)).background(PlayerInk.glassPressed).padding(Space.s3),
		horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Text(message, color = PlayerInk.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
		if (loading) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), color = PlayerInk.ink, strokeWidth = 2.dp)
		if (action != null) GlassPill(action, perform)
	}
}

@Composable
private fun Transport(model: LivePlayerModel, seconds: Int, used: () -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
		if ((model.guide.guide?.channels?.size ?: 0) > 1) GlassDisc("Previous channel", { model.step(-1); used() }) {
			Icon(painterResource(R.drawable.ic_skip_next), null, Modifier.rotate(180f), tint = PlayerInk.ink)
		}
		if (model.position.canGoBack) GlassButton(R.drawable.ic_replay, "Back $seconds seconds", { model.skip(-seconds); used() })
		GlassDisc(if (model.paused) "Play" else "Pause", { model.toggle(); used() }, solid = true) {
			Icon(painterResource(if (model.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause), null, tint = PlayerInk.onSolid)
		}
		if (model.position.behind >= 3000L) GlassButton(R.drawable.ic_forward, "Forward $seconds seconds", { model.skip(seconds); used() })
		if ((model.guide.guide?.channels?.size ?: 0) > 1) GlassButton(R.drawable.ic_skip_next, "Next channel", { model.step(1); used() })
	}
}

@Composable
internal fun LiveQuickGuide(
	guide: GuideWindow, lists: LiveLists, tuned: String?, now: Double, modifier: Modifier,
	dismiss: () -> Unit, favourite: (GuideChannel) -> Unit, watch: (GuideChannel) -> Unit,
	open: (GuideChannel, GuideProgramme) -> Unit,
) {
	val recent = remember(guide, lists, tuned) { liveChannels(guide, "recent", lists, null).filterNot { it.id == tuned }.take(3) }
	val selected = if (recent.isEmpty()) guide.channels.indexOfFirst { it.id == tuned }.coerceAtLeast(0) else 0
	val scroll = rememberLazyListState(if (recent.isEmpty() && selected > 0) selected + 1 else 0)
	Column(modifier.clip(RoundedCornerShape(Radius.sheet)).background(lucent.sheet).testTag("live-quick-guide")
		.semantics { paneTitle = "Quick Guide" }) {
		Row(Modifier.fillMaxWidth().padding(start = Space.inset, end = Space.s2), verticalAlignment = Alignment.CenterVertically) {
			Text("Guide", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = lucent.ink)
			IconButton(dismiss) { Icon(painterResource(R.drawable.ic_close), "Close guide", tint = lucent.ink) }
		}
		HorizontalDivider(color = lucent.edge)
		LazyColumn(state = scroll, modifier = Modifier.weight(1f)) {
			if (recent.isNotEmpty()) {
				item { QuickHeading("Recent") }
				items(recent, key = { "recent:${it.id}" }) { channel ->
					ChannelButton(channel, channel.id in lists.favourites, { watch(channel) }, { favourite(channel) }, Modifier.fillMaxWidth())
				}
			}
			item { QuickHeading("All Channels") }
			items(guide.channels, key = { it.id }) { channel ->
				val (on, next) = guide.programmes(channel.id, now)
				Column(Modifier.fillMaxWidth().background(if (tuned == channel.id) lucent.glassRaised else lucent.sheet)) {
					ChannelButton(channel, channel.id in lists.favourites, { watch(channel) }, { favourite(channel) })
					if (tuned == channel.id) QuickHeading("Playing")
					QuickProgramme(if (on == null) "No programme on now" else "Now · ${on.title}",
						details = on?.let { { open(channel, it) } }) { watch(channel) }
					if (next != null) QuickProgramme("${rememberGuideTime(next.start)} · ${next.title}") { open(channel, next) }
				}
				HorizontalDivider(color = lucent.edge)
			}
		}
	}
}

@Composable
private fun QuickHeading(text: String) {
	Text(text, Modifier.padding(horizontal = Space.inset, vertical = Space.s1).semantics { heading() },
		style = MaterialTheme.typography.labelMedium, color = lucent.ink2)
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickProgramme(title: String, details: (() -> Unit)? = null, click: () -> Unit) {
	Box(Modifier.fillMaxWidth().heightIn(min = Size.target)
		.combinedClickable(onClick = click, onLongClick = details, onLongClickLabel = "Programme details")
		.padding(horizontal = Space.inset, vertical = Space.s1),
		contentAlignment = Alignment.CenterStart) {
		Text(title, style = MaterialTheme.typography.bodyMedium, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
	}
}

private fun lag(ms: Long): String {
	val seconds = ms / 1000
	return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
