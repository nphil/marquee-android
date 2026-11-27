package com.nphil.marquee.television

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.nphil.marquee.ui.player.clock
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay

/** Promotion keeps the preview's decoder, position and audio session. It never starts title playback. */
internal class TvTrailerTransport {
    var player by mutableStateOf<Player?>(null); private set
    var full by mutableStateOf(false); private set
    var hud by mutableStateOf(false); private set
    var interaction by mutableIntStateOf(0); private set
    private var consumed = KeyEvent.KEYCODE_UNKNOWN

    fun attach(value: Player?) { player = value; if (value == null) { full = false; hud = false } }
    fun collapse() { if (full) player?.play(); full = false; hud = false }
    fun hide() { hud = false }
    fun key(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && consumed == code) { consumed = KeyEvent.KEYCODE_UNKNOWN; return true }
        val engine = player ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0 && !full) return consumed == code
        if (!full && code != KeyEvent.KEYCODE_DPAD_UP) return false
        val seek = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        if (event.repeatCount > 0 && !seek) return consumed == code
        if (!full) { full = true; hud = true }
        else when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN -> collapse()
            KeyEvent.KEYCODE_DPAD_UP -> hud = !hud
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { engine.playWhenReady = !engine.playWhenReady; hud = true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { engine.play(); hud = true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { engine.pause(); hud = true }
            KeyEvent.KEYCODE_MENU -> Unit // The title menu belongs to the page beneath the trailer.
            else -> if (seek) {
                val forward = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                if (engine.isCurrentMediaItemSeekable && engine.duration > 0) engine.seekTo((engine.currentPosition + if (forward) 5_000 else -5_000).coerceIn(0L, engine.duration - 1))
                hud = true
            } else return false
        }
        consumed = code; interaction++; return true
    }
}

@Composable internal fun BoxScope.TvTrailerHud(remote: TvTrailerTransport, title: String) {
    val engine = remote.player ?: return
    LaunchedEffect(remote.interaction, remote.full) {
        if (remote.full && remote.hud && engine.playWhenReady) { delay(3_000); remote.hide() }
    }
    if (!remote.hud) return
    var position by remember(engine) { mutableLongStateOf(engine.currentPosition) }
    LaunchedEffect(engine, remote.interaction) { do { position = engine.currentPosition; delay(250) } while (engine.playWhenReady) }
    val total = engine.duration.coerceAtLeast(0)
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(lucent.scrim.copy(alpha = 0f), lucent.scrim))).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text("Trailer", color = lucent.ink2)
        Text(title, color = lucent.ink, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (total > 0) LinearProgressIndicator(progress = { (position.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = lucent.accent, trackColor = lucent.trackOff)
        Text("${if (!engine.playWhenReady) "Paused · " else ""}${clock(position)} / ${clock(total)}", color = lucent.ink)
    }
}
