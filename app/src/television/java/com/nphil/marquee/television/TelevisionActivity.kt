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
                        if (state.phase == AppSession.Phase.Ready) key(state.server?.id, state.user?.uuid) { Library() }
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

    @Composable private fun Library() {
        val scope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(false) }
        var style by remember { mutableStateOf(false) }
        var requests by remember { mutableStateOf(false) }
        val requestsAvailable by container.seerr.enabled.collectAsStateWithLifecycle()
        var about by remember { mutableStateOf<com.nphil.marquee.core.PlexItem?>(null) }
        var request by remember { mutableStateOf<com.nphil.marquee.ui.player.PlayRequest.Title?>(null) }
        val nav = remember { GlassesNavigator(container, scope, openTitle = { id, title, start, file -> request = com.nphil.marquee.ui.player.PlayRequest.Title(id, fromStart = start, titleKey = title, recordingMediaID = file) },
            tvSettings = { settings = true }, tvSubtitleStyle = { style = true }, tvRequests = { requests = true }, tvAbout = { about = it }) }
        LaunchedEffect(requestsAvailable) { nav.refreshRequestsEntry(); if (!requestsAvailable) requests = false }
        DisposableEffect(nav) { nav.start(); onDispose { nav.close() } }
        SideEffect { input = if (settings || style || requests || about != null) null else nav }
        DisposableEffect(Unit) { onDispose { input = null } }
        BackHandler(style || settings || requests || about != null || nav.panel != null || nav.pages.size > 1 || request != null) {
            when { about != null -> about = null; style -> style = false; settings -> settings = false; requests -> requests = false; else -> nav.handle(GlassesInput.Back) }
        }
        request?.let { item -> key(item) {
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
            val model = viewModel<PlayerModel>(viewModelStoreOwner = owner) { PlayerModel(container, container.http, this@TelevisionActivity, item.ratingKey, item.titleKey ?: item.ratingKey, item.fromStart, recordingMediaID = item.recordingMediaID) }
            MatchFilmRate(window, model.player, model.plan?.takeUnless { it.route.reencodesVideo }?.videoStream()?.frameRate)
            val covered = nav.panel != null || settings || style || about != null
            SideEffect { model.menusOpen = covered }
            LaunchedEffect(model, covered) { if (covered) model.userActed() }
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
        } } ?: if (!settings && !style && !requests && about == null) { nav.page?.let { page -> key(page) { TvBrowse(page, nav.panel == null) } } } else Unit
        when {
            about != null -> about?.let { TvAbout(it) }
            style -> TvSubtitleStyle(container)
            settings -> TvSettings(container) { settings = false }
            requests -> com.nphil.marquee.ui.requests.RequestsDestination(container, Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) { requests = false }
            else -> TvOptions(nav.panel, nav)
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
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                    else -> if (nav.scrubTime != null) nav.handle(GlassesInput.Select)
                        else if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) player.setPlaying(true) else player.toggle()
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
