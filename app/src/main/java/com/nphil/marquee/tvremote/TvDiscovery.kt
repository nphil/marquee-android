package com.nphil.marquee.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

internal data class TvAdvertisement(val id: String, val name: String, val host: String, val port: Int, val service: String)

internal data class TvDiscovery(val televisions: List<TvAdvertisement> = emptyList(), val failed: Boolean = false)

@Suppress("DEPRECATION") // Older Android supports only one outstanding mDNS resolve at a time.
internal fun discoverTvs(context: Context): Flow<TvDiscovery> = callbackFlow {
	val manager = context.getSystemService(NsdManager::class.java)
	if (manager == null) {
		trySend(TvDiscovery(failed = true))
		close()
		return@callbackFlow
	}
	val main = Handler(Looper.getMainLooper())
	val pending = LinkedHashMap<String, NsdServiceInfo>()
	val found = LinkedHashMap<String, TvAdvertisement>()
	val visible = HashSet<String>()
	var resolving = false
	var stopped = false

	fun update() { trySend(TvDiscovery(found.values.distinctBy { it.id })) }
	fun resolveNext() {
		if (stopped || resolving) return
		val entry = pending.entries.firstOrNull() ?: return
		val name = entry.key
		val service = entry.value
		pending.remove(name)
		resolving = true
		val result = object : NsdManager.ResolveListener {
			override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
				main.post { resolving = false; resolveNext() }
			}

			override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
				main.post {
					resolving = false
					if (!stopped && name in visible) serviceInfo.advertisement()?.let { found[name] = it; update() }
					resolveNext()
				}
			}
		}
		try {
			manager.resolveService(service, result)
		} catch (_: IllegalArgumentException) {
			resolving = false
			resolveNext()
		} catch (_: SecurityException) {
			trySend(TvDiscovery(failed = true))
			close()
		}
	}

	val listener = object : NsdManager.DiscoveryListener {
		override fun onDiscoveryStarted(serviceType: String) = Unit
		override fun onDiscoveryStopped(serviceType: String) = Unit
		override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
			trySend(TvDiscovery(failed = true))
			close()
		}
		override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
		override fun onServiceFound(service: NsdServiceInfo) {
			main.post {
				if (!stopped) {
					visible.add(service.serviceName)
					pending[service.serviceName] = service
					resolveNext()
				}
			}
		}
		override fun onServiceLost(service: NsdServiceInfo) {
			main.post {
				visible.remove(service.serviceName)
				pending.remove(service.serviceName)
				if (found.remove(service.serviceName) != null) update()
			}
		}
	}
	try {
		manager.discoverServices("_lumatv-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
	} catch (_: SecurityException) {
		trySend(TvDiscovery(failed = true))
		close()
	}
	awaitClose {
		main.post {
			stopped = true
			try { manager.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { /* It never started. */ }
		}
	}
}

@Suppress("DEPRECATION")
private fun NsdServiceInfo.advertisement(): TvAdvertisement? {
	if (attributes["v"]?.decodeToString() != "1" || port !in 1..65535) return null
	val id = attributes["id"]?.decodeToString()?.lowercase() ?: return null
	if (id.length != 16 || id.any { it !in "0123456789abcdef" }) return null
	val address = if (Build.VERSION.SDK_INT >= 34) hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull() else host
	return TvAdvertisement(id, attributes["name"]?.decodeToString()?.takeIf { it.isNotBlank() } ?: serviceName,
		address?.hostAddress ?: return null, port, serviceName)
}
