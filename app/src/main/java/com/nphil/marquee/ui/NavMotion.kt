package com.nphil.marquee.ui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut

/**
 * How a page opens and closes, by the back arrow and by the back swipe, in every stack of pages the app has.
 *
 * A close runs on one clock as long as the longest animation in it, and a swipe holds that clock under the finger
 * until it lifts. Nav3's own swipe animation shrinks the page on a slow spring and never fades it, so the page stood
 * on screen, solid, for about 0.3 s after the finger lifted and was then removed in one frame. Here the page's fade
 * and shrink, the poster's flight and the poster's own fades all take [Millis], so they end together. An open is on
 * the same clock: the page's fade-in and the poster's flight take [Millis] and ease alike.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal object NavMotion {
	private const val Millis = 350

	/** Little enough that the page reads as uncovering the screen under it rather than as going away. */
	private const val Shrink = 0.92f

	/**
	 * The fades and the shrink follow the finger one to one. The library already eases the finish after a swipe; a
	 * second curve on these made the page lag the finger and then run ahead of it.
	 */
	fun <T> linear(): FiniteAnimationSpec<T> = tween(Millis, easing = LinearEasing)

	/** The page that leaves fades and shrinks over the screen under it, which stands in full from the first frame. */
	val close = ContentTransform(
		targetContentEnter = EnterTransition.None,
		initialContentExit = fadeOut(linear()) + scaleOut(linear(), Shrink),
	)

	/**
	 * The page that opens fades in over the screen under it, which stands in full from the first frame: fading both
	 * would dip to the canvas between them. It eases as the poster's flight does, so the page arrives with its hero.
	 */
	val open = ContentTransform(
		targetContentEnter = fadeIn(tween(Millis, easing = FastOutSlowInEasing)),
		initialContentExit = ExitTransition.None,
	)

	/** The shared poster flies eased: a straight run across the screen reads as mechanical. */
	val bounds = BoundsTransform { _, _ -> tween(Millis, easing = FastOutSlowInEasing) }
}
