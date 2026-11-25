package com.nphil.marquee.ui.player

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
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

/**
 * Enhance Picture in the player's menu: the scaler, and when it is on the Sharpen and Deband strengths. A strength
 * applies to the picture while it is dragged ([onStrength] with `final` false) and is kept on release (`final` true).
 * While one is dragged [fade] falls to 0 and everything but that slider's track, label and percentage fades with it, so
 * the picture can be judged; [onDragging] tells the screen to put its controls away too. With a screen reader the
 * sliders are adjusted by its own gestures and nothing hides.
 */
@Composable
internal fun EnhancePage(
	setting: PictureEnhance,
	unavailable: EnhanceUnavailable?,
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
		Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
			StrengthSlider(Strength.Sharpen, setting.sharpen, 0, fade, onDragging, onStrength)
			StrengthSlider(Strength.Deband, setting.deband, 1, fade, onDragging, onStrength)
		}
	}
}

/** The two strengths Enhance Picture has. */
internal enum class Strength(val label: String) { Sharpen("Sharpen"), Deband("Deband") }

private fun Modifier.fadeable(fade: Float): Modifier = if (fade >= 1f) this else graphicsLayer { alpha = fade }

/**
 * One strength from 0 to 100 %. Its row's surface fades with the rest while it is dragged, its own label, track and
 * percentage stay, lit for a picture rather than the sheet.
 */
@Composable
private fun StrengthSlider(
	which: Strength,
	value: Float,
	index: Int,
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
	// The row that is dragged keeps its content; the other one goes with the rest.
	val own = if (dragged) 1f else fade
	val ink = lerp(PlayerInk.ink, lucent.ink, if (dragged) fade else 1f)
	val label = MaterialTheme.typography.titleMedium.let { style ->
		if (dragged && fade < 1f) style.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f * (1f - fade)), blurRadius = 8f)) else style
	}
	GroupRow(index, 2, Modifier.fadeable(own), surface = fade) {
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

/** The page as the panel opens it: the profile's own setting, saved and applied to the title playing. */
@Composable
internal fun EnhanceMenu(model: PlayerModel, fade: Float, onDragging: (Boolean) -> Unit) {
	val setting = rememberPlaybackPrefs().value?.pictureEnhance ?: return
	EnhancePage(
		setting, model.enhanceUnavailable, fade, onDragging,
		onUpscaler = { upscaler -> model.saveEnhance { it.copy(upscaler = upscaler) } },
		onStrength = { which, value, final ->
			if (final) model.saveEnhance { it.with(which, value) } else model.tuneEnhance(setting.with(which, value))
		},
	)
}

private fun PictureEnhance.with(which: Strength, value: Float): PictureEnhance = when (which) {
	Strength.Sharpen -> copy(sharpen = value)
	Strength.Deband -> copy(deband = value)
}
