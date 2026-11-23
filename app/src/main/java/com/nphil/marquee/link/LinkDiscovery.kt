package com.nphil.marquee.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address

/** The link's Bonjour service, the iPhone's `LinkTransport.serviceType`; the instance name is the phone's link id. */
private const val SERVICE_TYPE = "_marquee-link._tcp."

internal fun isLinkId(text: String) = text.length == 32 && text.all { it in "0123456789abcdef" }

/** What mDNS shows of linked phones on this network: [phones] resolved, [failed] when discovery cannot run. */
internal data class LinkDiscovery(val phones: List<LinkSeen> = emptyList(), val failed: Boolean = false)

/**
 * Answers to calls under [id], the instance name linked phones look for, until the coroutine is cancelled. Returns by
 * throwing: the service could not be registered, which the caller retries a little later.
 */
internal suspend fun advertiseLink(context: Context, id: String, port: Int) {
	val manager = context.getSystemService(NsdManager::class.java) ?: throw IllegalStateException("No NsdManager.")
	val main = Handler(Looper.getMainLooper())
	val info = NsdServiceInfo().apply {
		serviceName = id
		serviceType = SERVICE_TYPE
		setPort(port)
	}
	var registered: NsdManager.RegistrationListener? = null
	try {
		suspendCancellableCoroutine<Unit> { continuation ->
			val listener = object : NsdManager.RegistrationListener {
				override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
				override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
				override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
					if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("mDNS registration failed: $errorCode")))
				}
				override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
			}
			registered = listener
			try {
				manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
			} catch (e: SecurityException) {
				continuation.resumeWith(Result.failure(e))
			}
		}
	} catch (e: CancellationException) {
		throw e
	} finally {
		val listener = registered
		if (listener != null) main.post { try { manager.unregisterService(listener) } catch (_: IllegalArgumentException) { /* It never registered. */ } }
	}
}

/** Linked phones this network advertises; the flow ends with `failed` set when mDNS cannot be used. */
@Suppress("DEPRECATION") // Older Android supports only one outstanding mDNS resolve at a time.
internal fun discoverLinkedPhones(context: Context): Flow<LinkDiscovery> = callbackFlow {
	val manager = context.getSystemService(NsdManager::class.java)
	if (manager == null) {
		trySend(LinkDiscovery(failed = true))
		close()
		return@callbackFlow
	}
	val main = Handler(Looper.getMainLooper())
	val pending = LinkedHashMap<String, NsdServiceInfo>()
	val found = LinkedHashMap<String, LinkSeen>()
	val visible = HashSet<String>()
	var resolving = false
	var stopped = false

	fun update() { trySend(LinkDiscovery(found.values.toList())) }
	fun resolveNext() {
		if (stopped || resolving) return
		val entry = pending.entries.firstOrNull() ?: return
		val name = entry.key
		pending.remove(name)
		resolving = true
		val result = object : NsdManager.ResolveListener {
			override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
				main.post { resolving = false; resolveNext() }
			}

			override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
				main.post {
					resolving = false
					if (!stopped && name in visible) serviceInfo.seen(name)?.let { found[name] = it; update() }
					resolveNext()
				}
			}
		}
		try {
			manager.resolveService(entry.value, result)
		} catch (_: IllegalArgumentException) {
			resolving = false
			resolveNext()
		} catch (_: SecurityException) {
			trySend(LinkDiscovery(failed = true))
			close()
		}
	}

	val listener = object : NsdManager.DiscoveryListener {
		override fun onDiscoveryStarted(serviceType: String) = Unit
		override fun onDiscoveryStopped(serviceType: String) = Unit
		override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
			trySend(LinkDiscovery(failed = true))
			close()
		}
		override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
		override fun onServiceFound(service: NsdServiceInfo) {
			main.post {
				if (!stopped && isLinkId(service.serviceName)) {
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
		manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
	} catch (_: SecurityException) {
		trySend(LinkDiscovery(failed = true))
		close()
	}
	awaitClose {
		main.post {
			stopped = true
			try { manager.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { /* It never started. */ }
		}
	}
}

/** The first IPv4 address when there is one: the other phone's call needs no interface scope. */
@Suppress("DEPRECATION")
private fun NsdServiceInfo.seen(id: String): LinkSeen? {
	if (port !in 1..65535) return null
	val address = if (Build.VERSION.SDK_INT >= 34) hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull() else host
	return LinkSeen(id, address?.hostAddress ?: return null, port)
}
