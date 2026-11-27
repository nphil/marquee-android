package com.nphil.marquee.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.episodeCode
import com.nphil.marquee.playback.Countdown
import com.nphil.marquee.playback.Offer
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space

/** How far an offer slides as it comes and goes. */
private val OFFER_SLIDE = 16.dp

/**
 * Skip Intro and Up Next over the picture, at the bottom-end above the seek bar, in the player's dark colours on every
 * theme. [visible] is the screen's say on the dwell: a card that stayed up would be a bright shape in one place on the
 * panel.
 */
@Composable
internal fun OfferOverlay(model: PlayerModel, visible: Boolean, modifier: Modifier = Modifier) {
	val offers = model.offers
	val offer = offers.offer
	// The last offer stays drawn while it fades out.
	val last = remember { arrayOfNulls<Offer>(1) }
	if (offer != null) last[0] = offer
	val accent = rememberPlayerAccent()
	val slide = with(LocalDensity.current) { OFFER_SLIDE.roundToPx() }
	val motion = MaterialTheme.motionScheme
	AnimatedVisibility(
		visible = offer != null && visible,
		enter = fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { slide },
		exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec<IntOffset>()) { slide },
		modifier = modifier,
	) {
		when (val shown = offer ?: last[0]) {
			is Offer.SkipIntro -> SkipIntroOffer(
				accent = accent,
				countdown = offers.countdown,
				onSkip = {
					model.userActed()
					offers.skipIntro()
				},
				onWatch = offers::watchIntro,
			)
			is Offer.SkipCredits -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
				GlassPill("Watch Credits", offers::watchCredits)
				GlassPill("Skip Credits", offers::skipCredits, icon = R.drawable.ic_skip_next, fill = accent, content = PlayerInk.onAccent)
			}
			is Offer.UpNext -> UpNextCard(
				accent = accent,
				next = shown.next,
				countdown = offers.countdown,
				onPlay = model::playNext,
				onWatch = offers::watchCredits,
			)
			null -> Unit
		}
	}
}

@Composable
private fun SkipIntroOffer(accent: Color, countdown: Countdown?, onSkip: () -> Unit, onWatch: () -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
		if (countdown != null) GlassPill("Watch Intro", onWatch)
		GlassPill(
			"Skip Intro",
			onSkip,
			icon = R.drawable.ic_skip_next,
			fill = accent,
			content = PlayerInk.onAccent,
			trailing = if (countdown != null) {
				{ CountdownRing(countdown, PlayerInk.onAccent, Modifier.size(18.dp)) }
			} else {
				null
			},
		)
	}
}

@Composable
private fun UpNextCard(accent: Color, next: PlexItem, countdown: Countdown?, onPlay: () -> Unit, onWatch: () -> Unit) {
	val shape = RoundedCornerShape(Radius.card)
	Row(
		modifier = Modifier
			.widthIn(max = 440.dp)
			.background(PlayerInk.card, shape)
			.border(1.dp, PlayerInk.cardEdge, shape)
			.padding(Space.s3),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		PlexArt(
			next.thumb ?: next.art,
			widthDp = 112.dp,
			heightDp = 63.dp,
			modifier = Modifier.clip(RoundedCornerShape(Radius.inner(Radius.card, Space.s3))),
		)
		Column(Modifier.weight(1f, fill = false)) {
			Text("Up Next", style = MaterialTheme.typography.labelMedium, color = PlayerInk.ink2)
			Text(
				listOfNotNull(next.episodeCode, next.title.ifEmpty { null }).joinToString(" · "),
				style = MaterialTheme.typography.titleSmall,
				color = PlayerInk.ink,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			TextButton(
				onClick = onWatch,
				contentPadding = PaddingValues(0.dp),
				colors = ButtonDefaults.textButtonColors(contentColor = accent),
			) { Text("Watch Credits") }
		}
		Box(contentAlignment = Alignment.Center) {
			FilledIconButton(
				onClick = onPlay,
				modifier = Modifier.size(44.dp),
				colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayerInk.solid, contentColor = PlayerInk.onSolid),
			) {
				Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = "Play Next Episode", modifier = Modifier.size(28.dp))
			}
			if (countdown != null) CountdownRing(countdown, accent, Modifier.size(52.dp))
		}
	}
}

/** A ring that fills as the [countdown] runs out. It picks up where the clock is, so it is right when it comes back after a hide. */
@Composable
internal fun CountdownRing(countdown: Countdown, color: Color, modifier: Modifier) {
	fun leftMs() = (countdown.lengthMs - (SystemClock.elapsedRealtime() - countdown.startedAt)).coerceIn(0L, countdown.lengthMs)
	val progress = remember(countdown) { Animatable(1f - leftMs().toFloat() / countdown.lengthMs) }
	LaunchedEffect(countdown) {
		progress.animateTo(1f, tween(durationMillis = leftMs().toInt().coerceAtLeast(1), easing = LinearEasing))
	}
	CircularProgressIndicator(
		progress = { progress.value },
		modifier = modifier,
		color = color,
		trackColor = color.copy(alpha = 0.3f),
		strokeWidth = 2.dp,
		gapSize = 0.dp,
	)
}
