package com.nphil.marquee.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [changed] when the default network becomes another one. [first] is the network in use when watching starts: the
 * callback's report of it is not a change.
 */
internal class NetworkChanges<N : Any>(first: N?, private val changed: () -> Unit) {
	private var current = first

	fun available(network: N) {
		if (network == current) return
		current = network
		changed()
	}
}

/**
 * A tablet that comes home should move back to the LAN address, as the iPhone app does when its network path changes.
 * Leaving is Core's to notice: the next request that gets no answer drops the address. Changes within a second count
 * once, so a Wi-Fi that drops and returns is one look.
 */
internal fun watchNetworkChanges(context: Context, scope: CoroutineScope, session: AppSession) {
	val connectivity = context.getSystemService(ConnectivityManager::class.java)
	var settling: Job? = null
	val changes = NetworkChanges(connectivity.activeNetwork) {
		settling?.cancel()
		settling = scope.launch {
			delay(SETTLE_MS)
			session.preferLocal("network")
		}
	}
	// On the main thread, where [scope] runs, so `settling` is only ever touched from there.
	connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) = changes.available(network)
	}, Handler(Looper.getMainLooper()))
}

private const val SETTLE_MS = 1_000L
