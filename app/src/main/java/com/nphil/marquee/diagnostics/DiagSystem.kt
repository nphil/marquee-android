package com.nphil.marquee.diagnostics

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.nphil.marquee.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the system says about the app, in the log: foreground and background, memory pressure, heat, power
 * saving, the network the tablet is on and screen-reader use, plus how the previous run ended. Each watcher is
 * set up on its own, so one the device lacks costs nothing else.
 */
internal class DiagSystem(private val app: Application, private val scope: CoroutineScope) {
	private var started = 0
	private var foreground = false
	private var sampler: Job? = null
	private var lastPath: String? = null

	/** The log is open: records the launch, then how the run before it ended. */
	fun launched(launch: String) {
		Diag.notice(
			DiagCategory.App, "launch",
			"version" to BuildConfig.VERSION_NAME,
			"build" to BuildConfig.VERSION_CODE,
			"commit" to BuildConfig.COMMIT,
			"device" to "${Build.MANUFACTURER} ${Build.MODEL}",
			"android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
			"abi" to Build.SUPPORTED_ABIS.firstOrNull(),
			"launch" to launch,
		)
		scope.launch(Dispatchers.IO) { DiagExits.report(app) }
	}

	fun install() {
		app.registerActivityLifecycleCallbacks(lifecycle)
		app.registerComponentCallbacks(memory)
		guarded("thermal", ::watchThermal)
		guarded("power saver", ::watchPowerSave)
		guarded("network", ::watchNetwork)
		guarded("accessibility", ::watchAccessibility)
	}

	private inline fun guarded(what: String, watch: () -> Unit) {
		try {
			watch()
		} catch (e: RuntimeException) {
			Log.w(TAG, "The $what watcher could not start.", e)
		}
	}

	private val lifecycle = object : Application.ActivityLifecycleCallbacks {
		override fun onActivityStarted(activity: Activity) {
			started++
			if (started == 1 && !foreground) toForeground()
		}

		override fun onActivityStopped(activity: Activity) {
			started = (started - 1).coerceAtLeast(0)
			if (started == 0 && !activity.isChangingConfigurations) toBackground()
		}

		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
		override fun onActivityResumed(activity: Activity) = Unit
		override fun onActivityPaused(activity: Activity) = Unit
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
		override fun onActivityDestroyed(activity: Activity) = Unit
	}

	private fun toForeground() {
		foreground = true
		Diag.info(DiagCategory.App, "foreground")
		Diag.phase("foreground")
		sampler = scope.launch {
			while (true) {
				sample()
				delay(SAMPLE_MILLIS)
			}
		}
	}

	private fun toBackground() {
		foreground = false
		sampler?.cancel()
		Diag.info(DiagCategory.App, "background")
		Diag.phase("background")
		Diag.flush()
	}

	private fun sample() {
		val runtime = Runtime.getRuntime()
		Diag.debug(
			DiagCategory.App, "memory.sample",
			"javaMB" to (runtime.totalMemory() - runtime.freeMemory()) / MB,
			"nativeMB" to Debug.getNativeHeapAllocatedSize() / MB,
			"limitMB" to runtime.maxMemory() / MB,
		)
	}

	private val memory = object : ComponentCallbacks2 {
		override fun onConfigurationChanged(newConfig: Configuration) = Unit

		@Suppress("OVERRIDE_DEPRECATION")
		override fun onLowMemory() {
			Diag.warning(DiagCategory.App, "memory.low")
		}

		override fun onTrimMemory(level: Int) {
			val name = DiagWatch.trimName(level) ?: return
			Diag.event(DiagWatch.trimLevel(level), DiagCategory.App, "memory.trim", "level" to name, "code" to level)
		}
	}

	private fun watchThermal() {
		val power = app.getSystemService(PowerManager::class.java)
		power.addThermalStatusListener(app.mainExecutor) { status ->
			Diag.event(DiagWatch.thermalLevel(status), DiagCategory.App, "thermal", "status" to DiagWatch.thermalName(status))
		}
	}

	private fun watchPowerSave() {
		val power = app.getSystemService(PowerManager::class.java)
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				Diag.notice(DiagCategory.App, "power.saver", "on" to power.isPowerSaveMode)
			}
		}
		ContextCompat.registerReceiver(
			app, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
		)
	}

	private fun watchNetwork() {
		val connectivity = app.getSystemService(ConnectivityManager::class.java)
		connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
			override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
				pathChanged(DiagFacts.network(capabilities))

			override fun onLost(network: Network) = pathChanged("none")
		})
	}

	@Synchronized
	private fun pathChanged(text: String) {
		if (text == lastPath) return
		lastPath = text
		Diag.info(DiagCategory.Net, "path", "state" to text)
	}

	private fun watchAccessibility() {
		val accessibility = app.getSystemService(AccessibilityManager::class.java)
		accessibility.addTouchExplorationStateChangeListener { on ->
			Diag.info(DiagCategory.Ui, "screenReader", "on" to on)
		}
	}

	private companion object {
		const val TAG = "MarqueeDiag"
		const val SAMPLE_MILLIS = 30_000L
		const val MB = 1024L * 1024L
	}
}

/** The names and levels the watchers give what they see. */
internal object DiagWatch {
	/** The trim levels worth a line; the others (the interface hiding) are noise. */
	fun trimName(level: Int): String? = when (level) {
		5 -> "running moderate"
		10 -> "running low"
		15 -> "running critical"
		40 -> "background"
		60 -> "moderate"
		80 -> "complete"
		else -> null
	}

	fun trimLevel(level: Int): DiagLevel = if (level == 10 || level == 15) DiagLevel.Warning else DiagLevel.Notice

	fun thermalName(status: Int): String = when (status) {
		0 -> "none"
		1 -> "light"
		2 -> "moderate"
		3 -> "severe"
		4 -> "critical"
		5 -> "emergency"
		6 -> "shutdown"
		else -> status.toString()
	}

	fun thermalLevel(status: Int): DiagLevel = when {
		status >= 3 -> DiagLevel.Warning
		status == 2 -> DiagLevel.Notice
		else -> DiagLevel.Info
	}

	fun networkText(transports: List<String>, validated: Boolean, metered: Boolean): String = buildString {
		append(transports.joinToString("+").ifEmpty { "other" })
		if (!validated) append(" · no internet")
		if (metered) append(" · metered")
	}
}
