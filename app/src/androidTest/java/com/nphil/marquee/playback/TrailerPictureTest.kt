package com.nphil.marquee.playback

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.container
import com.nphil.marquee.testing.Film
import com.nphil.marquee.testing.saveScreenshot
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TrailerPicture] on the emulator over a stand-in for a hero: a 16:9 box in the accent colour for the art, with a
 * player on one of the [Clips] over it. The player is paused, which still draws its first frame and the frame it is
 * moved to, so every picture is the same one for as long as the test looks at it. The hero is shot with the screen's
 * own pixels: a bar left in the picture is a band of black along a side of the shot.
 *
 * A letterbox is cropped away and the hero is left filled with picture, the same for a pillarbox, and a clip with no
 * bars is left as it is. A clip that cuts from one aspect ratio to another is followed there and back. The first
 * trailer is filmed from the art alone, through the video's first frame, to the end of the cross-fade, and saved as a
 * numbered contact sheet among the review screenshots; so is the crop moving. The clock stands still between frames
 * in those two, so the crop's and the fade's every frame is seen.
 */
@RunWith(AndroidJUnit4::class)
class TrailerPictureTest {
	@get:Rule
	val rule = createAndroidComposeRule<ComponentActivity>()

	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val probe = HeroProbe(rule, HeroTag)
	private val film = Film(TileWidth)
	private lateinit var player: ExoPlayer
	private var rendered by mutableStateOf(false)

	@Before
	fun makePlayer() {
		rule.runOnUiThread {
			player = ExoPlayer.Builder(context).build()
			player.addListener(object : Player.Listener {
				override fun onRenderedFirstFrame() {
					rendered = true
				}
			})
		}
	}

	@After
	fun startTheClockAgain() {
		rule.mainClock.autoAdvance = true
	}

	@Test
	fun aLetterboxedTrailerFadesInOverTheArtAndFillsTheHero() {
		show()
		rule.waitForIdle()
		rule.mainClock.autoAdvance = false
		val quiet = Film()
		repeat(BeforeFrames) { frame(film, quiet) }
		start(Clips.Scope)
		val shownAt = probe.filmFade(film, quiet, BeforeFrames, FadeFrames, LoadMillis)
		saveFilm("trailer-start-hero-letterbox", shownAt)

		val report = "the picture showed at frame $shownAt of ${film.size}, the player's first frame was ${if (rendered) "drawn" else "not drawn"}; place per frame: $film"
		assertTrue("the picture never showed. $report", shownAt >= 0)
		assertTrue("the hero moved ${quiet.reach} before the picture showed: something was drawn over the art. $report", quiet.reach <= QuietGap)
		assertTrue("the picture cut in, ${film.biggestStep} of the way in one frame. $report", film.biggestStep <= BiggestStep)
		assertTrue("the picture went back ${film.biggestStepBack} of the way in one frame. $report", film.biggestStepBack <= BiggestStepBack)
		assertTrue("the hero is only ${film.leftover} from the art after the fade: the picture did not take its place. $report", film.leftover >= Replaced)
		assertCropped("the letterbox", Clips.Scope.bars)
	}

	@Test
	fun aPillarboxedTrailerFillsTheHero() = fills(Clips.Pillar, "the pillarbox")

	@Test
	fun aTrailerWithNoBarsIsLeftAsItIs() = fills(Clips.Flat, "the full frame")

	@Test
	fun theCropFollowsATrailerThatCutsBetweenAspectRatios() {
		show()
		start(Clips.Switch)
		awaitShown()
		rule.waitForIdle()
		assertCropped("the opening, a full frame", Clips.Switch.bars)

		seek(Clips.SwitchLetterboxedAt)
		probe.await("the crop to follow the cut to the letterbox", LoadMillis) { probe.state()?.bars?.let { it.top >= Seen && it.bottom >= Seen } == true }
		rule.waitForIdle()
		assertCropped("the letterboxed shot", Clips.Scope.bars)

		seek(Clips.SwitchFullAt)
		probe.await("the crop to follow the cut back to the full frame", LoadMillis) { probe.state()?.bars?.let { it.top <= Gone && it.bottom <= Gone } == true }
		rule.waitForIdle()
		assertCropped("the full frame again", Bars.None)
	}

	@Test
	fun aChangeOfCropMovesInOverSeveralFramesAndDoesNotJump() {
		show()
		start(Clips.Switch)
		awaitShown()
		rule.waitForIdle()
		rule.mainClock.autoAdvance = false
		seek(Clips.SwitchLetterboxedAt)
		// The clock is held, so the stage has seen the cut and the picture has not moved: the first shot is the letterbox as shot.
		probe.await("the stage to see the cut to the letterbox", LoadMillis) { probe.state()?.let { it.bars.top >= Seen } == true }
		val height = probe.shoot { it.height }
		val bands = mutableListOf(probe.shoot(film) { it.blackTop() })
		repeat(CropFrames) {
			rule.mainClock.advanceTimeByFrame()
			bands += probe.shoot(film) { it.blackTop() }
		}
		saveFilm("trailer-crop-follows", 0)

		val peak = bands.first()
		val drops = bands.zipWithNext { before, after -> before - after }
		val report = "rows of black along the top of the $height rows of the hero, per frame from the cut: ${bands.joinToString(" ")}"
		assertTrue("the letterbox was $peak rows before the crop moved, not on screen as shot. $report", peak >= LetterboxShare * height)
		assertTrue("the crop jumped ${drops.max()} rows in one frame. $report", drops.max() <= BiggestDrop * peak)
		assertTrue("the crop went back ${-drops.min()} rows in one frame. $report", -drops.min() <= GoingBack * height)
		assertTrue("${bands.last()} rows of black were left once the crop was done. $report", bands.last() <= LeftoverShare * height)
	}

	private fun fills(clip: Clip, what: String) {
		show()
		start(clip)
		awaitShown()
		rule.waitForIdle()
		assertCropped(what, clip.bars)
	}

	/** The crop is the clip's bars, and the hero shows picture to every edge. */
	private fun assertCropped(what: String, bars: Bars) {
		probe.assertBars(what, bars, probe.picture().bars)
		probe.shoot { probe.assertFilled(what, it) }
	}

	private fun awaitShown() = probe.await("the picture to show", LoadMillis) { probe.state()?.shown == true }

	/** Moves the clock a frame on and shoots the hero into [into]. */
	private fun frame(vararg into: Film) {
		rule.mainClock.advanceTimeByFrame()
		probe.shoot(*into) {}
	}

	private fun saveFilm(name: String, marked: Int) {
		val sheet = film.contactSheet(Columns, marked)
		sheet.saveScreenshot(name)
		sheet.recycle()
	}

	/** Prepares the player on [clip], which is copied out of the test's assets for it to read as a file. */
	private fun start(clip: Clip) {
		val file = File(context.cacheDir, clip.file.substringAfterLast('/'))
		InstrumentationRegistry.getInstrumentation().context.assets.open(clip.file).use { input -> file.outputStream().use { input.copyTo(it) } }
		rule.runOnUiThread {
			player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
			player.prepare()
		}
	}

	private fun seek(positionMs: Long) {
		rule.runOnUiThread { player.seekTo(positionMs) }
	}

	/** The picture over a hero's worth of art, with no video yet. */
	private fun show() {
		val container = context.container
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent {
			DisposableEffect(Unit) { onDispose { player.release() } }
			MarqueeTheme(theme, container.typography) {
				Box(Modifier.fillMaxSize().background(lucent.canvas), contentAlignment = Alignment.Center) {
					Box(Modifier.fillMaxWidth(0.8f).aspectRatio(16f / 9f).testTag(HeroTag).background(lucent.accent)) {
						TrailerPicture(player, rendered, stopped = false, Modifier.matchParentSize())
					}
				}
			}
		}
	}

	private companion object {
		/** Long enough for the emulator's decoder to start and for the stage to read a few frames. */
		const val LoadMillis = 20_000L

		const val HeroTag = "stand-in hero"
		const val TileWidth = 240
		const val Columns = 8

		/** Frames of the art alone, before the player is given its clip. */
		const val BeforeFrames = 4

		/** The fade takes 400 ms, 25 frames of 16 ms; the rest show it done. */
		const val FadeFrames = 32

		/** The crop takes 300 ms, 19 frames of 16 ms; the rest show it done. */
		const val CropFrames = 28

		/** How far the hero may move from the art alone before the picture starts to show. */
		const val QuietGap = 0.03f

		/** How far from the art the hero must be once the picture has faded in: the picture has taken its place. It was 0.32 on the CI emulator. */
		const val Replaced = 0.15f

		// Where the fade sits between the art and the picture, frame to frame. A 400 ms ease moves at most about 0.11 of the
		// way in a frame of 16 ms; a cut moves all of it in one.
		const val BiggestStep = 0.2f
		const val BiggestStepBack = 0.05f

		/** A crop's bars are seen once they are this thick, and gone once they are this thin, as a share of the frame. The clip's letterbox is 0.133. */
		const val Seen = 0.1f
		const val Gone = 0.02f

		// The black along the top of the hero as the crop moves it out, in rows of the hero: at least this share of its height
		// to start with; no frame's drop of more than this share of that (the ease's steepest frame drops about 0.16 of it, a
		// cut all of it); no frame's step back of more than this share of the height; no more than this share left at the end.
		const val LetterboxShare = 0.1f
		const val BiggestDrop = 0.35f
		const val GoingBack = 0.015f
		const val LeftoverShare = 0.03f
	}
}
