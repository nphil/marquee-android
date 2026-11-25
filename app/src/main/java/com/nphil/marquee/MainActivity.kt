package com.nphil.marquee

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.diagnostics.DiagReportText
import com.nphil.marquee.diagnostics.WindowFrames
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.MarqueeApp
import com.nphil.marquee.ui.diagnostics.ReportButtonHost
import com.nphil.marquee.ui.diagnostics.ReportButtonTheme
import com.nphil.marquee.ui.diagnostics.ReportSheetHost
import com.nphil.marquee.ui.signin.PlexSignInBrowser
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
	private lateinit var reportButton: ReportButtonHost
	private var frameTimings: WindowFrames? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		val splash = installSplashScreen()
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		reportButton = ReportButtonHost(this).also { it.attach() }
		val container = container
		container.glasses.attach(this)
		var ready = false
		openRemoteInvite(intent)
		openBackup(intent)
		// The splash stays until the first frame can show the chosen theme on the screen the kept sign-in
		// leads to, so neither flashes.
		splash.setKeepOnScreenCondition { !ready }
		lifecycleScope.launch {
			container.session.signInPages.collect { page -> PlexSignInBrowser.open(this@MainActivity, page) }
		}
		// Once the PIN is approved or the sign-in ends, the app comes back over the Custom Tab by itself: plex.tv
		// does not forward back when the browser was already signed in to it.
		lifecycleScope.launch {
			container.session.state.map { it.signInPage != null }.distinctUntilChanged().drop(1)
				.filter { open -> !open }
				.collect { PlexSignInBrowser.bringBack(this@MainActivity) }
		}
		lifecycleScope.launch {
			val initial = container.settings.themeChoice.first()
			withContext(Dispatchers.Default) {
				container.themes
				container.typography
			}
			container.session.state.first { it.phase != AppSession.Phase.Restoring }
			setContent { Root(container, initial) }
			ready = true
		}
	}

	// Linked phones look for each other only while Marquee is in front, and a nearer Plex address may answer again.
	override fun onStart() {
		super.onStart()
		container.link.setForeground(true)
		container.session.preferLocal("foreground")
	}

	override fun onStop() {
		container.link.setForeground(false)
		container.backup.flush()
		super.onStop()
	}

	override fun onDestroy() {
		container.glasses.detach(this)
		super.onDestroy()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		openRemoteInvite(intent)
		openBackup(intent)
	}

	private fun openRemoteInvite(intent: Intent) {
		val data = intent.data ?: return
		if (intent.action == Intent.ACTION_VIEW && data.scheme == "lumaremote" && data.host == "pair") {
			container.tvRemote.openInvite(data.toString())
			intent.data = null
		}
	}

	/** A backup file opened from a file manager goes to Settings › Backup & Sync, which offers to restore it. */
	private fun openBackup(intent: Intent) {
		val data = intent.data ?: return
		if (intent.action == Intent.ACTION_VIEW && data.scheme == "content") {
			container.backup.open(data)
			intent.data = null
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		preferFastestRefreshRate()
		frameTimings = WindowFrames(window)
	}

	override fun onDetachedFromWindow() {
		frameTimings?.close()
		frameTimings = null
		super.onDetachedFromWindow()
	}

	/** Without asking, a 120 Hz panel runs this window's scrolling and transitions at 60 Hz. */
	private fun preferFastestRefreshRate() {
		val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
		val current = screen?.mode ?: return
		val fastest = screen.supportedModes
			.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
			.maxByOrNull { it.refreshRate } ?: return
		window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
	}

	@Composable
	private fun Root(container: AppContainer, initial: ThemeChoice) {
		val choice by container.settings.themeChoice.collectAsStateWithLifecycle(initial)
		val mode = choice.mode(isSystemInDarkTheme())
		val id = choice.id(mode)
		val theme = remember(id, mode) { container.themes.resolve(id, mode) }
		var shown by remember { mutableStateOf(choice) }
		LaunchedEffect(choice) {
			if (choice != shown) {
				Diag.info(DiagCategory.Ui, "theme.change", "from" to DiagReportText.themeSlots(shown), "to" to DiagReportText.themeSlots(choice))
				shown = choice
			}
		}
		val dark = theme.lucent.isDark
		// System bar icons follow the theme on screen, which need not match the system's appearance.
		LaunchedEffect(dark) {
			val bars = if (dark) {
				SystemBarStyle.dark(Color.TRANSPARENT)
			} else {
				SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
			}
			enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
		}
		val scope = rememberCoroutineScope()
		MarqueeTheme(theme, container.typography) {
			MarqueeApp(container, choice, onTheme = { change -> scope.launch { container.settings.updateTheme(change) } })
			ReportButtonTheme(reportButton)
			ReportSheetHost(container)
		}
	}
}
