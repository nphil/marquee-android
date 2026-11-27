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
private val insetSteps = listOf(-4, 0, 4, 8, 12, 16, 20)
private val brightnessSteps = listOf(55, 70, 85, 100)
private val edgeSteps = (1..8).toList()
private val spacingSteps = (70..200 step 5).toList()
private val boxSteps = (0..40 step 2).toList()
private fun <T> List<T>.step(value: T, delta: Int): T = this[Math.floorMod(indexOf(value) + delta, size)]
private enum class Setting(val label: String) {
    Preset("Preset"), Size("Size"), Font("Font"), Bold("Bold"), Colour("Colour"), Opacity("Text opacity"), Brightness("Text brightness"),
    Edge("Edge"), EdgeWidth("Edge width"), Box("Background"), BoxColour("Background colour"), BoxOpacity("Background opacity"),
    Radius("Background corners"), Padding("Background padding"), Anchor("Position"), Inset("Distance from edge"), Spacing("Line spacing"),
    Soften("Soften bright text"), Reveal("Reveal as spoken"), Reset("Restore preset");
    fun value(s: SubtitleLook): String = with(s) { when (this@Setting) {
        Preset -> if (isPreset) preset.title else "${preset.title} · Custom"
        Size -> "$size%"; Font -> font.name; Bold -> onOff(bold); Colour -> colour.title; Opacity -> "$opacity%"
        Brightness -> "$brightness%"; EdgeWidth -> "$edgeWidth"; Radius -> "$boxRadius"; Padding -> "$boxPadding"; Spacing -> "$lineSpacing%"; Reset -> preset.title
        Edge -> when (edge) { SubtitleEdge.DropShadow -> "Drop shadow"; SubtitleEdge.SoftShadow -> "Soft shadow"; else -> edge.name }
        Box -> if (!box) "None" else if (windowBox) "Window" else "Line"; BoxColour -> backingColour.name; BoxOpacity -> "$boxOpacity%"; Anchor -> if (atTop) "Top" else "Bottom"; Inset -> if (inset == 0) "Standard" else "${if (inset > 0) "+" else ""}$inset%"; Soften -> onOff(softens); Reveal -> reveal.title
    } }
    fun change(s: SubtitleLook, d: Int): SubtitleLook = with(s) { when (this@Setting) {
        Preset -> applying(SubtitlePreset.entries.step(preset, d))
        Reset -> applying(preset)
        Brightness -> copy(brightness = brightnessSteps.step(brightness, d)); EdgeWidth -> copy(edgeWidth = edgeSteps.step(edgeWidth, d))
        Radius -> copy(boxRadius = boxSteps.step(boxRadius, d)); Padding -> copy(boxPadding = boxSteps.step(boxPadding, d)); Spacing -> copy(lineSpacing = spacingSteps.step(lineSpacing, d))
        Size -> copy(size = SubtitleLook.SIZES.step(size, d)); Font -> copy(font = SubtitleFont.entries.step(font, d)); Bold -> copy(bold = !bold)
        Colour -> copy(colour = SubtitleColour.entries.step(colour, d)); Opacity -> copy(opacity = alphaSteps.step(opacity, d)); Edge -> copy(edge = SubtitleEdge.entries.step(edge, d))
        Box -> { val next = Math.floorMod((if (!box) 0 else if (windowBox) 2 else 1) + d, 3); copy(box = next != 0, windowBox = next == 2) }
        BoxColour -> copy(backingColour = SubtitleBacking.entries.step(backingColour, d)); BoxOpacity -> copy(boxOpacity = alphaSteps.step(boxOpacity, d))
        Anchor -> copy(atTop = !atTop); Inset -> copy(inset = insetSteps.step(inset, d)); Soften -> copy(softens = !softens); Reveal -> copy(reveal = SubtitleReveal.entries.step(reveal, d))
    } }
    private fun onOff(v: Boolean) = if (v) "On" else "Off"
}

@Composable internal fun TvSubtitleStyle(container: AppContainer) {
    val session by container.session.state.collectAsStateWithLifecycle()
    val profile = session.user?.uuid
    val look by remember(profile) { container.settings.subtitleLook(profile) }.collectAsStateWithLifecycle(SubtitleLook())
    var initialFocus by remember { mutableStateOf(true) }
    val first = remember { FocusRequester() }
    val cues = remember { listOf(Cue.Builder().setText("Did you hear that, too?\nI thought we were alone.").build()) }
    val rows = remember(look.box, look.edge) { Setting.entries.filterNot {
        (!look.box && it in setOf(Setting.BoxColour, Setting.BoxOpacity, Setting.Radius, Setting.Padding)) ||
            (look.edge == SubtitleEdge.None && it == Setting.EdgeWidth)
    } }
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxSize().background(lucent.canvas).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Subtitle Style", color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows.size, key = { rows[it].name }) { i ->
                    val row = rows[i]
                    var active by remember { mutableStateOf(false) }
                    fun adjust(delta: Int) { if (profile != null) container.scope.launch { container.settings.updateSubtitleLook(profile) { row.change(it, delta) } } }
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
                    if (i == 0 && initialFocus) LaunchedEffect(Unit) { first.requestFocus(); initialFocus = false }
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
}
