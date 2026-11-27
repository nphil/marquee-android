package com.nphil.marquee.television

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import com.nphil.marquee.AppContainer
import com.nphil.marquee.data.*
import com.nphil.marquee.ui.player.CaptionView
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

private val alphaSteps = listOf(25, 50, 65, 75, 80, 90, 100)
private fun <T> List<T>.step(value: T, delta: Int): T = this[Math.floorMod(indexOf(value) + delta, size)]
private enum class Setting(val label: String) {
    Preset("Preset"), Size("Size"), Font("Font"), Bold("Bold"), Colour("Colour"), Opacity("Text opacity"), Edge("Edge"), Box("Line background"), BoxOpacity("Background opacity"), Soften("Soften bright text"), Reveal("Reveal as spoken");
    fun value(s: SubtitleLook): String = with(s) { when (this@Setting) {
        Preset -> if (isPreset) preset.title else "${preset.title} · Custom"
        Size -> "$size%"; Font -> font.name; Bold -> onOff(bold); Colour -> colour.title; Opacity -> "$opacity%"
        Edge -> when (edge) { SubtitleEdge.DropShadow -> "Drop shadow"; SubtitleEdge.SoftShadow -> "Soft shadow"; else -> edge.name }
        Box -> onOff(box); BoxOpacity -> "$boxOpacity%"; Soften -> onOff(softens); Reveal -> reveal.title
    } }
    fun change(s: SubtitleLook, d: Int): SubtitleLook = with(s) { when (this@Setting) {
        Preset -> SubtitleLook.of(SubtitlePreset.entries.step(preset, d), reveal)
        Size -> copy(size = SubtitleLook.SIZES.step(size, d)); Font -> copy(font = SubtitleFont.entries.step(font, d)); Bold -> copy(bold = !bold)
        Colour -> copy(colour = SubtitleColour.entries.step(colour, d)); Opacity -> copy(opacity = alphaSteps.step(opacity, d)); Edge -> copy(edge = SubtitleEdge.entries.step(edge, d))
        Box -> copy(box = !box); BoxOpacity -> copy(boxOpacity = alphaSteps.step(boxOpacity, d)); Soften -> copy(softens = !softens); Reveal -> copy(reveal = SubtitleReveal.entries.step(reveal, d))
    } }
    private fun onOff(v: Boolean) = if (v) "On" else "Off"
}

@Composable internal fun TvSubtitleStyle(container: AppContainer) {
    val session by container.session.state.collectAsStateWithLifecycle()
    val profile = session.user?.uuid
    val look by remember(profile) { container.settings.subtitleLook(profile) }.collectAsStateWithLifecycle(SubtitleLook())
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val cues = remember { listOf(Cue.Builder().setText("Did you hear that, too?").build()) }
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxSize().background(lucent.canvas).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Subtitle Style", color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
            Text("↑ ↓  Choose a setting     ← →  Adjust", color = lucent.ink2)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(Setting.entries.size) { i ->
                    val row = Setting.entries[i]
                    var active by remember { mutableStateOf(false) }
                    fun adjust(delta: Int) { if (profile != null) scope.launch { container.settings.updateSubtitleLook(profile) { row.change(it, delta) } } }
                    Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { active = it.isFocused }.onPreviewKeyEvent { event ->
                            val delta = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
                            if (event.type == KeyEventType.KeyDown) adjust(delta)
                            true
                        }.clip(shape).background(if (active) lucent.glassRaised else lucent.sheet)
                        .then(if (active) Modifier.border(2.dp, lucent.accent, shape) else Modifier)
                        .clickable { adjust(1) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, Modifier.weight(1f), color = lucent.ink)
                        Text(row.value(look), color = if (active) lucent.accentText else lucent.ink2)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Text subtitle preview", color = lucent.accentText, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(shape).background(Brush.linearGradient(listOf(lucent.accent, lucent.glassRaised, lucent.canvas)))) {
                CaptionView(cues, look, false, Modifier.fillMaxSize())
            }
            Text("Saved for this profile. Subtitles drawn into the video cannot be restyled.", color = lucent.ink2)
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
