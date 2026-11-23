package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.nphil.marquee.R
import com.nphil.marquee.bigscreen.BigScreenUse
import com.nphil.marquee.bigscreen.DisplayKind
import com.nphil.marquee.container
import com.nphil.marquee.ui.components.ChoiceRow
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/**
 * The big-screen plugin's settings (XR glasses and TVs): on or off, what a connected display is for, what kind it is,
 * the displays remembered, tilt, and the remote's gestures.
 */
@Composable
internal fun BigScreenPage(padding: PaddingValues) {
	val glasses = LocalContext.current.container.glasses
	val prefs = glasses.prefs
	val connected = if (prefs.enabled) glasses.display else null
	val remembered = if (prefs.enabled) prefs.displays.displays else emptyList()
	LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		item { SettingsSwitchRow(0, 1, "Big Screen", prefs.enabled, enabled = true, onChange = glasses::setEnabled) }
		if (prefs.enabled) {
			item {
				ChoiceRow("When a Display Connects", prefs.use, BigScreenUse.entries, { it.title }, 0, 2, glasses::setUse)
			}
			item {
				ChoiceRow("Display Type", glasses.shownKind, DisplayKind.entries, { it.title }, 1, 2, glasses::chooseKind)
			}
		}
		item {
			SettingsFootnote(
				"XR glasses such as the Viture Pro, or a TV on USB-C video out or an HDMI adapter, connect to the phone as an " +
					"external display. Marquee\u2019s own screen fills it, and the phone becomes a remote that works without " +
					"looking at it. Mirror leaves the display to Android, as without the plugin. Android cannot tell glasses from " +
					"a TV, so Display Type is remembered for each display; with none connected it is what a new display is taken " +
					"for. A TV keeps clear of its edges and shows the theme\u2019s own dark canvas; glasses show black, which is " +
					"see-through.",
			)
		}
		if (connected != null) {
			item { Heading("Connected Display") }
			item {
				GroupRow(0, 1) {
					Column(Modifier.weight(1f).testTag("glasses-connected")) {
						Text(connected.name ?: "Display", style = MaterialTheme.typography.titleMedium, color = lucent.ink)
						Text(
							listOfNotNull(connected.sizeLabel, "${connected.refreshRate} Hz").joinToString(" \u00B7 "),
							style = MaterialTheme.typography.bodyMedium, color = lucent.ink2,
						)
					}
				}
			}
		}
		if (remembered.isNotEmpty()) {
			item { Heading("Remembered Displays") }
			itemsIndexed(remembered, key = { _, display -> display.key }) { index, display ->
				GroupRow(index, remembered.size) {
					Column(Modifier.weight(1f)) {
						Text(display.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
						Text(display.summary, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
					}
					IconButton(onClick = { glasses.forget(display.key) }) {
						Icon(painterResource(R.drawable.ic_delete), "Forget ${display.title}", tint = lucent.ink2)
					}
				}
			}
			item { SettingsFootnote("Forget a display and the next connection asks again.") }
		}
		if (prefs.enabled) {
			item { SettingsSwitchRow(0, 1, "Tilt While Holding", prefs.tilt, enabled = true, onChange = glasses::setTilt) }
			item {
				SettingsFootnote(
					"Hold the thumb still after a swipe and tilt the phone to keep scrolling, or tilt while scrubbing to fine-tune. " +
						"Motion is read only while the thumb is down.",
				)
			}
			item { Heading("Remote Gestures") }
			val gestures = listOf(
				"Swipe" to "Moves the focus; a hard flick keeps going",
				"Tap" to "Chooses; in a video, plays or pauses",
				"Swipe in from the left edge" to "Goes back",
				"Hold" to "Opens the options; in a video, scrubs as the thumb slides",
				"Volume buttons" to "Change the volume",
			)
			itemsIndexed(gestures) { index, (name, effect) ->
				GroupRow(index, gestures.size, modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = effect }) {
					Column(Modifier.weight(1f)) {
						Text(name, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
						Text(effect, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
					}
				}
			}
		}
	}
}
