package com.nphil.marquee.television

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.bigscreen.*
import com.nphil.marquee.container
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.videoStream
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ProvidePlexImages
import com.nphil.marquee.ui.theme.*
import kotlinx.coroutines.launch

class TelevisionActivity : ComponentActivity() {
    private var input: GlassesNavigator? = null
    private var playing: PlayerModel? = null
    private var heldSelect = false
    private val localAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) container.session.retry() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        if (savedInstanceState == null && android.os.Build.VERSION.SDK_INT >= 37) localAccess.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        setContent {
            val state by container.session.state.collectAsStateWithLifecycle()
            val choice by container.settings.themeChoice.collectAsStateWithLifecycle(ThemeChoice())
            val mode = choice.mode(isSystemInDarkTheme())
            val theme = remember(choice, mode) { container.themes.resolve(choice.id(mode), mode) }
            MarqueeTheme(theme, container.typography) {
                ProvidePlexImages(container) {
                    Box(Modifier.fillMaxSize().background(lucent.canvas), contentAlignment = Alignment.Center) {
                        if (state.phase == AppSession.Phase.Ready) key(state.server?.id, state.user?.uuid) { Library(mode) }
                        else Account(state)
                    }
                }
            }
        }
    }

    @Composable private fun Account(state: AppSession.State) {
        val session = container.session
        val scope = rememberCoroutineScope()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Marquee", color = lucent.accentText, style = MaterialTheme.typography.titleLarge)
            when (val phase = state.phase) {
                AppSession.Phase.Restoring -> CircularProgressIndicator()
                AppSession.Phase.SignedOut -> Action("Link Plex account", session::signInWithCode)
                AppSession.Phase.SigningIn -> {
                    Text("Open plex.tv/link on your phone", color = lucent.ink)
                    Text(state.linkCode ?: "Getting code…", style = MaterialTheme.typography.displayMedium, color = lucent.ink)
                    Action("Cancel", session::cancelSignIn)
                }
                AppSession.Phase.ChoosingServer -> {
                    Text(if (state.servers.isEmpty()) "No servers found" else "Choose a server", color = lucent.ink)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(state.servers.size) { val server = state.servers[it]; Action(server.name) { session.choose(server) } }
                    }
                    Action("Refresh") { scope.launch { session.reloadServers() } }
                    Action("Sign out") { session.signOut() }
                }
                is AppSession.Phase.Failed -> { Text("Could not connect: ${phase.error}", color = lucent.ink); Action("Retry", session::retry); Action("Link another account") { session.signOut() } }
                AppSession.Phase.Ready -> Unit
            }
        }
    }

    @Composable private fun Library(mode: ThemeMode) {
        val scope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(false) }
        var request by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
        val nav = remember { GlassesNavigator(container, scope, openTitle = { id, title, start -> request = Triple(id, title, start) }, tvSettings = { settings = true }) }
        DisposableEffect(nav) { nav.start(); onDispose { nav.close() } }
        SideEffect { input = if (settings) null else nav }
        DisposableEffect(Unit) { onDispose { input = null } }
        BackHandler(settings || nav.panel != null || nav.pages.size > 1 || request != null) {
            if (settings) settings = false else nav.handle(GlassesInput.Back)
        }
        request?.let { item -> key(item) {
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
            val model = viewModel<PlayerModel>(viewModelStoreOwner = owner) { PlayerModel(container, container.http, this@TelevisionActivity, item.first, item.second, item.third) }
            MatchFilmRate(window, model.player, model.plan?.takeUnless { it.route.reencodesVideo }?.videoStream()?.frameRate)
            DisposableEffect(model) {
                playing = model; nav.link = GlassesPlayerLink(model) { request = null }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); nav.link = null; nav.leftPlayer(); playing = null; owner.viewModelStore.clear() }
            }
            LaunchedEffect(model.isPlaying) {
                if (model.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(model.finished) { if (model.finished) request = null }
            val pixels = LocalWindowInfo.current.containerSize
            val layout = BigScreenLayout.of(DisplayKind.Tv, pixels.width, pixels.height)
            val density = (pixels.height / 1080f).coerceAtLeast(0.5f)
            CompositionLocalProvider(LocalDensity provides Density(density, LocalDensity.current.fontScale)) { GlassesPlayerView(model, nav, container, layout) }
        } } ?: if (!settings) { nav.page?.let { page -> key(page) { TvBrowse(page) } } } else Unit
        if (!settings) nav.panel?.let { TvPanel(it, nav) }
        if (settings) {
            Column(Modifier.fillMaxSize().background(lucent.canvas).padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Settings", color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
                Action("Appearance · ${mode.name}") { scope.launch { container.settings.updateTheme { it.copy(matchSystem = false, fixedMode = if (mode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark) } } }
                var confirmSignOut by remember { mutableStateOf(false) }
                if (confirmSignOut) {
                    Text("Unlink this TV from Plex?", color = lucent.ink)
                    Action("Keep linked") { confirmSignOut = false }
                    Action("Unlink account") { container.session.signOut() }
                } else Action("Sign out") { confirmSignOut = true }
                Action("Done") { settings = false }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val nav = input ?: return super.dispatchKeyEvent(event)
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> GlassesInput.Move(FocusMove.Up)
            KeyEvent.KEYCODE_DPAD_DOWN -> GlassesInput.Move(FocusMove.Down)
            KeyEvent.KEYCODE_DPAD_LEFT -> GlassesInput.Move(FocusMove.Left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> GlassesInput.Move(FocusMove.Right)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when {
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> heldSelect = false
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1 && nav.panel == null -> {
                        heldSelect = true
                        if (nav.inPlayer) nav.openPlayerOptions() else nav.handle(GlassesInput.HoldBegan)
                    }
                    event.action == KeyEvent.ACTION_UP && !heldSelect && !event.isCanceled -> nav.handle(GlassesInput.Select)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> GlassesInput.HoldBegan
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                val player = playing ?: return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> player.setPlaying(true); KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause(); else -> player.toggle()
                }; return true
            }
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || action is GlassesInput.Move)) {
            if (action == GlassesInput.HoldBegan && nav.inPlayer) nav.openPlayerOptions() else nav.handle(action)
        }
        return true
    }
    override fun onStop() { playing?.pause(); super.onStop() }
}
