package com.nphil.marquee.bigscreen

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nphil.marquee.browse.ItemActions
import com.nphil.marquee.browse.WatchlistModel
import com.nphil.marquee.core.Watchlist
import com.nphil.marquee.core.onWatchlist
import com.nphil.marquee.core.releasedWatchlist
import com.nphil.marquee.browse.LibraryOptions
import com.nphil.marquee.browse.browsableLibraries
import com.nphil.marquee.browse.SearchModel
import com.nphil.marquee.browse.SearchScope
import com.nphil.marquee.browse.StoredLibraryMemory
import com.nphil.marquee.browse.decodeLibraryView
import com.nphil.marquee.browse.encodeLibraryView
import com.nphil.marquee.data.TvShortcut
import com.nphil.marquee.core.logoPath
import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.searchHubs
import com.nphil.marquee.core.libraryLetters
import com.nphil.marquee.core.HomeLoad
import com.nphil.marquee.core.HomeRow
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SectionQuery
import com.nphil.marquee.core.SnapshotMode
import com.nphil.marquee.core.SubtitlePick
import com.nphil.marquee.core.children
import com.nphil.marquee.core.homeRows
import com.nphil.marquee.core.item as itemOf
import com.nphil.marquee.core.libraryPage as libraryItems
import com.nphil.marquee.core.extras
import com.nphil.marquee.core.related
import com.nphil.marquee.core.titlesOf
import com.nphil.marquee.ui.detail.trailerOf
import com.nphil.marquee.core.libraryRecommended
import com.nphil.marquee.core.libraryCollections
import com.nphil.marquee.core.libraryGenres
import com.nphil.marquee.core.copiesOf
import com.nphil.marquee.core.versionLabel
import com.nphil.marquee.core.Page
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.Offer
import com.nphil.marquee.playback.PlayerModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** What a tile draws in place of a picture, or as one. */
internal sealed interface GlassesArt {
	data object None : GlassesArt
	data class Symbol(@DrawableRes val icon: Int) : GlassesArt
	data class Plex(val path: String?) : GlassesArt
}

/** A tile's size in design points, which is also what its row's height is made of. */
internal enum class TileShape(val width: Float, val height: Float) {
	Poster(GlassesCanvas.POSTER.first, GlassesCanvas.POSTER.second),
	Landscape(GlassesCanvas.LANDSCAPE.first, GlassesCanvas.LANDSCAPE.second),
	Card(GlassesCanvas.CARD.first, GlassesCanvas.CARD.second),
	Key(GlassesCanvas.KEY.first, GlassesCanvas.KEY.second),
}

@Immutable
internal class GlassesTile(
	val id: String,
	val title: String,
	val subtitle: String? = null,
	val art: GlassesArt = GlassesArt.None,
	/** How much has been watched, 0 to 1. */
	val progress: Float? = null,
	/** The title behind the tile, for Play from Start among the options. */
	val item: PlexItem? = null,
	val captionItem: PlexItem? = item,
	val select: () -> Unit,
)

@Immutable
internal class GlassesRow(val id: String, val title: String?, val shape: TileShape, val tiles: List<GlassesTile>)

@Immutable
internal class GlassesHero(val title: String, val meta: String?, val overview: String?, val backdrop: String?, val logo: String?)

/** One page of the big screen: rows of tiles and where the focus sits among them. */
@Stable
internal class GlassesPage(val title: String, style: FocusGrid.Style = FocusGrid.Style.Shelves, val posterSort: SectionQuery.Sort? = null) {
	var hero by mutableStateOf<GlassesHero?>(null)
	var trailer by mutableStateOf<PlexItem?>(null)
	var rows by mutableStateOf<List<GlassesRow>>(emptyList())
		private set
	val focus = FocusGrid(emptyList(), style)

	/** Counts every change of [focus], which is not itself observable. */
	var stamp by mutableIntStateOf(0)
		private set
	var loading by mutableStateOf(true)
		private set
	var problem by mutableStateOf<String?>(null)
		private set

	/** Search: what has been typed. */
	var query by mutableStateOf("")

	/** Runs after each focus move, as when a library loads its next page near the end. */
	var onMove: (() -> Unit)? = null
	private var job: Job? = null

	val focusedTile: GlassesTile? get() = rows.getOrNull(focus.row)?.tiles?.getOrNull(focus.column)

	fun setRows(rows: List<GlassesRow>, loading: Boolean = false) {
		this.rows = rows
		focus.update(rows.map { it.tiles.size })
		this.loading = loading
		problem = null
		stamp++
	}

	fun failed(message: String) {
		loading = false
		problem = message
	}

	fun moved() {
		stamp++
		onMove?.invoke()
	}

	fun focusOn(row: Int, column: Int) {
		focus.focus(row, column)
		stamp++
	}

	fun run(scope: CoroutineScope, work: suspend () -> Unit) {
		job?.cancel()
		job = scope.launch { work() }
	}

	fun cancel() {
		job?.cancel()
	}
}

@Immutable
internal class GlassesOption(val id: String, val title: String, val section: String? = null, val checked: Boolean = false, val run: () -> Unit)

/** The options of a tile or the player's tracks, a list the thumb steps through. */
@Stable
internal class GlassesPanel(val title: String, val options: List<GlassesOption>, focus: Int = 0, val parent: GlassesPanel? = null) {
	var focus by mutableIntStateOf(focus)
}

internal enum class HudRow { Tracks, Timeline, Sections }

/** The player a [GlassesNavigator] drives: the phone's player, with the way to close it. */
@Stable
internal class GlassesPlayerLink(val model: PlayerModel, val close: () -> Unit) {
	var aspect by mutableStateOf(com.nphil.marquee.ui.player.PlayerAspect.Fit)
	val sleepTimer = com.nphil.marquee.ui.player.SleepTimer()
	var row by mutableStateOf(HudRow.Timeline)
	var track by mutableIntStateOf(0)
	var section by mutableIntStateOf(0)
	var dismissed by mutableStateOf(false)
	var awake by mutableStateOf(true)
	val controlsUp get() = !dismissed && (awake || !model.isPlaying || model.phase != com.nphil.marquee.playback.PlayerPhase.Playing)

	fun hideHud() { dismissed = true; awake = false; row = HudRow.Timeline }
	var visibleOffer: Offer? = null
	var unfocusedOffer by mutableStateOf<Offer?>(null, androidx.compose.runtime.referentialEqualityPolicy())
	var watchFocus by mutableStateOf<Offer?>(null, androidx.compose.runtime.referentialEqualityPolicy())
		private set

	fun chooseWatch(offer: Offer, watch: Boolean) {
		watchFocus = if (watch) offer else null
		if (watch) when (offer) { is Offer.SkipIntro -> model.offers.watchIntro(); else -> model.userActed() }
	}

	fun finishOffer(watch: Boolean) {
		watchFocus = null
		when (model.offers.offer) {
			is Offer.SkipIntro -> if (watch) model.offers.dismissIntro() else model.offers.skipIntro()
			is Offer.SkipCredits -> if (watch) model.offers.watchCredits() else model.offers.skipCredits()
			is Offer.UpNext -> if (watch) model.offers.watchCredits() else { model.userActed(); model.playNext() }
			null -> Unit
		}
	}
}

/**
 * The big screen's pages and what a press of the phone remote does to them. The remote is the only input: every touch
 * becomes a [GlassesInput], and the cue to play in the hand comes back through [onCue].
 */
@Stable
internal class GlassesNavigator(
	private val container: AppContainer,
	private val scope: CoroutineScope,
	private val center: GlassesCenter? = null,
	private val openTitle: (String, String, Boolean, Int?) -> Unit = { key, title, start, _ -> requireNotNull(center).play(key, title, start) },
	private val tvSettings: (() -> Unit)? = null,
	private val tvSubtitleStyle: (() -> Unit)? = null,
	private val tvRequests: (() -> Unit)? = null,
	private val tvAbout: ((PlexItem) -> Unit)? = null,
) {
	val pages = mutableStateListOf<GlassesPage>()
	var panel by mutableStateOf<GlassesPanel?>(null)
		private set
	var toast by mutableStateOf<String?>(null)
		private set

	/** The seconds the playhead is at while the thumb scrubs; null when it does not. */
	var scrubTime by mutableStateOf<Double?>(null)
		private set

	/** Counts presses that show the player's bar. */
	var chromeStamp by mutableIntStateOf(0)
		private set
	var link by mutableStateOf<GlassesPlayerLink?>(null)
	var onCue: ((HapticCue) -> Unit)? = null

	private var scrub: Scrub? = null
	private var swipe = SwipeSteps()
	private var coast: Job? = null
	private var toastJob: Job? = null
	private var menuLibraries: List<LibrarySection> = emptyList()
	private var hiddenShortcuts: Set<String> = emptySet()
	private val watchedChanges = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
	val tvWatchedChanges: Map<String, Boolean> get() = watchedChanges

	val page: GlassesPage? get() = pages.lastOrNull()

	/** What the phone says and TalkBack reads: the focused thing. */
	val focusTitle: String
		get() = when {
			panel != null -> panel!!.options.getOrNull(panel!!.focus)?.title ?: panel!!.title
			link != null -> link!!.model.title
			else -> page?.focusedTile?.title ?: page?.title ?: ""
		}

	val focusDetail: String?
		get() = if (panel != null || link != null) null else page?.focusedTile?.subtitle

	val inPlayer: Boolean get() = link != null
	val isScrubbing: Boolean get() = scrub != null

	/** Home for the signed-in server; nothing changes while it stays the same. */
	fun start() {
		if (pages.isEmpty()) pages += homePage()
	}

	/** The player behind the link went away; whatever the thumb held with it ends. */
	fun leftPlayer() {
		coast?.cancel()
		scrub = null
		scrubTime = null
		panel = null
	}

	fun close() {
		coast?.cancel()
		toastJob?.cancel()
		pages.forEach { it.cancel() }
		pages.clear()
		panel = null
		scrub = null
		scrubTime = null
	}

	// MARK: Input

	fun handle(input: GlassesInput) {
		when (input) {
			GlassesInput.PanBegan -> {
				coast?.cancel()
				swipe = SwipeSteps()
				return
			}
			is GlassesInput.Pan -> {
				for (move in swipe.moved(input.dx, input.dy)) step(move)
				return
			}
			is GlassesInput.PanEnded -> {
				panEnded(input.vx, input.vy)
				return
			}
			else -> coast?.cancel()
		}
		val current = panel
		val player = link
		val cue = when {
			current != null -> inPanel(input, current)
			player != null -> inPlayer(input, player)
			else -> onPage(input)
		}
		if (cue != null) onCue?.invoke(cue)
	}

	/** One step of the focus, from a swipe, a coast or tilt; the cue it played, [HapticCue.Edge] when it met an end. */
	fun step(move: FocusMove): HapticCue? {
		val current = panel
		val player = link
		val cue = when {
			current != null -> inPanel(GlassesInput.Move(move), current)
			player != null -> inPlayer(GlassesInput.Move(move), player)
			else -> onPage(GlassesInput.Move(move))
		}
		if (cue != null) onCue?.invoke(cue)
		return cue
	}

	val tiltEnabled: Boolean get() = center?.prefs?.tilt == true

	/** The remote's accessibility action for Options in a video: the tracks. */
	fun openPlayerOptions() {
		link?.let { openTracks(it.model) }
	}

	/** One whole step moved: whether the focus met an end, which stops a coast. */
	private fun stepMoved(move: FocusMove): Boolean {
		val before = page?.let { it.focus.row to it.focus.column }
		step(move)
		val after = page?.let { it.focus.row to it.focus.column }
		return panel != null || link != null || before != after
	}

	private fun panEnded(vx: Double, vy: Double) {
		val release = swipe.released(vx, vy)
		release.step?.let(::step)
		val move = release.coastMove ?: return
		val distance = if (move.isHorizontal) SwipeSteps.STEP_ACROSS else SwipeSteps.STEP_DOWN
		val times = SwipeSteps.coast(release.coastSpeed, distance)
		coast = scope.launch {
			var last = 0.0
			for (time in times) {
				delay(((time - last) * 1000).toLong())
				last = time
				if (!stepMoved(move)) break
			}
		}
	}

	private fun onPage(input: GlassesInput): HapticCue? {
		val page = page ?: return HapticCue.Edge
		return when (input) {
			is GlassesInput.Move -> {
				if (page.focus.move(input.move) != FocusGrid.Outcome.Moved) return HapticCue.Edge
				page.moved()
				HapticCue.FocusStep
			}
			GlassesInput.Select -> {
				val tile = page.focusedTile ?: return HapticCue.Edge
				tile.select()
				HapticCue.Select
			}
			GlassesInput.Back -> {
				if (pages.size <= 1) return HapticCue.Edge
				pages.removeAt(pages.lastIndex).cancel()
				HapticCue.Back
			}
			GlassesInput.HoldBegan -> {
				openOptions(page.focusedTile)
				HapticCue.Select
			}
			else -> null
		}
	}

	private fun inPanel(input: GlassesInput, panel: GlassesPanel): HapticCue? = when (input) {
		is GlassesInput.Move -> when (input.move) {
			FocusMove.Up -> if (panel.focus > 0) { panel.focus--; HapticCue.FocusStep } else HapticCue.Edge
			FocusMove.Down -> if (panel.focus < panel.options.size - 1) { panel.focus++; HapticCue.FocusStep } else HapticCue.Edge
			else -> HapticCue.Edge
		}
		GlassesInput.Select -> {
			val option = panel.options.getOrNull(panel.focus)
			if (option == null) {
				HapticCue.Edge
			} else {
				this.panel = null
				option.run()
				HapticCue.Select
			}
		}
		GlassesInput.Back -> {
			this.panel = panel.parent
			if (isTelevision && this.panel == null && link != null) wakeTransport()
			HapticCue.Back
		}
		else -> null
	}

	val isTelevision: Boolean get() = tvSettings != null

	private fun inPlayer(input: GlassesInput, player: GlassesPlayerLink): HapticCue? {
		val model = player.model
		val offer = model.offers.offer
		if (isTelevision && scrubTime != null) return televisionScrub(input, model)
		if (isTelevision && offer != null && player.visibleOffer === offer && player.unfocusedOffer !== offer) {
			when (input) {
				GlassesInput.Select -> { player.finishOffer(player.watchFocus === offer); return HapticCue.Select }
				GlassesInput.Back -> { player.finishOffer(watch = true); player.row = HudRow.Tracks; wakeTransport(); return HapticCue.Back }
				is GlassesInput.Move -> {
					if (input.move.isHorizontal) {
						player.chooseWatch(offer, input.move == FocusMove.Left)
						// Choosing between Watch and Skip must not move the buttons into the transport overlay.
						chromeStamp++
					} else {
						player.unfocusedOffer = offer
						player.row = HudRow.Timeline
						model.userActed()
						wakeTransport()
					}
					return HapticCue.FocusStep
				}
				else -> Unit
			}
		}
		if (isTelevision) {
			if (input == GlassesInput.Back) {
				if (player.controlsUp && model.phase !is com.nphil.marquee.playback.PlayerPhase.Failed) player.hideHud() else player.close()
				return HapticCue.Back
			}
			val visible = player.controlsUp
			wakeTransport()
			if (input is GlassesInput.Move) {
				if (!input.move.isHorizontal) {
					if (!visible) player.row = HudRow.Timeline
					else if (input.move == FocusMove.Up && player.row == HudRow.Tracks) player.hideHud()
					else player.row = HudRow.entries[(player.row.ordinal + if (input.move == FocusMove.Up) -1 else 1).coerceIn(0, 2)]
					if (player.row == HudRow.Tracks && offer != null) player.unfocusedOffer = null
					return HapticCue.FocusStep
				}
				val direction = if (input.move == FocusMove.Right) 1 else -1
				when (player.row) {
					HudRow.Tracks -> player.track = (player.track + direction).coerceIn(0, 2)
					HudRow.Sections -> player.section = (player.section + direction).coerceIn(0, if (model.plan?.chapters.isNullOrEmpty()) 0 else 1)
					HudRow.Timeline -> return televisionScrub(input, model)
				}
				return HapticCue.FocusStep
			}
			if (input == GlassesInput.Select && visible && player.row != HudRow.Timeline) {
				openHudControl(player)
				return HapticCue.Select
			}
		}
		return when (input) {
			GlassesInput.Select -> when (model.offers.offer.takeUnless { isTelevision }) {
				is Offer.SkipIntro -> { model.offers.skipIntro(); HapticCue.Select }
				is Offer.SkipCredits -> { model.offers.skipCredits(); HapticCue.Select }
				is Offer.UpNext -> { model.userActed(); model.playNext(); HapticCue.Select }
				null -> {
					model.userActed()
					model.toggle()
					chromeStamp++
					HapticCue.PlayPause
				}
			}
			is GlassesInput.Move -> when {
				input.move.isHorizontal -> {
					model.skip((if (input.move == FocusMove.Right) 1 else -1) * model.skipInterval)
					chromeStamp++
					HapticCue.FocusStep
				}
				input.move == FocusMove.Up -> { chromeStamp++; HapticCue.FocusStep }
				else -> { openTracks(model); HapticCue.Select }
			}
			GlassesInput.Back -> {
				player.close()
				HapticCue.Back
			}
			GlassesInput.HoldBegan -> {
				if (model.durationMs <= 0) return HapticCue.Edge
				model.scrubbing = true
				chromeStamp++
				val begun = Scrub(model.movieMs() / 1000.0, model.durationMs / 1000.0)
				scrub = begun
				scrubTime = begun.time
				HapticCue.Select
			}
			is GlassesInput.Slide -> scrub?.let { cue(it.slid(input.dx, input.width), it) }
			is GlassesInput.Nudge -> scrub?.let { cue(it.nudge(input.seconds), it) }
			GlassesInput.HoldEnded -> {
				val ended = scrub ?: return null
				model.scrubbing = false
				model.seekTo(ended.time)
				scrub = null
				scrubTime = null
				HapticCue.Select
			}
			else -> null
		}
	}

	private fun wakeTransport() {
		link?.let { it.dismissed = false; it.awake = true }
		chromeStamp++
	}

	fun openHudControl(player: GlassesPlayerLink) {
		val model = player.model
		model.userActed()
		wakeTransport()
		when (player.row) {
			HudRow.Tracks -> openTracks(model, when (player.track) { 0 -> "Subtitles"; 1 -> "Audio"; else -> null })
			HudRow.Sections -> if (player.section == 0) model.plan?.item?.let { tvAbout?.invoke(it) }
				else openChapters(model)
			HudRow.Timeline -> Unit
		}
	}

	private fun openChapters(model: PlayerModel, parent: GlassesPanel? = null) {
		val chapters = model.plan?.chapters.orEmpty()
		if (chapters.isEmpty()) return
		val active = com.nphil.marquee.ui.player.chapterAt(chapters, model.movieMs())
		panel = GlassesPanel("Chapters", chapters.mapIndexed { i, c ->
			GlassesOption("chapter.$i", com.nphil.marquee.ui.player.chapterName(c, i), checked = i == active) { model.seekTo(c.start / 1000.0) }
		}, active.coerceAtLeast(0), parent)
	}

	private fun cue(tick: Scrub.Tick, scrub: Scrub): HapticCue? {
		scrubTime = scrub.time
		return when (tick) {
			Scrub.Tick.None -> null
			Scrub.Tick.Detent -> HapticCue.ScrubDetent
			Scrub.Tick.End -> HapticCue.Edge
		}
	}

	// MARK: Options

	private fun openOptions(tile: GlassesTile?) {
		val options = ArrayList<GlassesOption>()
		val item = tile?.item
		val toasts = container.browse.toasts
		if (isTelevision) toasts.current.value?.takeIf { it.undo != null }?.let { shown ->
			options += GlassesOption("undo", "Undo: ${shown.message}") { if (toasts.current.value?.id == shown.id) toasts.undo() }
		}
		if (item != null && item.viewOffset > 0 && (item.type == MediaType.Movie || item.type == MediaType.Episode)) {
			options += GlassesOption("start", "Play from Start") { play(item, fromStart = true) }
		}
		options += hostOption()
		if (isTelevision && item?.type == MediaType.Episode) {
			options += GlassesOption("episode", "Episode Details") { pages += leafPage(item) }
			if (item.grandparentRatingKey != null) options += GlassesOption("show", "Go to Show") { goToShow(item) }
		}
		if (isTelevision && item != null) {
			val series = item.type == MediaType.Show || item.type == MediaType.Season
			if (series || item.type == MediaType.Movie || item.type == MediaType.Episode) {
				val next = !(watchedChanges[item.ratingKey] ?: item.isWatched)
				val state = if (next) "watched" else "unwatched"
				val label = if (series) "Mark all episodes $state" else "Mark as $state"
				options += GlassesOption("watched", label) {
					fun apply() {
						val previous = watchedChanges[item.ratingKey]
						ItemActions(container.browse).setWatched(next, item) { value ->
							if (value != next && previous == null) watchedChanges.remove(item.ratingKey)
							else watchedChanges[item.ratingKey] = value
						}
					}
					if (!series) apply() else panel = GlassesPanel("${item.title}: $label?", listOf(
						GlassesOption("cancel", "Cancel") {}, GlassesOption("confirm", label, run = ::apply)))
				}
			}
			val guid = item.guid
			if ((item.type == MediaType.Movie || item.type == MediaType.Show) && guid != null && Watchlist.catalogueKey(guid) != null && container.browse.source.accountToken != null)
				options += GlassesOption("watchlist", "Watchlist…") { watchlistOptions(item.title, guid) }
		}
		if (pages.size > 1) options += GlassesOption("home", "Home") { goHome() }
		panel = GlassesPanel(tile?.title ?: page?.title ?: "Options", options)
	}

	private fun watchlistOptions(title: String, guid: String) {
		val loading = GlassesPanel("Checking Watchlist…", listOf(GlassesOption("cancel", "Cancel") {}))
		panel = loading
		scope.launch {
			try {
				val listed = container.browse.source.onAccount { token -> onWatchlist(token, guid) } ?: error("Signed out")
				if (panel !== loading) return@launch
				val label = if (listed) "Remove from Watchlist" else "Add to Watchlist"
				panel = GlassesPanel(title, listOf(GlassesOption("change", label) {
					ItemActions(container.browse).setWatchlisted(!listed, guid) { on ->
						if (on) container.browse.changes.showInWatchlist(guid) else container.browse.changes.hideFromWatchlist(guid)
					}
				}, GlassesOption("cancel", "Cancel") {}))
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { if (panel === loading) panel = GlassesPanel("Couldn’t check Watchlist", listOf(GlassesOption("retry", "Retry") { watchlistOptions(title, guid) }, GlassesOption("cancel", "Cancel") {})) }
		}
	}

	/** Subtitles and audio, then the rest of what the player offers. */
	fun openTracks(model: PlayerModel, section: String? = null) {
		val options = ArrayList<GlassesOption>()
		val tracks = model.tracks
		val lists = tracks.lists
		if (tvSettings != null) options += GlassesOption("stats", "Playback Info", "Playback", model.showsStats) {
			model.toggleStats()
			chromeStamp++
		}
		tvSubtitleStyle?.let { options += GlassesOption("subtitle.style", "Subtitle Style", "Subtitles", run = it) }
		options += GlassesOption("subtitle.off", "Off", "Subtitles", tracks.subtitleId == null) { model.pickSubtitle(SubtitlePick.Off) }
		for (track in lists.menuSubtitles) {
			options += GlassesOption("subtitle.${track.id}", track.label, "Subtitles", tracks.subtitleId == track.id) {
				model.pickSubtitle(SubtitlePick.track(track.id))
			}
		}
		if (isTelevision && model.canFindSubtitles && !model.subtitleFinder.busy) {
			if (model.canRecheckSubtitle) options += GlassesOption("subtitle.recheck", "Check Plex again", "Subtitles") { model.findSubtitles(checkOnly = true) }
			options += GlassesOption("subtitle.find", "Find Subtitles", "Subtitles") { model.findSubtitles() }
			if (model.canChooseSubtitle) options += GlassesOption("subtitle.providers", "Choose from Bazarr", "Subtitles") {
				val back = GlassesOption("back", "Back") { openTracks(model, "Subtitles") }
				val loading = GlassesPanel("Searching Bazarr…", listOf(back))
				panel = loading
				scope.launch {
					try {
						val choices = model.subtitleChoices()
						if (panel !== loading) return@launch
						panel = GlassesPanel(if (choices.isEmpty()) "No English subtitles found" else "Choose a subtitle", choices.map { row ->
							GlassesOption(row.key, row.label) { model.findSubtitles(checkOnly = false, choice = row.key) }
						} + back, parent = GlassesPanel("Subtitles", options.filter { it.section == "Subtitles" }))
					} catch (e: CancellationException) { throw e }
					catch (_: Exception) { if (panel === loading) panel = GlassesPanel("Couldn’t search Bazarr", listOf(back)) }
				}
			}
			val external = lists.menuSubtitles.filter { it.isExternal }
			if (model.canRemoveSubtitle && external.isNotEmpty()) options += GlassesOption("subtitle.remove", "Remove a subtitle file…", "Subtitles") {
				val parent = GlassesPanel("Subtitles", options.filter { it.section == "Subtitles" })
				panel = GlassesPanel("Remove a subtitle file", external.map { track ->
					GlassesOption("remove.${track.id}", track.label) {
						val checking = GlassesPanel("Checking file…", listOf(GlassesOption("cancel", "Cancel") { panel = parent }), parent = parent)
						panel = checking
						scope.launch {
							try {
								val ticket = requireNotNull(model.removeExternalSubtitle(track.id))
								if (panel !== checking) return@launch
								panel = GlassesPanel("Delete ${track.label} from the server?", listOf(
									GlassesOption("cancel", "Keep file") { panel = parent },
									GlassesOption("delete", "Delete subtitle file") {
										scope.launch {
											try { model.removeExternalSubtitle(track.id, ticket) }
											catch (e: CancellationException) { throw e }
											catch (_: Exception) { container.browse.toasts.show("Couldn’t remove subtitle file") }
										}
									}), parent = parent)
							} catch (e: CancellationException) { throw e }
							catch (_: Exception) { if (panel === checking) panel = GlassesPanel("File unavailable or removal not allowed", listOf(GlassesOption("back", "Back") { panel = parent }), parent = parent) }
						}
					}
				}, parent = parent)
			}
		}
		for (track in lists.audio) {
			options += GlassesOption("audio.${track.id}", track.label, "Audio", tracks.audioId == track.id) { model.pickAudio(track.id) }
		}
		// The phone's own buttons are out of reach, so the screen offers the steps; the list stays open.
		if (center != null) for ((id, title, up) in listOf(Triple("volume.up", "Volume Up", true), Triple("volume.down", "Volume Down", false))) {
			options += GlassesOption(id, title, "Volume") {
				center.stepVolume(up)
				openTracks(model)
				panel?.let { open -> open.focus = open.options.indexOfFirst { it.id == id }.coerceAtLeast(0) }
			}
		}
		if (isTelevision) {
			fun parent(id: String): GlassesPanel {
				val items = if (section == null) options else options.filter { it.section == section }
				return GlassesPanel(section ?: model.title, items, items.indexOfFirst { it.id == id }.coerceAtLeast(0))
			}
			fun submenu(label: String, choices: () -> List<GlassesOption>) {
				options += GlassesOption(label, label, "Playback") {
					val rows = choices()
					panel = GlassesPanel(label, rows, rows.indexOfFirst { it.checked }.coerceAtLeast(0), parent(label))
				}
			}
			options += GlassesOption("sync.hardware", "Prefer hardware A/V sync", "Playback", model.wantsHardwareSync, model::toggleHardwareSync)
			if (!model.isOffline) submenu("Quality") {
				com.nphil.marquee.core.QualityPreset.all.map { q ->
					GlassesOption("quality.$q", com.nphil.marquee.core.QualityPreset.title(q), checked = model.quality == q) { model.setQuality(q) }
				}
			}
			submenu("Playback Speed") {
				(2..8).map { val v = it / 4f
					GlassesOption("speed.$it", com.nphil.marquee.ui.player.speedTitle(v), checked = model.speed == v) { model.setSpeed(v) }
				}
			}
			link?.let { player -> submenu("Aspect Ratio") {
				com.nphil.marquee.ui.player.PlayerAspect.entries.map { a -> GlassesOption(a.name, a.label, checked = player.aspect == a) { player.aspect = a } }
			} }
			link?.sleepTimer?.let { timer -> submenu("Sleep Timer") {
				buildList {
					add(GlassesOption("off", "Off", checked = !timer.set && !model.sleepAtEnd) { timer.cancel(); model.sleepAtEnd = false })
					add(GlassesOption("end", "Stop at End", checked = model.sleepAtEnd) { timer.cancel(); model.sleepAtEnd = true })
					for (minutes in com.nphil.marquee.ui.player.SLEEP_MINUTES) {
						val active = timer.set && timer.minutes == minutes
						val label = if (active) "$minutes minutes · ${timer.minutesLeft()} min left" else "$minutes minutes"
						add(GlassesOption("minutes.$minutes", label, checked = active) { model.sleepAtEnd = false; timer.start(minutes) })
					}
				}
			} }
			val chapters = model.plan?.chapters.orEmpty()
			if (chapters.isNotEmpty()) {
				options += GlassesOption("chapters", "Chapters", "Playback") { openChapters(model, parent("chapters")) }
			}
		}
		if (model.next != null) options += GlassesOption("next", "Next Episode", "Playback") { model.userActed(); model.playNext() }
		options += hostOption("Playback")
		val shown = if (section == null) options else options.filter { it.section == section }
		panel = GlassesPanel(section ?: model.title, shown, if (section == null) 0 else shown.indexOfFirst { it.checked }.coerceAtLeast(0))
	}

	private fun televisionScrub(input: GlassesInput, model: PlayerModel): HapticCue? {
		val current = scrubTime ?: (model.movieMs() / 1000.0)
		return when (input) {
			is GlassesInput.Move -> {
				if (model.durationMs <= 0) return HapticCue.Edge
				val seconds = when (input.move) {
					FocusMove.Left -> current - model.skipInterval
					FocusMove.Right -> current + model.skipInterval
					FocusMove.Up, FocusMove.Down -> chapterJump(model.plan?.chapters.orEmpty(), (current * 1000).toLong(), input.move == FocusMove.Up)?.div(1000.0)
				} ?: return HapticCue.Edge
				if (scrubTime == null) model.userActed()
				model.scrubbing = true
				val target = seconds.coerceIn(0.0, model.durationMs / 1000.0)
				scrubTime = target
				model.previews?.want(target)
				HapticCue.FocusStep
			}
			GlassesInput.Select, GlassesInput.Back -> {
				model.previews?.settle()
				model.scrubbing = false
				scrubTime = null
				if (input == GlassesInput.Select) model.seekTo(current)
				wakeTransport()
				if (input == GlassesInput.Select) HapticCue.Select else HapticCue.Back
			}
			else -> null
		}
	}

	private fun hostOption(section: String? = null) = if (tvSettings != null) GlassesOption("settings", "Settings", section, run = tvSettings)
		else GlassesOption("phone", "Use the Phone App", section) { center?.leaveRemote() }

	private fun goHome() {
		while (pages.size > 1) pages.removeAt(pages.lastIndex).cancel()
	}

	fun show(text: String) {
		toast = text
		toastJob?.cancel()
		toastJob = scope.launch {
			delay(TOAST_MS)
			toast = null
		}
	}

	// MARK: Opening things

	fun play(item: PlexItem, fromStart: Boolean = false, mediaID: Int? = null) {
		openTitle(item.ratingKey, item.grandparentRatingKey ?: item.ratingKey, fromStart, mediaID)
	}

	private fun open(item: PlexItem) {
		when (item.type) {
			MediaType.Movie -> pages += leafPage(item)
			MediaType.Episode -> if (isTelevision) pages += leafPage(item) else play(item, fromStart = item.rewatchedAt != null)
			MediaType.Show -> pages += showPage(item)
			MediaType.Season -> pages += seasonPage(item)
			MediaType.Collection -> pages += collectionPage(item)
			else -> play(item, fromStart = item.rewatchedAt != null)
		}
	}

	private suspend fun <T> onServer(block: suspend com.nphil.marquee.core.MarqueeCore.(com.nphil.marquee.core.PlexServer, String?) -> T): T =
		container.browse.source.onServer(block)

	private fun profile(): String = container.browse.source.place.value?.profile.orEmpty()

	private fun failure(page: GlassesPage, e: Exception) {
		if (e is CancellationException) throw e
		Diag.warning(DiagCategory.BigScreen, "page.failed", "page" to page.title, "error" to e.javaClass.simpleName)
		page.failed("Could not load this page")
	}

	fun updateTvMenu(hidden: Set<String>) {
		hiddenShortcuts = hidden
		val home = pages.firstOrNull() ?: return
		val menu = home.rows.firstOrNull()?.takeIf { it.id == "menu" } ?: return
		val updated = menuRow()
		if (menu.tiles.size == updated.tiles.size && menu.tiles.indices.all { menu.tiles[it].id == updated.tiles[it].id }) return
		val focused = home.focusedTile?.id.takeIf { home.focus.row == 0 }
		home.setRows(listOf(updated) + home.rows.drop(1), home.loading)
		if (focused != null) updated.tiles.indexOfFirst { it.id == focused }.takeIf { it >= 0 }?.let { home.focusOn(0, it) }
	}

	private fun homePage(): GlassesPage {
		val page = GlassesPage("Home")
		page.run(scope) {
			try {
				val sections = onServer { server, remembered -> sections(server, remembered) }
				val prefs = container.browse.preferences
				val profile = profile()
				combine(prefs.homeLayout(profile), prefs.hiddenLibraries(profile)) { layout, hidden -> layout to hidden }
					.collectLatest { (layout, hidden) ->
						menuLibraries = browsableLibraries(sections, hidden)
						val first = page.rows.isEmpty()
						if (first) page.setRows(listOf(menuRow()), loading = true)
						val dismissed = prefs.rewatchDismissals(profile)
						val load = HomeLoad(layout.shownLibraries.toList(), hidden.toList(), dismissed, SnapshotMode.Live, null, false)
						val reply = onServer { server, remembered -> homeRows(server, remembered, load) }
						val shown = layout.arrange(reply.rows).filter { it.shown && it.hub.items.isNotEmpty() }
						val rowId = page.rows.getOrNull(page.focus.row)?.id
						val tileId = page.focusedTile?.id
						val rows = listOf(menuRow()) + shown.map(::row)
						page.setRows(rows)
						if (first && shown.firstOrNull()?.continueWatching == true) page.focusOn(1, 0)
						else {
							val r = rows.indexOfFirst { it.id == rowId }
							if (r >= 0) page.focusOn(r, rows[r].tiles.indexOfFirst { it.id == tileId }.coerceAtLeast(0))
						}
					}
			} catch (e: Exception) {
				failure(page, e)
			}
		}
		return page
	}

	/** Search and the libraries, as cards above Home's rows. */
	private fun menuRow(): GlassesRow {
		val tiles = ArrayList<GlassesTile>()
		if (TvShortcut.Search.name !in hiddenShortcuts) tiles += GlassesTile("search", "Search", art = GlassesArt.Symbol(R.drawable.ic_search)) { pages += searchPage() }
		for (section in menuLibraries) {
			if ((if (section.type == "movie") TvShortcut.Movies else TvShortcut.Shows).name in hiddenShortcuts) continue
			tiles += GlassesTile("library.${section.key}", section.title, art = GlassesArt.Symbol(if (section.type == "movie") R.drawable.ic_movie else R.drawable.ic_tv)) {
				pages += if (isTelevision) libraryLanding(section) else libraryGrid(section)
			}
		}
		if (isTelevision && TvShortcut.Watchlist.name !in hiddenShortcuts) tiles += GlassesTile("watchlist", "Watchlist") { pages += watchlist() }
		if (tvRequests != null && container.seerr.enabled.value && TvShortcut.Requests.name !in hiddenShortcuts) tiles += GlassesTile("requests", "Requests", select = tvRequests)
		tvSettings?.let { tiles += GlassesTile("settings", "Settings", select = it) }
		return GlassesRow("menu", null, TileShape.Card, tiles)
	}

	private fun row(row: HomeRow): GlassesRow {
		val wide = row.continueWatching
		return GlassesRow(row.id, row.title, if (wide) TileShape.Landscape else TileShape.Poster, row.hub.items.map { tile(it, wide) })
	}

	private fun tile(item: PlexItem, wide: Boolean, inShow: Boolean = false, beforeOpen: (() -> Unit)? = null): GlassesTile {
		val episode = item.type == MediaType.Episode
		val title = if (episode && !inShow) item.grandparentTitle ?: item.title else item.title
		val subtitle = when {
			episode -> if (inShow) runtime(item.duration) else listOfNotNull(episodeNumber(item), item.title).joinToString(" \u00B7 ")
			item.year != null -> item.year.toString()
			else -> null
		}
		val path = when {
			wide -> if (episode) item.thumb ?: item.art else item.art ?: item.thumb
			episode -> item.grandparentThumb ?: item.parentThumb ?: item.thumb
			else -> item.thumb ?: item.parentThumb
		}
		val progress = if (item.duration > 0 && item.viewOffset > 0) (item.viewOffset.toFloat() / item.duration).coerceIn(0f, 1f) else null
		return GlassesTile(item.ratingKey, title, subtitle, GlassesArt.Plex(path), progress, item) { beforeOpen?.invoke(); open(item) }
	}

	private fun hero(item: PlexItem): GlassesHero {
		val parts = ArrayList<String>()
		item.year?.let { parts += it.toString() }
		item.contentRating?.let { parts += it }
		if (item.type == MediaType.Episode) {
			item.grandparentTitle?.let { parts.add(0, it) }
			episodeNumber(item)?.let { parts += it }
		}
		if ((item.type == MediaType.Movie || item.type == MediaType.Episode) && item.duration > 0) parts += runtime(item.duration)
		if (item.type == MediaType.Show) item.childCount?.let { parts += if (it == 1) "1 Season" else "$it Seasons" }
		if (item.type == MediaType.Season) item.parentTitle?.let { parts.add(0, it) }
		return GlassesHero(item.title, parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00B7 "), item.summary.takeIf { it.isNotEmpty() }, item.art ?: item.grandparentArt, item.logoPath)
	}

	private fun playActions(item: PlexItem, label: String? = null, episodeDetails: Boolean = false): GlassesRow {
		val tiles = ArrayList<GlassesTile>()
		if (item.viewOffset > 0) {
			tiles += GlassesTile("resume", "Resume", label, GlassesArt.Symbol(R.drawable.ic_play_arrow), item = item) { play(item) }
			tiles += GlassesTile("start", "Play from Start", art = GlassesArt.Symbol(R.drawable.ic_replay), item = item) { play(item, fromStart = true) }
		} else {
			tiles += GlassesTile("play", "Play", label, GlassesArt.Symbol(R.drawable.ic_play_arrow), item = item) { play(item, fromStart = item.rewatchedAt != null) }
		}
		if (episodeDetails && isTelevision && item.type == MediaType.Episode && item.grandparentRatingKey != null) {
			tiles.add(1, GlassesTile("show", "Go to Show", art = GlassesArt.Symbol(R.drawable.ic_tv)) { goToShow(item) })
		}
		return GlassesRow("actions", null, TileShape.Card, tiles)
	}

	private fun leafPage(item: PlexItem): GlassesPage {
		val page = GlassesPage(item.title)
		page.hero = hero(item)
		page.setRows(listOf(playActions(item, episodeDetails = true)), loading = true)
		page.run(scope) {
			val full = try {
				onServer { server, remembered -> itemOf(server, remembered, item.ratingKey) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				page.setRows(page.rows)
				return@run
			}
			page.hero = hero(full)
			page.setRows(listOf(playActions(full, episodeDetails = true)))
			if (isTelevision) addDetailShelves(page, full)
		}
		return page
	}

	private fun goToShow(episode: PlexItem) {
		val show = PlexItem(episode.grandparentRatingKey ?: return, title = episode.grandparentTitle.orEmpty(), type = MediaType.Show)
		pages += showPage(show, episode)
	}

	private fun showPage(item: PlexItem, fromEpisode: PlexItem? = null): GlassesPage {
		val page = GlassesPage(item.title)
		page.hero = hero(item)
		page.run(scope) {
			try {
				val seasons = onServer { server, remembered -> children(server, remembered, item.ratingKey) }.filter { it.type == MediaType.Season }
				val show = runCatching { onServer { server, remembered -> itemOf(server, remembered, item.ratingKey) } }.getOrDefault(item)
				page.hero = hero(show)
				val rows = ArrayList<GlassesRow>()
				show.onDeck?.let { rows += playActions(it, episodeNumber(it)) }
				rows += GlassesRow("seasons", "Seasons", TileShape.Poster, seasons.map { tile(it, wide = false) })
				page.setRows(rows)
				if (fromEpisode != null) {
					val at = seasons.indexOfFirst { s -> fromEpisode.parentRatingKey?.let { it == s.ratingKey }
						?: (fromEpisode.parentIndex != null && fromEpisode.parentIndex == s.index) }
					if (at >= 0) page.focusOn(rows.lastIndex, at)
				}
				if (isTelevision) addDetailShelves(page, show)
			} catch (e: Exception) {
				failure(page, e)
			}
		}
		return page
	}

	private suspend fun addDetailShelves(page: GlassesPage, title: PlexItem) {
		val rows = page.rows.toMutableList()
		tvAbout?.let { about ->
			val details = arrayListOf(GlassesTile("about", "About", art = GlassesArt.Symbol(R.drawable.ic_info)) { about(title) })
			val files = title.media.filter { it.id > 0 && it.parts.isNotEmpty() }
			if (files.size > 1) details += GlassesTile("versions", "Play a Version", art = GlassesArt.Symbol(R.drawable.ic_video_library)) {
				panel = GlassesPanel("Play a Version", files.map { file ->
					GlassesOption("file.${file.id}", file.versionLabel) { play(title, mediaID = file.id) }
				})
			}
			val guid = title.guid
			if (guid != null && Watchlist.catalogueKey(guid) != null && (title.type == MediaType.Movie || title.type == MediaType.Show)) {
				details += GlassesTile("watchlist", "Watchlist…") { watchlistOptions(title.title, guid) }
				details += GlassesTile("copies", "Library Copies") {
					val copiesPage = GlassesPage("Library Copies")
					pages += copiesPage
					copiesPage.run(scope) {
						try {
							val copies = onServer { s, r -> copiesOf(s, r, guid) }.ifEmpty { listOf(title) }
							copiesPage.setRows(gridRows(copies.map { copy ->
								GlassesTile(copy.ratingKey, copy.librarySectionTitle ?: copy.title,
									listOfNotNull(copy.title, copy.media.firstOrNull()?.versionLabel).joinToString(" · "), GlassesArt.Plex(copy.thumb)) { open(copy) }
							}, TileShape.Poster))
						} catch (e: Exception) { failure(copiesPage, e) }
					}
				}
			}
			rows += GlassesRow("more", "More", TileShape.Card, details)
			page.setRows(rows.toList())
		}
		val cast = title.roles.distinctBy { it.id ?: it.name }.map { role ->
			GlassesTile("actor.${role.id ?: role.name}", role.name, role.character, GlassesArt.Plex(role.thumb)) { openPerson(role.name, role.id, "actor") }
		}
		if (cast.isNotEmpty()) {
			rows += GlassesRow("cast", "Cast", TileShape.Poster, cast)
			page.setRows(rows.toList())
		}
		val crew = buildList {
			for ((role, names) in listOf("director" to title.directors, "writer" to title.writers)) {
				for (name in names.distinct().filter(String::isNotBlank)) add(GlassesTile("$role.$name", name, if (role == "director") "Director" else "Writer") { openPerson(name, null, role) })
			}
		}
		if (crew.isNotEmpty()) {
			rows += GlassesRow("crew", "Crew", TileShape.Card, crew)
			page.setRows(rows.toList())
		}
		val bonus = optionalShelf("extras") { onServer { s, r -> extras(s, r, title.ratingKey) } }
		if (bonus.isNotEmpty()) {
			page.trailer = trailerOf(bonus, title.primaryExtraKey)
			rows += GlassesRow("extras", "Trailers & Extras", TileShape.Landscape, bonus.map { tile(it, true) })
			page.setRows(rows.toList())
		}
		val similar = optionalShelf("related") { onServer { s, r -> related(s, r, title.ratingKey) } }
		val relatedRows = similar.mapNotNull(::resultRow)
		if (relatedRows.isNotEmpty()) page.setRows(rows + relatedRows)
	}

	private suspend fun <T> optionalShelf(name: String, load: suspend () -> List<T>): List<T> = try {
		load()
	} catch (e: CancellationException) { throw e } catch (e: Exception) {
		Diag.warning(DiagCategory.BigScreen, "shelf.failed", "shelf" to name, "error" to e.javaClass.simpleName)
		emptyList()
	}

	private fun seasonPage(item: PlexItem): GlassesPage {
		val page = GlassesPage(item.parentTitle ?: item.title)
		page.hero = hero(item)
		page.run(scope) {
			try {
				val episodes = onServer { server, remembered -> children(server, remembered, item.ratingKey) }
				val tiles = episodes.map { episode ->
					val base = tile(episode, wide = true, inShow = true)
					GlassesTile(base.id, listOfNotNull(episodeNumber(episode), episode.title).joinToString(" \u00B7 "), base.subtitle, base.art, base.progress, base.item, select = base.select)
				}
				page.setRows(listOf(GlassesRow("episodes", "Episodes", TileShape.Landscape, tiles)))
				val next = episodes.indexOfFirst { it.viewCount == 0 }
				if (next >= 0) page.focusOn(0, next)
			} catch (e: Exception) {
				failure(page, e)
			}
		}
		return page
	}

	private fun collectionPage(item: PlexItem): GlassesPage {
		val page = GlassesPage(item.title, FocusGrid.Style.Grid)
		page.run(scope) {
			try {
				val items = onServer { server, remembered -> children(server, remembered, item.ratingKey) }
				page.setRows(gridRows(items.map { tile(it, wide = false) }, TileShape.Poster))
			} catch (e: Exception) {
				failure(page, e)
			}
		}
		return page
	}

	private fun gridRows(tiles: List<GlassesTile>, shape: TileShape): List<GlassesRow> =
		(center?.columns ?: 6).let { columns -> tiles.chunked(columns).mapIndexed { index, slice -> GlassesRow("grid.${index * columns}", null, shape, slice) } }

	private fun libraryLettersPage(section: LibrarySection, query: SectionQuery): GlassesPage {
		val page = GlassesPage("Jump to Letter")
		page.run(scope) {
			try {
				val letters = onServer { s, r -> libraryLetters(s, r, section.key, query) }
				page.setRows(gridRows(letters.filter { it.count > 0 && it.offset >= 0 }.map { letter ->
					GlassesTile("letter.${letter.offset}", letter.character, letter.count.toString()) {
						pages += pagedGrid("${section.title} · ${letter.character}", { at ->
							val size = minOf(LIBRARY_PAGE, letter.count - at)
							val batch = onServer { s, r -> libraryItems(s, r, section.key, query, letter.offset + at, size) }
							Page(if (batch.items.size <= size) batch.items else batch.items.subList(0, size), minOf(letter.count, (batch.total - letter.offset).coerceAtLeast(0)))
						}, card = ::libraryTile, sort = query.sort)
					}
				}, TileShape.Card))
			} catch (e: Exception) { failure(page, e) }
		}
		return page
	}

	private fun libraryLanding(section: LibrarySection): GlassesPage {
		val page = GlassesPage(section.title)
		val memory = StoredLibraryMemory(container.browse.preferences, profile())
		page.run(scope) {
			try {
				var view = decodeLibraryView(memory.view(section.key))
				var genres: List<PlexDirectory> = emptyList()
				var recommended: List<GlassesRow> = emptyList()
				fun render() {
					val order = view.sort.orders?.let { if (view.reversed) it.reversed else it.first }
					val orderLabel = listOfNotNull(view.sort.title, order).joinToString(" · ")
					val query = view.tvQuery(section.itemType)
					fun apply(next: LibraryOptions) {
						if (next == view) return
						view = next
						container.scope.launch { memory.keepView(section.key, encodeLibraryView(next)) }
						render()
					}
					val controls = buildList {
						val browse = if (section.itemType == MediaType.Show) "Browse ${view.seriesListing.title}" else "Browse Titles"
						add(GlassesTile("titles", browse, listOfNotNull(view.genre?.title, "Unwatched".takeIf { view.unwatchedOnly }, orderLabel).joinToString(" · ")) { pages += libraryGrid(section, query) })
						if (section.itemType == MediaType.Show) add(GlassesTile("type", "List", view.seriesListing.title) {
							val choices = com.nphil.marquee.browse.SeriesListing.entries.map { kind ->
								GlassesOption(kind.name, kind.title, checked = view.seriesListing == kind) { apply(view.listing(kind)) }
							}
							panel = GlassesPanel("List", choices, view.seriesListing.ordinal)
						})
						add(GlassesTile("unwatched", "Unwatched Only", if (view.unwatchedOnly) "On" else "Off") { apply(view.copy(unwatchedOnly = !view.unwatchedOnly)) })
						if (query.type == section.itemType && (genres.isNotEmpty() || view.genre != null)) add(GlassesTile("genre", "Genre", view.genre?.title ?: "All Genres") {
							val choices = listOf(GlassesOption("all", "All Genres", checked = view.genre == null) { apply(view.copy(genre = null)) }) +
								genres.map { g -> GlassesOption(g.key, g.title, checked = g.key == view.genre?.key) { apply(view.copy(genre = g)) } }
							panel = GlassesPanel("Genre", choices, choices.indexOfFirst { it.checked }.coerceAtLeast(0))
						})
						add(GlassesTile("sort", "Sort by", orderLabel) {
							val choices = SectionQuery.Sort.entries.flatMap { sort ->
								val orders = sort.orders
								(if (orders == null) listOf(false) else listOf(false, true)).map { reverse ->
									val direction = if (reverse) orders?.reversed else orders?.first
									GlassesOption("${sort.raw}.$reverse", listOfNotNull(sort.title, direction).joinToString(" · "), checked = sort == view.sort && reverse == view.reversed) {
										apply(view.copy(sort = sort, reversed = reverse))
									}
								}
							}
							panel = GlassesPanel("Sort by", choices, choices.indexOfFirst { it.checked }.coerceAtLeast(0))
						})
						if (query.isAlphabetical) add(GlassesTile("az", "Jump to Letter") { pages += libraryLettersPage(section, query) })
						add(GlassesTile("collections", "Collections") { pages += pagedGrid("Collections", { at ->
							onServer { s, r -> libraryCollections(s, r, section.key, at, LIBRARY_PAGE) }
						}) })
						if (view.isFiltered) add(GlassesTile("clear", "Clear Filters") { apply(view.cleared()) })
					}
					val focused = page.focusedTile?.id.takeIf { page.focus.row == 0 }
					page.setRows(listOf(GlassesRow("library.views", "Browse Library", TileShape.Card, controls)) + recommended)
					if (focused != null) page.focusOn(0, controls.indexOfFirst { it.id == focused }.coerceAtLeast(0))
				}
				render()
				genres = optionalShelf("genres") { onServer { s, r -> libraryGenres(s, r, section.key) } }
				render()
				recommended = optionalShelf("recommended") { onServer { s, r -> libraryRecommended(s, r, section.key) } }.mapNotNull(::resultRow)
				render()
			} catch (e: Exception) { failure(page, e) }
		}
		return page
	}

	private fun watchlist(): GlassesPage {
		val page = GlassesPage("Watchlist", FocusGrid.Style.Grid)
		page.withModel<WatchlistModel>({ WatchlistModel(container.browse) }) { model ->
			val choices = kotlinx.coroutines.flow.MutableStateFlow(TvWatchlistOrder())
			val retryRelease = kotlinx.coroutines.flow.MutableStateFlow(0)
			val revision = container.browse.changes.state.map { it.watchlistRevision }.distinctUntilChanged()
			var membership: Pair<Int, Set<String>>? = null
			page.onMove = { if (!choices.value.needsWholeList) page.focusedTile?.id?.let(model::nearEnd) }
			combine(choices, revision, retryRelease) { order, rev, _ -> order to rev }.collectLatest { (order, rev) ->
				val tools = GlassesRow("watchlist.filters", "Sort & Filter", TileShape.Card, listOfNotNull(
					GlassesTile("kind", order.typeLabel) {
						panel = GlassesPanel("Show", listOf(null, MediaType.Movie, MediaType.Show).map { type ->
							val next = order.copy(type = type)
							GlassesOption(next.typeLabel, next.typeLabel, checked = type == order.type) { choices.value = next }
						})
					},
					GlassesTile("order", order.sort.label, order.direction) {
						panel = GlassesPanel("Sort by", TvWatchlistSort.entries.flatMap { sort ->
							listOf(false, true).map { reversed ->
								val next = order.copy(sort = sort, reversed = reversed)
								GlassesOption("${sort.name}.$reversed", "${sort.label} · ${next.direction}", checked = order == next) { choices.value = next }
							}
						})
					},
					if (tvSettings == null) null else GlassesTile("release", "Released only", if (order.releasedOnly) "On" else "Off") {
						choices.value = order.copy(releasedOnly = !order.releasedOnly)
					},
				))
				if (!order.releasedOnly) membership = null
				else if (membership?.first != rev) {
					page.setRows(listOf(tools), loading = true)
					try {
						val guids = container.browse.source.onAccount { token -> releasedWatchlist(token) } ?: error("Signed out")
						membership = rev to guids
					} catch (e: CancellationException) { throw e }
					catch (_: Exception) {
						val retry = GlassesTile("retry-release", "Retry") { retryRelease.value++ }
						page.setRows(listOf(tools, GlassesRow("release-error", "Couldn’t load released titles", TileShape.Card, listOf(retry))))
						return@collectLatest
					}
				}
				val released = membership?.second.orEmpty()
				model.state.collect { state ->
					val rows = gridRows(order.apply(state.items, released).map(::watchlistCard), TileShape.Poster)
					val retry = if (state.failure == null) emptyList() else listOf(GlassesRow("retry", "Couldn’t load the full Watchlist", TileShape.Card, listOf(GlassesTile("retry", "Retry") { model.load() })))
					page.setRows(listOf(tools) + retry + rows, state.isLoading)
					if (order.needsWholeList && !state.isLoading && state.failure == null) model.fillNextPage()
				}
			}
		}
		return page
	}

	private fun watchlistCard(item: PlexItem) =
		GlassesTile(item.ratingKey, item.title, item.year?.toString(), GlassesArt.Plex(item.thumb), captionItem = item) {
			// Watchlist keys belong to the catalogue, not the local Plex server.
			val page = GlassesPage(item.title)
			page.hero = hero(item)
			pages += page
			page.run(scope) {
				try {
					val guid = item.guid
					val copies = if (guid == null) emptyList() else onServer { s, r -> copiesOf(s, r, guid) }
					val rows = arrayListOf<GlassesRow>()
					if (copies.isNotEmpty()) rows += GlassesRow("copies", "In This Library", TileShape.Poster, copies.map { tile(it, false) })
					val actions = buildList {
						if (guid != null && Watchlist.catalogueKey(guid) != null) add(GlassesTile("watchlist", "Manage Watchlist") { watchlistOptions(item.title, guid) })
						if (copies.isEmpty() && tvRequests != null && container.seerr.enabled.value) add(GlassesTile("requests", "Open Requests", select = tvRequests))
					}
					if (actions.isNotEmpty()) rows += GlassesRow("actions", if (copies.isEmpty()) "Not in this server’s libraries" else "More", TileShape.Card, actions)
					page.setRows(rows)
					if (rows.isEmpty()) page.failed("Not in this server’s libraries")
				} catch (e: Exception) { failure(page, e) }
			}
		}

	private fun libraryTile(item: PlexItem) = tile(item, wide = false, inShow = item.type == MediaType.Episode)

	private fun libraryGrid(section: LibrarySection, query: SectionQuery = SectionQuery(type = section.itemType)): GlassesPage =
		pagedGrid(section.title, { at -> onServer { s, r -> libraryItems(s, r, section.key, query, at, LIBRARY_PAGE) } }, card = ::libraryTile, sort = query.sort)

	private fun pagedGrid(title: String, fetch: suspend (Int) -> Page<PlexItem>, card: (PlexItem) -> GlassesTile = { tile(it, false) }, sort: SectionQuery.Sort? = null): GlassesPage {
		val page = GlassesPage(title, FocusGrid.Style.Grid, posterSort = sort)
		val items = ArrayList<GlassesTile>()
		var total = Int.MAX_VALUE
		var loading = false
		fun load() {
			if (loading || items.size >= total) return
			loading = true
			page.run(scope) {
				try {
					val next = fetch(items.size)
					items += next.items.map(card)
					total = if (next.items.isEmpty()) items.size else next.total
					page.setRows(gridRows(items, TileShape.Poster))
				} catch (e: Exception) {
					if (items.isEmpty()) failure(page, e) else if (e is CancellationException) throw e
				} finally {
					loading = false
				}
			}
		}
		page.onMove = { if (page.focus.row >= page.rows.size - 2) load() }
		load()
		return page
	}

	private inline fun <reified M : ViewModel> GlassesPage.withModel(noinline create: () -> M, crossinline display: suspend (M) -> Unit) {
		run(scope) {
			val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
			try {
				val model = ViewModelProvider.create(owner, viewModelFactory { initializer { create() } })[M::class]
				display(model)
			} finally { owner.viewModelStore.clear() }
		}
	}

	private fun openPerson(name: String, localId: String?, role: String? = null) {
		val person = GlassesPage(name, FocusGrid.Style.Grid)
		pages += person
		person.run(scope) {
			try {
				val credits = onServer { server, address ->
					val id = localId ?: role?.let { searchHubs(server, address, name, 100).uniqueCredit(name, it) }
					id?.let { titlesOf(server, address, it) }
				}
				if (credits.isNullOrEmpty()) person.failed(if (credits == null) "No unique credit for $name on this server" else "No library titles for $name")
				else person.setRows(gridRows(credits.map(::libraryTile), TileShape.Poster))
			} catch (e: Exception) { failure(person, e) }
		}
	}

	private fun searchPage(): GlassesPage {
		val page = GlassesPage("Search")
		val place = container.browse.source.place.value ?: return page.apply { failed("Select a server to search") }
		page.withModel<SearchModel>({ SearchModel(container.browse, place) }) { model ->
			val peopleOnly = kotlinx.coroutines.flow.MutableStateFlow(false)
			fun type(text: String) {
				page.query = text
				model.type(if (isTelevision) GlassesKeyboard.tvSearchTerm(text) else text)
			}
			val keyboard = GlassesKeyboard.rows.mapIndexed { index, row ->
				GlassesRow("keys.$index", null, TileShape.Key, row.map { key ->
					GlassesTile("key.${key.label}", key.label) { type(GlassesKeyboard.type(key, page.query)) }
				})
			}
			combine(model.ui, peopleOnly) { ui, people -> ui to people }.collect { (ui, people) ->
				val rows = keyboard.toMutableList()
				if (ui.query.isBlank() && ui.recents.isNotEmpty()) {
					rows += GlassesRow("recent", "Recent Searches", TileShape.Card,
						ui.recents.map { text -> GlassesTile("recent.$text", text) { type(text) } } + GlassesTile("clear", "Clear Recent Searches") { model.clearRecents() })
				} else if (ui.query.isNotBlank()) {
					rows += GlassesRow("scope", "Search in", TileShape.Card, listOf(GlassesTile("scope", if (people) "People" else ui.scope.label) {
						val choices = buildList {
							for (s in SearchScope.entries) add(GlassesOption(s.name, s.label, checked = !people && s == ui.scope) { peopleOnly.value = false; model.choose(s) })
							if (isTelevision) add(GlassesOption("people", "People", checked = people) { peopleOnly.value = true })
						}
						panel = GlassesPanel("Search in", choices, choices.indexOfFirst { it.checked }.coerceAtLeast(0))
					}))
					if (ui.results.failure != null) rows += GlassesRow("retry", "Couldn’t search this server", TileShape.Card, listOf(GlassesTile("retry", "Retry", select = model::retry)))
					else {
						val before = rows.size
						if (!people) ui.groups.mapNotNullTo(rows) { hub ->
							val items = hub.items.filter { it.type in SEARCHABLE }
							if (items.isEmpty()) null else GlassesRow("hub.${hub.identifier}", hub.title, TileShape.Poster, items.map { item ->
								tile(item, false, beforeOpen = { model.keep(ui.query) })
							})
						}
						if (isTelevision && (people || ui.scope == SearchScope.All)) {
							val persons = LinkedHashMap<String, GlassesTile>()
							for (entry in ui.results.value.orEmpty().peopleIn()) {
								val id = entry.id ?: continue
								val name = entry.personName ?: continue
								persons.getOrPut(id) { GlassesTile("person.$id", name, art = GlassesArt.Plex(entry.thumb)) { model.keep(ui.query); openPerson(name, id) } }
							}
							if (persons.isNotEmpty()) rows += GlassesRow("people", "People", TileShape.Poster, persons.values.toList())
						}
						if (rows.size == before && ui.results.value != null && !ui.results.isLoading) rows += GlassesRow("empty", if (people) "No matching people" else "No matches", TileShape.Card, listOf(GlassesTile("clear", "Clear Search") { type("") }))
					}
				}
				page.setRows(rows)
			}
		}
		return page
	}


	private fun resultRow(hub: PlexHub): GlassesRow? {
		val items = hub.items.filter { it.type in SEARCHABLE }
		if (items.isEmpty()) return null
		return GlassesRow("hub.${hub.identifier}", hub.title, TileShape.Poster, items.map { tile(it, wide = false) })
	}

	private companion object {
		const val LIBRARY_PAGE = 60
		const val TOAST_MS = 2_500L
		val SEARCHABLE = setOf(MediaType.Movie, MediaType.Show, MediaType.Episode, MediaType.Season, MediaType.Collection)
	}
}

/** "S2 \u00B7 E5" for an episode with both numbers; null when it lacks them. */
internal fun episodeNumber(item: PlexItem): String? {
	val season = item.parentIndex ?: return null
	val episode = item.index ?: return null
	return "S$season \u00B7 E$episode"
}

/** "1h 42m", or "42m", of a duration in milliseconds. */
internal fun runtime(ms: Int): String {
	val minutes = abs(ms) / 60_000
	return if (minutes >= 60) String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60) else "${minutes}m"
}

internal fun chapterJump(chapters: List<com.nphil.marquee.core.Chapter>, ms: Long, forward: Boolean): Int? {
	if (chapters.size < 2) return null
	val at = chapters.indexOfLast { it.start <= ms + 250 }
	val target = when {
		forward -> at + 1
		at < 0 -> return null
		ms - chapters[at].start > 3_000 -> at
		else -> (at - 1).coerceAtLeast(0)
	}
	return chapters.getOrNull(target)?.start
}
