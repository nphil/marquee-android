package com.nphil.marquee.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.core.GuideWindow
import com.nphil.marquee.data.LiveLists
import com.nphil.marquee.live.LiveGuideModel
import com.nphil.marquee.live.DvrModel
import com.nphil.marquee.live.liveChannels
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.browse.ActionMenu
import com.nphil.marquee.ui.browse.MenuAction
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

@Composable
internal fun LiveGuideDestination(container: AppContainer, place: BrowsePlace, onBack: (() -> Unit)?) {
	val guide by container.live.guide.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	val model = guide?.takeIf { it.place == place }
	if (model == null) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading Live TV…") }
	} else LiveGuideScreen(model, onBack, { navigator?.openRecordings(model.key) }) { navigator?.openLive(model.key, it.id) }
}

@Composable
internal fun KeepLiveGuideCurrent(model: LiveGuideModel) {
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	LaunchedEffect(model, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.visible() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveGuideScreen(model: LiveGuideModel, back: (() -> Unit)?, recordings: () -> Unit, watch: (GuideChannel) -> Unit) {
	KeepLiveGuideCurrent(model)
	val schedule = model.guide
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val hasGuide = schedule != null
	LaunchedEffect(model, lifecycle, hasGuide) {
		if (hasGuide) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			while (true) {
				model.dvr.refreshSchedule()
				delay(60_000)
			}
		}
	}
	val mark = remember(model) { { channel: String, start: Double -> model.dvr.airing(channel, start)?.mark } }
	val lists by model.lists.collectAsStateWithLifecycle(LiveLists())
	val scope = rememberCoroutineScope()
	val refresh = { scope.launch { model.refresh() }; Unit }
	var category by rememberSaveable(model.key) { mutableStateOf("all") }
	val genre = schedule?.filters?.firstOrNull { it.key == category }
	val shownCategory = if (genre == null && category !in listOf("all", "favourites", "recent")) "all" else category
	var genreKeys by remember(genre, model.refreshedAt) { mutableStateOf<Set<String>?>(null) }
	var genreError by remember(genre, model.refreshedAt) { mutableStateOf(false) }
	var genreRetry by remember(genre) { mutableStateOf(0) }
	LaunchedEffect(genre, model.refreshedAt, genreRetry) {
		if (genre != null) {
			genreError = false
			try { genreKeys = model.genre(genre) } catch (_: CoreException) { genreError = true }
		}
	}
	val channels = remember(schedule, shownCategory, lists, genreKeys) {
		if (schedule == null) emptyList() else liveChannels(schedule, shownCategory, lists, genreKeys)
	}
	val rows = rememberLazyListState()
	val cursor = rememberSaveable(model.key) { mutableDoubleStateOf(Double.NaN) }
	var detailChannel by rememberSaveable(model.key) { mutableStateOf<String?>(null) }
	var detailAiring by rememberSaveable(model.key) { mutableStateOf<String?>(null) }
	val detail = detailChannel?.let { schedule?.airings?.get(it)?.firstOrNull { programme -> programme.id == detailAiring } }
	val selection = schedule?.channels?.firstOrNull { it.id == detailChannel }
	val openDetails: (GuideChannel, GuideProgramme) -> Unit = { channel, programme ->
		detailChannel = channel.id
		detailAiring = programme.id
	}
	Scaffold(
		containerColor = lucent.canvas,
		topBar = {
			TopAppBar(
				title = { Text("Live TV", modifier = Modifier.semantics { heading() }) },
				navigationIcon = { if (back != null) BackArrow(back) },
				actions = { if (hasGuide) TextAction("Recordings", recordings) },
			)
		},
	) { padded ->
		PullToRefreshBox(
			isRefreshing = model.refreshing && schedule == null,
			onRefresh = refresh,
			modifier = Modifier.fillMaxSize().padding(padded),
		) {
			if (schedule == null) {
				val (title, message) = when {
					model.problem != null -> "Guide unavailable" to model.problem.orEmpty()
					model.noDvr -> "No Plex Live TV setup" to "Set up Live TV on ${model.place.server.name} to see its channels here."
					else -> "Loading Live TV…" to "Looking for channels on ${model.place.server.name}."
				}
				EmptyState(R.drawable.ic_tv, title, message, Modifier.align(Alignment.Center)) {
					if (model.noDvr || model.problem != null) RetryButton("Try again", refresh)
				}
			} else {
				Column(Modifier.fillMaxSize()) {
					val problem = model.problem
					when {
						problem != null -> GuideBanner("Schedule not refreshed. $problem", refresh)
						!model.refreshing && model.refreshedAt == null -> GuideBanner("Showing the saved schedule.", refresh)
						schedule.tunerOffline -> GuideBanner("The Plex tuner is offline. The schedule is still available.", refresh)
					}
					Row(
						Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.inset),
						horizontalArrangement = Arrangement.spacedBy(Space.s2),
					) {
						for ((key, label) in listOf("all" to "All", "favourites" to "Favourites", "recent" to "Recent") +
							schedule.filters.map { it.key to it.title }) {
							FilterChip(category == key, {
								if (category != key) {
									category = key
									scope.launch { rows.scrollToItem(0) }
								}
							}, { Text(label) }, modifier = Modifier.heightIn(min = Size.target))
						}
					}
					if (model.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = lucent.accent)
					val empty = when {
						genreError -> "Couldn't load ${genre?.title.orEmpty()}" to "Plex couldn't load this channel filter."
						genre != null && genreKeys == null -> "Loading ${genre.title}…" to "Looking for matching channels."
						channels.isNotEmpty() -> null
						category == "favourites" -> "No favourites yet" to "Touch and hold a channel to add it to Favourites."
						category == "recent" -> "No recently watched channels" to "Watch a channel and it will appear here."
						genre != null -> "No matching channels" to "There are no channels in ${genre.title}."
						else -> "No channels" to "Check the channel lineup on ${model.place.server.name}."
					}
					if (empty != null) {
						Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
							EmptyState(R.drawable.ic_tv, empty.first, empty.second) {
								if (genreError) RetryButton("Try again") { genreRetry++ }
							}
						}
					} else {
						GuideSchedule(
							schedule, channels, lists, model.now, cursor, rows, model::toggleFavourite, watch, openDetails,
							mark = mark,
						) { channel, programme ->
							if (programme.isOn(model.now)) watch(channel) else openDetails(channel, programme)
						}
					}
				}
			}
		}
	}
	if (detail != null && selection != null) {
		ProgrammeDetails(selection, detail, model.now, dismiss = {
			detailAiring = null
			detailChannel = null
		}, watch = {
			detailAiring = null
			detailChannel = null
			watch(selection)
		}, dvr = model.dvr)
	}
}

@Composable
private fun GuideBanner(text: String, retry: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().background(lucent.glass).padding(start = Space.inset, end = Space.s2),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
		TextAction("Retry", retry)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelButton(
	channel: GuideChannel,
	favourite: Boolean,
	watch: () -> Unit,
	toggle: () -> Unit,
	modifier: Modifier = Modifier,
	compact: Boolean = false,
) {
	var menu by remember { mutableStateOf(false) }
	val label = if (favourite) "Remove from Favourites" else "Add to Favourites"
	Box(modifier.heightIn(min = Size.target).combinedClickable(
		role = Role.Button, onClickLabel = "Watch ${channel.label}", onClick = watch,
		onLongClickLabel = label, onLongClick = { menu = true },
	)) {
		Row(
			Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(horizontal = Space.s3, vertical = Space.s1),
			horizontalArrangement = Arrangement.spacedBy(Space.s2),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (channel.logo != null) {
				val edge = if (compact) 32.dp else 40.dp
				PlexArt(channel.logo, edge, edge, Modifier.clip(RoundedCornerShape(Radius.control)), ContentScale.Fit)
			}
			Column(Modifier.weight(1f)) {
				Text(channel.label, style = MaterialTheme.typography.labelLarge, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
				if (!compact && channel.title.isNotBlank() && !channel.label.contains(channel.title, ignoreCase = true)) {
					Text(channel.title, style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			}
			if (favourite) Icon(painterResource(R.drawable.ic_star), "Favourite", Modifier.size(16.dp), tint = lucent.accentText)
		}
		ActionMenu(menu, { menu = false }, listOf(MenuAction(R.drawable.ic_star, label, onClick = toggle)))
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProgrammeDetails(
	channel: GuideChannel, programme: GuideProgramme, now: Double, dismiss: () -> Unit, watch: () -> Unit,
	dvr: DvrModel? = null,
) {
	ModalBottomSheet(onDismissRequest = dismiss, containerColor = lucent.sheet) {
		Column(
			Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
				.padding(start = Space.inset, end = Space.inset, bottom = Space.s8),
			verticalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			Text(channel.label, style = MaterialTheme.typography.labelLarge, color = lucent.ink2)
			Text(programme.title, style = MaterialTheme.typography.headlineSmall, color = lucent.ink, modifier = Modifier.semantics { heading() })
			val episode = listOfNotNull(programme.episodeCode, programme.episodeTitle).joinToString(" · ")
			if (episode.isNotBlank()) Text(episode, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
			val start = rememberGuideTime(programme.start, includeDay = true)
			val end = rememberGuideTime(programme.end)
			Text("$start – $end", style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
				if (programme.isNew) ProgrammeTag("NEW")
				if (programme.isHD) ProgrammeTag("HD")
				programme.contentRating?.let { ProgrammeTag(it) }
			}
			if (programme.isOn(now)) FilledTonalButton(watch, Modifier.heightIn(min = Size.target)) {
				Text("Watch channel")
			}
			if (dvr != null) DvrProgrammeActions(dvr, channel.id, programme, now)
			(programme.art ?: programme.thumb)?.let {
				PlexArt(it, 320.dp, 180.dp, Modifier.clip(RoundedCornerShape(Radius.tile)))
			}
			programme.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = lucent.ink) }
		}
	}
}

@Composable
internal fun ProgrammeTag(text: String) {
	Text(
		text,
		Modifier.clip(RoundedCornerShape(Radius.control)).background(lucent.glassRaised).padding(horizontal = Space.s2, vertical = Space.s1),
		style = MaterialTheme.typography.labelSmall, color = lucent.ink,
	)
}

@Composable
internal fun rememberGuideTime(seconds: Double, includeDay: Boolean = false): String {
	val context = LocalContext.current
	val configuration = androidx.compose.ui.platform.LocalConfiguration.current
	return remember(seconds, configuration, includeDay) {
		val date = Date((seconds * 1000).toLong())
		val time = android.text.format.DateFormat.getTimeFormat(context).format(date)
		if (includeDay) "${android.text.format.DateFormat.getMediumDateFormat(context).format(date)} · $time" else time
	}
}

@Composable
internal fun rememberGuideDate(seconds: Double): String {
	val context = LocalContext.current
	val configuration = androidx.compose.ui.platform.LocalConfiguration.current
	return remember(seconds, configuration) {
		android.text.format.DateFormat.getMediumDateFormat(context).format(Date((seconds * 1000).toLong()))
	}
}