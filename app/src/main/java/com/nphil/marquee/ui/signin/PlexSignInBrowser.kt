package com.nphil.marquee.ui.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * plex.tv's sign-in page in a Custom Tab, as the iPhone's PlexSignInBrowser opens it: no forward URL; the app
 * polls the PIN and comes back over the tab once it is approved. Never a WebView: Google's sign-in refuses
 * embedded ones.
 */
object PlexSignInBrowser {
	/** Whether a browser here opens Custom Tabs; without one, sign-in falls back to a plex.tv/link code. */
	fun isAvailable(context: Context): Boolean = CustomTabsClient.getPackageName(context, null) != null

	fun open(activity: Activity, page: String) {
		val browser = CustomTabsClient.getPackageName(activity, null) ?: return
		val tab = CustomTabsIntent.Builder()
			// A private session, so plex.tv always asks which account signs in. With the browser's shared
			// cookies the iPhone opened on whichever account was signed in there, and signing out left plex.tv's
			// page for its web app, so the PIN was never approved (2026-11-08).
			.setEphemeralBrowsingEnabled(CustomTabsClient.isEphemeralBrowsingSupported(activity, browser))
			.setShareState(CustomTabsIntent.SHARE_STATE_OFF)
			.build()
		tab.intent.setPackage(browser)
		tab.launchUrl(activity, page.toUri())
	}

	/** Brings [activity] back to the front, clearing the Custom Tab that sits above it in the same task. */
	fun bringBack(activity: Activity) {
		activity.startActivity(
			Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
	}
}
