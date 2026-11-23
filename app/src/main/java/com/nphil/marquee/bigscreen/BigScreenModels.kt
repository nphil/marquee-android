package com.nphil.marquee.bigscreen

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What an external display is. Android reports a pixel size and a refresh rate, never whether the picture goes to
 * birdbath glasses or a television, so the person says, once per display.
 */
@Serializable
enum class DisplayKind(val title: String, val wire: String) {
	@SerialName("glasses") Glasses("XR Glasses", "glasses"),
	@SerialName("tv") Tv("TV", "tv"),
}

/** What a connected external display is used for. The first connection asks, and the answer stays until it is changed in Settings. */
@Serializable
enum class BigScreenUse(val title: String, val wire: String) {
	@SerialName("ask") Ask("Ask", "ask"),

	/** Marquee draws its own big-screen UI and the phone becomes the remote. The wire name is the iPhone's, which stored it when only glasses existed. */
	@SerialName("glasses") Marquee("Use Marquee\u2019s TV Screen", "glasses"),

	/** The display shows what the system puts on it, as without the plugin. */
	@SerialName("mirror") Mirror("Mirror", "mirror"),
}

/** A connected display as the system describes it. The key tells displays apart between connections. */
@Immutable
data class DisplayIdentity(val pixelWidth: Int, val pixelHeight: Int, val refreshRate: Int, val name: String? = null) {
	val key: String get() = "${pixelWidth}x$pixelHeight@$refreshRate"

	/** "3840 × 2160", or null when the display reported no size. */
	val sizeLabel: String? get() = if (pixelWidth > 0 && pixelHeight > 0) "$pixelWidth \u00D7 $pixelHeight" else null

	/** What the first-connection question calls the display: its name, else its size, else just "this display". */
	val subject: String
		get() = when {
			name != null -> "\u201C$name\u201D"
			sizeLabel != null -> "this $sizeLabel display"
			else -> "this display"
		}

	val question: String get() = "Use Marquee on $subject?"

	companion object {
		/** Android names a display after its connector ("HDMI Screen", "Overlay #1"), which says nothing about what is on the other end. */
		private val GENERIC = Regex("^(hdmi|display ?port|external|usb[- ]?c?|overlay|virtual|built-in)( ?(screen|display|output))?( ?#?\\d+)?$", RegexOption.IGNORE_CASE)

		/** [raw] as a name worth showing, or null when it is blank or only names the connector. */
		fun nameOf(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && !GENERIC.matches(it) }

		/** Landscape whichever way the display reports it; a name that is blank or generic is none. */
		fun of(pixelWidth: Int, pixelHeight: Int, refreshRate: Int, name: String? = null) = DisplayIdentity(
			pixelWidth = max(pixelWidth, pixelHeight),
			pixelHeight = min(pixelWidth, pixelHeight),
			refreshRate = refreshRate,
			name = nameOf(name),
		)
	}
}

/** One display whose answer is kept. */
@Immutable
@Serializable
data class RememberedDisplay(
	val key: String,
	/** As last seen, for the list in Settings. */
	val name: String? = null,
	val sizeLabel: String? = null,
	val kind: DisplayKind,
	/** [BigScreenUse.Marquee] or [BigScreenUse.Mirror]; a display that is still to be asked is not remembered. */
	val use: BigScreenUse,
) {
	val title: String get() = name ?: sizeLabel ?: "Display"

	val summary: String get() = "${kind.title} \u00B7 ${if (use == BigScreenUse.Mirror) "Mirrors the phone" else "Marquee\u2019s screen"}"
}

/** The answers kept per display, so glasses and a TV can have different ones. [BigScreenUse.Ask] is never kept. */
@Immutable
@Serializable
data class DisplayMemory(val displays: List<RememberedDisplay> = emptyList()) {
	fun entry(identity: DisplayIdentity): RememberedDisplay? = displays.firstOrNull { it.key == identity.key }

	fun remembering(identity: DisplayIdentity, kind: DisplayKind, use: BigScreenUse): DisplayMemory {
		if (use == BigScreenUse.Ask) return this
		val entry = RememberedDisplay(identity.key, identity.name, identity.sizeLabel, kind, use)
		val at = displays.indexOfFirst { it.key == entry.key }
		return DisplayMemory(if (at >= 0) displays.toMutableList().also { it[at] = entry } else displays + entry)
	}

	fun forgetting(key: String): DisplayMemory = DisplayMemory(displays.filterNot { it.key == key })
}

/** What to do with a display that has just connected. */
sealed interface DisplayOutcome {
	data object Ask : DisplayOutcome
	data class Marquee(val kind: DisplayKind) : DisplayOutcome
	data object Mirror : DisplayOutcome

	companion object {
		/**
		 * A remembered answer for this display wins; otherwise the page's "When a Display Connects" does, with
		 * [defaultKind] for a display that was not told apart yet.
		 */
		fun decide(identity: DisplayIdentity, memory: DisplayMemory, use: BigScreenUse, defaultKind: DisplayKind): DisplayOutcome {
			memory.entry(identity)?.let { known -> return if (known.use == BigScreenUse.Mirror) Mirror else Marquee(known.kind) }
			return when (use) {
				BigScreenUse.Ask -> Ask
				BigScreenUse.Marquee -> Marquee(defaultKind)
				BigScreenUse.Mirror -> Mirror
			}
		}
	}
}

/**
 * How the big screen is laid out on a display. Every size is in design points: the canvas is 1080 high on any display
 * and is scaled to the display's own pixels, so the layout tokens stay proportional on a 4K TV.
 */
@Immutable
data class BigScreenLayout(val kind: DisplayKind, val width: Double, val height: Double, val marginX: Double, val marginY: Double) {
	/** Black is see-through on birdbath glasses; a TV shows the theme's own canvas. */
	val usesBlackCanvas: Boolean get() = kind == DisplayKind.Glasses

	/** How much the canvas grows to fill a display of this many pixels, without cropping. */
	fun scale(displayWidth: Double, displayHeight: Double): Double = min(displayWidth / width, displayHeight / height)

	companion object {
		const val DESIGN_HEIGHT = 1080.0

		/** The share of each side a TV keeps clear, as overscan can cut that much off. */
		const val TV_SAFE_MARGIN = 0.05

		/** A TV wider or narrower than this is letterboxed. */
		val TV_ASPECTS = (4.0 / 3.0)..(64.0 / 27.0)

		/** Glasses: 1920 × 1080 whatever they report, with wide margins since the lens edges are soft. */
		val Glasses = BigScreenLayout(DisplayKind.Glasses, 1920.0, DESIGN_HEIGHT, 144.0, 88.0)

		fun of(kind: DisplayKind, pixelWidth: Int, pixelHeight: Int): BigScreenLayout = when (kind) {
			DisplayKind.Glasses -> Glasses
			DisplayKind.Tv -> {
				val aspect = if (pixelWidth > 0 && pixelHeight > 0) pixelWidth.toDouble() / pixelHeight else 16.0 / 9.0
				val clamped = aspect.coerceIn(TV_ASPECTS.start, TV_ASPECTS.endInclusive)
				val width = (DESIGN_HEIGHT * clamped).roundToInt().toDouble()
				BigScreenLayout(
					DisplayKind.Tv, width, DESIGN_HEIGHT,
					(width * TV_SAFE_MARGIN).roundToInt().toDouble(), (DESIGN_HEIGHT * TV_SAFE_MARGIN).roundToInt().toDouble(),
				)
			}
		}
	}
}

/** The canvas's tokens, in design points: the same on glasses and a TV so the layout stays proportional. */
object GlassesCanvas {
	/** Between rows; between a row's title and its tiles; between tiles; between a tile and its caption. */
	const val ROW_GAP = 48f
	const val TITLE_GAP = 16f
	const val TILE_GAP = 32f
	const val CAPTION_GAP = 16f

	/** Line boxes: the page title, a row title, a caption and the line under it, the focused title's two lines. */
	const val HEADER = 72f
	const val ROW_TITLE = 56f
	const val CAPTION = 40f
	const val SUBCAPTION = 32f
	const val FOCUS_LINE = 104f
	val POSTER = 220f to 330f
	val LANDSCAPE = 400f to 225f

	/** A library, tab or option: text on a raised surface. */
	val CARD = 400f to 120f
	val KEY = 96f to 96f
	const val LOGO = 120f

	/** Large status glyphs: play and pause, a notice's symbol. */
	const val GLYPH = 120f

	/** The focused tile grows by this much, and its ring is this wide. The rows keep [FOCUS_ROOM] above the focused tile so the growth is not cut off. */
	const val FOCUS_SCALE = 1.08f
	const val FOCUS_RING = 6f
	const val FOCUS_ROOM = 24f
	const val RADIUS = 24f
	const val PILL = 60f

	/** The opened title: its text block, the width its overview wraps at, and the backdrop behind it. */
	const val HERO_HEIGHT = 330f
	const val OVERVIEW_WIDTH = 1100f
	val BACKDROP = 1200f to 675f

	/** The player's track panel and the options panel, and one line in them. */
	const val PANEL_WIDTH = 760f
	const val OPTION = 88f
	const val BAR_HEIGHT = 10f
	const val VOLUME_WIDTH = 380f
}
