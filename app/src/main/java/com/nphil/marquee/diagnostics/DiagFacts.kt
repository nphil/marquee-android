package com.nphil.marquee.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager

/** What a report says about the tablet, read when it is sent. */
internal object DiagFacts {
	private const val MB = 1024L * 1024L

	fun read(context: Context, server: String, theme: String, config: DiagConfig) = DiagReportText.Facts(
		phone = phone(context),
		power = power(context),
		network = network(context),
		memory = memory(context),
		server = server,
		theme = theme,
		diagnostics = DiagReportText.diagnostics(config),
		accessibility = accessibility(context),
	)

	fun phone(context: Context): String {
		val configuration = context.resources.configuration
		val kind = if (configuration.smallestScreenWidthDp >= TABLET_DP) "tablet" else "phone"
		return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
			"$kind ${configuration.smallestScreenWidthDp}dp · ${configuration.locales[0].toLanguageTag()}"
	}

	fun power(context: Context): String {
		val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
		val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
		val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
		val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
		val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
		val percent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "unknown"
		val power = context.getSystemService(PowerManager::class.java)
		return "battery $percent${if (charging) " (charging)" else ""} · power saver ${if (power.isPowerSaveMode) "on" else "off"} · " +
			"thermal ${DiagWatch.thermalName(power.currentThermalStatus)}"
	}

	fun network(context: Context): String {
		val connectivity = context.getSystemService(ConnectivityManager::class.java)
		val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
		return if (capabilities == null) "none" else network(capabilities)
	}

	fun network(capabilities: NetworkCapabilities): String {
		val names = listOf(
			NetworkCapabilities.TRANSPORT_WIFI to "wifi",
			NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
			NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
			NetworkCapabilities.TRANSPORT_VPN to "vpn",
			NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
		)
		return DiagWatch.networkText(
			transports = names.filter { capabilities.hasTransport(it.first) }.map { it.second },
			validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
			metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
		)
	}

	fun memory(context: Context): String {
		val manager = context.getSystemService(ActivityManager::class.java)
		val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
		val runtime = Runtime.getRuntime()
		return "app ${(runtime.totalMemory() - runtime.freeMemory()) / MB} MB of ${runtime.maxMemory() / MB} MB · " +
			"native ${Debug.getNativeHeapAllocatedSize() / MB} MB · " +
			"device ${info.availMem / MB} MB free of ${info.totalMem / MB} MB${if (info.lowMemory) " (low)" else ""}"
	}

	fun accessibility(context: Context): String {
		val configuration = context.resources.configuration
		val reader = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
		val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
		return "font scale ${configuration.fontScale} · screen reader ${if (reader) "on" else "off"} · system ${if (dark) "dark" else "light"}"
	}

	/** The size from which the app calls a device a tablet. */
	const val TABLET_DP = 600
}
