package com.nphil.marquee.core

import android.os.SystemClock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The JNI surface of libMarqueeBridge.so (bridge/). A call is a method name and a JSON payload; the
 * bridge answers through [onResult] on one of its own threads. The bridge's Plex requests come back up
 * through [transportSend] so they run on the app's OkHttp client.
 *
 * Payloads travel as UTF-8 byte arrays: JNI's strings are modified UTF-8, which would mangle emoji in
 * titles.
 */
object NativeCore {
	private val calls = ConcurrentHashMap<Long, CancellableContinuation<ByteArray>>()
	private val lastId = AtomicLong()

	@Volatile
	private var transport: NativeTransport? = null

	/** How long loading the Swift libraries took, in milliseconds; measured on the first call. */
	@Volatile
	var loadMillis: Long = -1
		private set

	private fun load() {
		if (loadMillis >= 0) return
		synchronized(this) {
			if (loadMillis >= 0) return
			val start = SystemClock.elapsedRealtime()
			System.loadLibrary("MarqueeBridge")
			nativeInit()
			loadMillis = SystemClock.elapsedRealtime() - start
		}
	}

	/** Gives the bridge its HTTP client. Set once, before the first call. */
	fun install(transport: NativeTransport) {
		this.transport = transport
	}

	/**
	 * Runs [method] in the bridge and returns its JSON reply. The first call loads the libraries, so it
	 * blocks its thread for a moment: call from a background dispatcher. Cancelling the coroutine cancels
	 * the Swift task and its HTTP requests.
	 */
	suspend fun call(method: String, payload: ByteArray): ByteArray {
		load()
		val id = lastId.incrementAndGet()
		return suspendCancellableCoroutine { continuation ->
			calls[id] = continuation
			continuation.invokeOnCancellation {
				if (calls.remove(id) != null) nativeCancel(id)
			}
			nativeCall(id, method, payload)
		}
	}

	@JvmStatic
	private external fun nativeInit()

	@JvmStatic
	private external fun nativeCall(id: Long, method: String, payload: ByteArray)

	@JvmStatic
	private external fun nativeCancel(id: Long)

	@JvmStatic
	external fun transportResponse(id: Long, status: Int, headers: ByteArray, body: ByteArray)

	@JvmStatic
	external fun transportFailure(id: Long, failure: Int)

	// Called from C on the bridge's threads; kept by name in proguard-rules.pro.

	@JvmStatic
	private fun onResult(id: Long, reply: ByteArray) {
		calls.remove(id)?.resume(reply)
	}

	@JvmStatic
	private fun transportSend(id: Long, request: ByteArray, body: ByteArray?) {
		val transport = transport
		if (transport == null) {
			transportFailure(id, NativeTransport.OTHER)
		} else {
			transport.send(id, request, body)
		}
	}

	@JvmStatic
	private fun transportCancel(id: Long) {
		transport?.cancel(id)
	}
}
