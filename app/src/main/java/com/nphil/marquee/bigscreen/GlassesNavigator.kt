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
import com.nphil.marquee.core.searchHubs
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.Offer
import com.nphil.marquee.playback.PlayerModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
	val select: () -> Unit,
)

@Immutable
internal class GlassesRow(val id: String, val title: String?, val shape: TileShape, val tiles: List<GlassesTile>)

@Immutable
internal class GlassesHero(val title: String, val meta: String?, val overview: String?, val backdrop: String?)

/** One page of the big screen: rows of tiles and where the focus sits among them. */
@Stable
internal class GlassesPage(val title: String, style: FocusGrid.Style = FocusGrid.Style.Shelves) {
	var hero by mutableStateOf<GlassesHero?>(null)
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
internal class GlassesPanel(val title: String, val options: List<GlassesOption>, focus: Int = 0) {
	var focus by mutableIntStateOf(focus)
}

/** The player a [GlassesNavigator] drives: the phone's player, with the way to close it. */
@Stable
internal class GlassesPlayerLink(val model: PlayerModel, val close: () -> Unit)

/**
 * The big screen's pages and what a press of the phone remote does to them. The remote is the only input: every touch
 * becomes a [GlassesInput], and the cue to play in the hand comes back through [onCue].
 */
@Stable
internal class GlassesNavigator(
	private val container: AppContainer,
	private val scope: CoroutineScope,
	private val center: GlassesCenter? = null,
	private val openTitle: (String, String, Boolean) -> Unit = requireNotNull(center)::play,
	private val tvSettings: (() -> Unit)? = null,
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
	private val sectionsCache = ArrayList<LibrarySection>()

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
			this.panel = null
			HapticCue.Back
		}
		else -> null
	}

	private fun inPlayer(input: GlassesInput, player: GlassesPlayerLink): HapticCue? {
		val model = player.model
		return when (input) {
			GlassesInput.Select -> when (model.offers.offer) {
				is Offer.SkipIntro -> { model.offers.skipIntro(); HapticCue.Select }
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
		if (item != null && item.viewOffset > 0 && (item.type == MediaType.Movie || item.type == MediaType.Episode)) {
			options += GlassesOption("start", "Play from Start") { play(item, fromStart = true) }
		}
		options += hostOption()
		if (pages.size > 1) options += GlassesOption("home", "Home") { goHome() }
		panel = GlassesPanel(tile?.title ?: page?.title ?: "Options", options)
	}

	/** Subtitles and audio, then the rest of what the player offers. */
	fun openTracks(model: PlayerModel) {
		val options = ArrayList<GlassesOption>()
		val tracks = model.tracks
		val lists = tracks.lists
		options += GlassesOption("subtitle.off", "Off", "Subtitles", tracks.subtitleId == null) { model.pickSubtitle(SubtitlePick.Off) }
		for (track in lists.menuSubtitles) {
			options += GlassesOption("subtitle.${track.id}", track.label, "Subtitles", tracks.subtitleId == track.id) {
				model.pickSubtitle(SubtitlePick.track(track.id))
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
		if (model.next != null) options += GlassesOption("next", "Next Episode", "Playback") { model.userActed(); model.playNext() }
		options += hostOption("Playback")
		panel = GlassesPanel(model.title, options)
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

	fun play(item: PlexItem, fromStart: Boolean = false) {
		openTitle(item.ratingKey, item.grandparentRatingKey ?: item.ratingKey, fromStart)
	}

	private fun open(item: PlexItem) {
		when (item.type) {
			MediaType.Movie -> pages += moviePage(item)
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

	private fun homePage(): GlassesPage {
		val page = GlassesPage("Home")
		page.run(scope) {
			try {
				val sections = onServer { server, remembered -> sections(server, remembered) }
				sectionsCache.clear()
				sectionsCache += sections
				val hidden = container.browse.preferences.hiddenLibraries(profile()).first()
				val menu = menuRow(sections, hidden)
				page.setRows(listOf(menu), loading = true)
				val layout = container.browse.preferences.homeLayout(profile()).first()
				val dismissed = container.browse.preferences.rewatchDismissals(profile())
				val load = HomeLoad(layout.shownLibraries.toList(), hidden.toList(), dismissed, SnapshotMode.Live, null, false)
				val reply = onServer { server, remembered -> homeRows(server, remembered, load) }
				val shown = layout.arrange(reply.rows).filter { it.shown && it.hub.items.isNotEmpty() }
				page.setRows(listOf(menu) + shown.map(::row))
				// Continue Watching is what most visits are for.
				if (shown.firstOrNull()?.continueWatching == true) page.focusOn(1, 0)
			} catch (e: Exception) {
				failure(page, e)
			}
		}
		return page
	}

	/** Search and the libraries, as cards above Home's rows. */
	private fun menuRow(sections: List<LibrarySection>, hidden: Set<String>): GlassesRow {
		val tiles = ArrayList<GlassesTile>()
		tiles += GlassesTile("search", "Search", art = GlassesArt.Symbol(R.drawable.ic_search)) { pages += searchPage() }
		for (section in sections) {
			if (!section.isBrowsable || section.libraryId in hidden) continue
			tiles += GlassesTile("library.${section.key}", section.title, art = GlassesArt.Symbol(if (section.type == "movie") R.drawable.ic_movie else R.drawable.ic_tv)) {
				pages += libraryGrid(section)
			}
		}
		tvSettings?.let { tiles += GlassesTile("settings", "Settings", select = it) }
		return GlassesRow("menu", null, TileShape.Card, tiles)
	}

	private fun row(row: HomeRow): GlassesRow {
		val wide = row.continueWatching
		return GlassesRow(row.id, row.title, if (wide) TileShape.Landscape else TileShape.Poster, row.hub.items.map { tile(it, wide) })
	}

	private fun tile(item: PlexItem, wide: Boolean, inShow: Boolean = false): GlassesTile {
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
		return GlassesTile(item.ratingKey, title, subtitle, GlassesArt.Plex(path), progress, item) { open(item) }
	}

	private fun hero(item: PlexItem): GlassesHero {
		val parts = ArrayList<String>()
		item.year?.let { parts += it.toString() }
		item.contentRating?.let { parts += it }
		if (item.type == MediaType.Movie && item.duration > 0) parts += runtime(item.duration)
		if (item.type == MediaType.Show) item.childCount?.let { parts += if (it == 1) "1 Season" else "$it Seasons" }
		if (item.type == MediaType.Season) item.parentTitle?.let { parts.add(0, it) }
		return GlassesHero(item.title, parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00B7 "), item.summary.takeIf { it.isNotEmpty() }, item.art ?: item.grandparentArt)
	}

	private fun playActions(item: PlexItem, label: String? = null): GlassesRow {
		val tiles = ArrayList<GlassesTile>()
		if (item.viewOffset > 0) {
			tiles += GlassesTile("resume", "Resume", label, GlassesArt.Symbol(R.drawable.ic_play_arrow), item = item) { play(item) }
			tiles += GlassesTile("start", "Play from Start", art = GlassesArt.Symbol(R.drawable.ic_replay), item = item) { play(item, fromStart = true) }
		} else {
			tiles += GlassesTile("play", "Play", label, GlassesArt.Symbol(R.drawable.ic_play_arrow), item = item) { play(item, fromStart = item.rewatchedAt != null) }
		}
		return GlassesRow("actions", null, TileShape.Card, tiles)
	}

	private fun moviePage(item: PlexItem): GlassesPage {
		val page = GlassesPage(item.title)
		page.hero = hero(item)
		page.setRows(listOf(playActions(item)), loading = true)
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
			page.setRows(listOf(playActions(full)))
		}
		return page
	}

	private fun showPage(item: PlexItem): GlassesPage {
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
			} catch (e: Exception) {
				failure(page, e)
			}
		}
		return page
	}

	private fun seasonPage(item: PlexItem): GlassesPage {
		val page = GlassesPage(item.parentTitle ?: item.title)
		page.hero = hero(item)
		page.run(scope) {
			try {
				val episodes = onServer { server, remembered -> children(server, remembered, item.ratingKey) }
				val tiles = episodes.map { episode ->
					val base = tile(episode, wide = true, inShow = true)
					GlassesTile(base.id, listOfNotNull(episodeNumber(episode), episode.title).joinToString(" \u00B7 "), base.subtitle, base.art, base.progress, base.item, base.select)
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

	/** A library's titles in A\u2013Z order, a page at a time as the focus nears the end. */
	private fun libraryGrid(section: LibrarySection): GlassesPage {
		val page = GlassesPage(section.title, FocusGrid.Style.Grid)
		val items = ArrayList<PlexItem>()
		var total = Int.MAX_VALUE
		var loading = false
		fun load() {
			if (loading || items.size >= total) return
			loading = true
			page.run(scope) {
				try {
					val next = onServer { server, remembered ->
						libraryItems(server, remembered, section.key, SectionQuery(type = section.itemType), items.size, LIBRARY_PAGE)
					}
					items += next.items
					total = next.total
					page.setRows(gridRows(items.map { tile(it, wide = false) }, TileShape.Poster))
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

	/** The letter board, then what the libraries found for what was typed. */
	private fun searchPage(): GlassesPage {
		val page = GlassesPage("Search")
		page.setRows(keyboardRows(page, emptyList()))
		return page
	}

	private fun keyboardRows(page: GlassesPage, results: List<GlassesRow>): List<GlassesRow> {
		val keys = GlassesKeyboard.rows.mapIndexed { index, row ->
			GlassesRow("keys.$index", null, TileShape.Key, row.map { key ->
				GlassesTile("key.${key.label}", key.label) {
					page.query = GlassesKeyboard.type(key, page.query)
					search(page)
				}
			})
		}
		return keys + results
	}

	private fun search(page: GlassesPage) {
		val query = page.query.trim()
		page.run(scope) {
			if (query.isEmpty()) {
				page.setRows(keyboardRows(page, emptyList()))
				return@run
			}
			delay(SEARCH_DELAY_MS)
			try {
				val hubs = onServer { server, remembered -> searchHubs(server, remembered, query, SEARCH_LIMIT) }
				page.setRows(keyboardRows(page, hubs.mapNotNull(::resultRow)))
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				page.setRows(keyboardRows(page, emptyList()))
				show("Search failed")
			}
		}
	}

	private fun resultRow(hub: PlexHub): GlassesRow? {
		val items = hub.items.filter { it.type in SEARCHABLE }
		if (items.isEmpty()) return null
		return GlassesRow("hub.${hub.identifier}", hub.title, TileShape.Poster, items.map { tile(it, wide = false) })
	}

	private companion object {
		const val LIBRARY_PAGE = 60
		const val SEARCH_DELAY_MS = 500L
		const val SEARCH_LIMIT = 12
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
