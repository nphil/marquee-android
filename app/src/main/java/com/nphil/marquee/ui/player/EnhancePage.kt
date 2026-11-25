package com.nphil.marquee.ui.player

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nphil.marquee.playback.EnhanceUnavailable
import com.nphil.marquee.playback.EnhanceUpscaler
import com.nphil.marquee.playback.PictureEnhance
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.rememberPlaybackPrefs
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.settings.SettingsFootnote
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.roundToInt

/** What the More menu's Enhance Picture row says it is: the scaler, or why it cannot run on this title. */
internal fun enhanceRowValue(setting: PictureEnhance, unavailable: EnhanceUnavailable?): String =
	unavailable?.short ?: setting.upscaler.title

/** The sentence under the choices: what a change of scaler does to the title playing. */
internal const val ENHANCE_RESTART_NOTE = "Turning it on while a title plays restarts the title once, from where it was."

/** The line shown while the picture is already about the view's size. */
internal const val ENHANCE_NEAR_SCREEN_NOTE = "This video is already close to your screen's resolution, so upscaling adds little."

/**
 * Enhance Picture in the player's menu: the scaler, and when it is on the strengths of each effect. A strength applies to
 * the picture while it is dragged ([onStrength] with `final` false) and is kept on release (`final` true). While one is
 * dragged [fade] falls to 0 and everything but that slider, in a small capsule, fades with it, so the picture can be
 * judged next to the original; [onDragging] tells the screen to put its controls away and show the split. With a screen
 * reader the sliders are adjusted by its own gestures and nothing hides.
 */
@Composable
internal fun EnhancePage(
	setting: PictureEnhance,
	unavailable: EnhanceUnavailable?,
	nearScreen: Boolean,
	fade: Float,
	onDragging: (Boolean) -> Unit,
	onUpscaler: (EnhanceUpscaler) -> Unit,
	onStrength: (Strength, Float, Boolean) -> Unit,
) {
	val choices = EnhanceUpscaler.entries
	Column(Modifier.fadeable(fade), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		choices.forEachIndexed { index, choice ->
			PickRow(index, choices.size, choice.title, selected = setting.upscaler == choice, enabled = unavailable == null) { onUpscaler(choice) }
		}
	}
	SettingsFootnote(unavailable?.message ?: ENHANCE_RESTART_NOTE, Modifier.fadeable(fade))
	if (setting.isOn && unavailable == null) {
		if (nearScreen) SettingsFootnote(ENHANCE_NEAR_SCREEN_NOTE, Modifier.fadeable(fade))
		Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
			Strength.entries.forEachIndexed { index, which ->
				StrengthSlider(which, setting.strength(which), index, Strength.entries.size, fade, onDragging, onStrength)
			}
		}
	}
}

/** The strengths Enhance Picture has, in the order the page and Settings list them. */
internal enum class Strength(val label: String) {
	Sharpen("Sharpen"),
	Deband("Deband"),
	NoiseReduction("Noise Reduction"),
	Deblock("Deblock"),
	Vivid("Vivid"),
}

internal fun PictureEnhance.strength(which: Strength): Float = when (which) {
	Strength.Sharpen -> sharpen
	Strength.Deband -> deband
	Strength.NoiseReduction -> noiseReduction
	Strength.Deblock -> deblock
	Strength.Vivid -> vivid
}

internal fun PictureEnhance.with(which: Strength, value: Float): PictureEnhance = when (which) {
	Strength.Sharpen -> copy(sharpen = value)
	Strength.Deband -> copy(deband = value)
	Strength.NoiseReduction -> copy(noiseReduction = value)
	Strength.Deblock -> copy(deblock = value)
	Strength.Vivid -> copy(vivid = value)
}

private fun Modifier.fadeable(fade: Float): Modifier = if (fade >= 1f) this else graphicsLayer { alpha = fade }

/**
 * One strength from 0 to 100 %. While it is dragged its row's surface fades with the rest of the panel and the row
 * stays, in a dark capsule lit for a picture rather than the sheet. The row keeps its place in the composition, as the
 * drag would end if it moved.
 */
@Composable
private fun StrengthSlider(
	which: Strength,
	value: Float,
	index: Int,
	count: Int,
	fade: Float,
	onDragging: (Boolean) -> Unit,
	onStrength: (Strength, Float, Boolean) -> Unit,
) {
	val state = rememberSliderState(value)
	var dragged by remember { mutableStateOf(false) }
	val report by rememberUpdatedState(onDragging)
	val screenReader = rememberScreenReader()
	fun drag(on: Boolean) {
		if (dragged == on) return
		dragged = on
		if (!screenReader) report(on)
	}
	DisposableEffect(Unit) { onDispose { if (dragged) report(false) } }
	// The row that is dragged keeps its content; the other ones go with the rest.
	val own = if (dragged) 1f else fade
	val ink = lerp(PlayerInk.ink, lucent.ink, if (dragged) fade else 1f)
	val capsule = if (dragged) Modifier.background(PlayerInk.chip.copy(alpha = PlayerInk.chip.alpha * (1f - fade)), RoundedCornerShape(50)) else Modifier
	val label = MaterialTheme.typography.titleMedium
	GroupRow(index, count, Modifier.fadeable(own).then(capsule), surface = fade) {
		Text(which.label, style = label, color = ink, modifier = Modifier.weight(1f))
		Slider(
			state = state,
			onValueChange = {
				drag(true)
				state.value = it
				onStrength(which, it, false)
			},
			onValueChangeFinished = {
				drag(false)
				onStrength(which, state.value, true)
			},
			modifier = Modifier.weight(2f).semantics { contentDescription = which.label },
		)
		Text(
			"${(state.value * 100).roundToInt()}%",
			style = label.copy(fontFeatureSettings = "tnum"),
			color = ink,
			modifier = Modifier.widthIn(min = 44.dp),
		)
	}
}

/** Whether a screen reader that explores by touch is on: its sliders are adjusted by gesture, and nothing may hide under it. */
@Composable
private fun rememberScreenReader(): Boolean {
	val context = LocalContext.current
	return remember { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
}

/**
 * What the picture shows while a strength is dragged: a thin line down the middle, where the shader's split is, with
 * "Original" on its left and "Enhanced" on its right. Fixed light-on-dark colours, as on the system's own overlays, since
 * it sits over a picture and not over the app. It takes no touches.
 */
@Composable
internal fun EnhanceSplitOverlay(modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().fillMaxHeight().clearAndSetSemantics { }) {
		Box(Modifier.align(Alignment.Center).fillMaxHeight().width(3.dp).background(Color.Black.copy(alpha = 0.35f))) {
			Box(Modifier.align(Alignment.Center).fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.9f)))
		}
		Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(top = 16.dp)) {
			Box(Modifier.weight(1f).padding(end = 10.dp), contentAlignment = Alignment.CenterEnd) { SplitLabel("Original") }
			Spacer(Modifier.width(3.dp))
			Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) { SplitLabel("Enhanced") }
		}
	}
}

@Composable
private fun SplitLabel(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.labelMedium,
		color = Color.White,
		modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
	)
}

/** The page as the panel opens it: the profile's own setting, saved and applied to the title playing. */
@Composable
internal fun EnhanceMenu(model: PlayerModel, fade: Float, onDragging: (Boolean) -> Unit) {
	val setting = rememberPlaybackPrefs().value?.pictureEnhance ?: return
	DisposableEffect(model) {
		model.enhancePageOpen(true)
		onDispose { model.enhancePageOpen(false) }
	}
	EnhancePage(
		setting, model.enhanceUnavailable, model.enhanceNearScreen, fade,
		onDragging = { on ->
			model.enhanceDragging(on)
			onDragging(on)
		},
		onUpscaler = { upscaler -> model.saveEnhance { it.copy(upscaler = upscaler) } },
		onStrength = { which, value, final ->
			if (final) model.saveEnhance { it.with(which, value) } else model.tuneEnhance(setting.with(which, value))
		},
	)
}
