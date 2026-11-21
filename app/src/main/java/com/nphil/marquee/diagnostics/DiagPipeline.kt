package com.nphil.marquee.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * Carries events, calls and requests to the bridge's log in the order they were made. Call sites only enqueue:
 * one coroutine drains the queue in batches, so a tap never waits on JNI and the bridge sees one ordered
 * stream. A full queue drops its oldest items; the journal's own size limits apply on the other side.
 */
internal class DiagPipeline(
	private val bridge: DiagBridge,
	private val wallMillis: () -> Long = System::currentTimeMillis,
	private val offsetSeconds: () -> Int = { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 },
	private val onFailure: (method: String, error: Throwable) -> Unit = { _, _ -> },
) {
	private sealed interface Item {
		class Op(val json: JsonObject) : Item
		class Call(val method: String, val payload: JsonObject) : Item
		class Barrier(val done: CompletableDeferred<Unit>) : Item
	}

	private val queue = Channel<Item>(QUEUED, BufferOverflow.DROP_OLDEST)
	private val starting = Mutex()
	private val playbacks = AtomicInteger()

	@Volatile
	private var start: JsonObject? = null

	@Volatile
	private var launchId: String? = null

	@Volatile
	private var onStarted: (String) -> Unit = {}

	/** Whether Detailed is on: debug events are kept only then, as the bridge's own threshold has it. */
	@Volatile
	var detailed = false
		private set

	/** What `diag.start` is called with, once the first batch is ready to go. */
	fun begin(info: JsonObject) {
		start = info
	}

	/** Starts draining on [scope]; [onStarted] hears the launch id when the bridge has opened the log. */
	fun run(scope: CoroutineScope, onStarted: (String) -> Unit = {}): Job {
		this.onStarted = onStarted
		return scope.launch { drain() }
	}

	fun event(level: DiagLevel, category: DiagCategory, name: String, fields: Array<out Pair<String, Any?>>) {
		if (level == DiagLevel.Debug && !detailed) return
		queue.trySend(Item.Op(DiagWire.event(wallMillis(), level, category, name, fields)))
	}

	fun beginPlayback(): String {
		val id = "p${playbacks.incrementAndGet()}"
		queue.trySend(Item.Op(DiagWire.playbackBegin(id)))
		return id
	}

	fun endPlayback() {
		queue.trySend(Item.Op(DiagWire.playbackEnd))
	}

	fun configure(payload: JsonObject, detailed: Boolean) {
		this.detailed = detailed
		queue.trySend(Item.Call("diag.configure", payload))
	}

	fun phase(name: String) {
		queue.trySend(Item.Call("diag.phase", DiagWire.phase(name)))
	}

	fun flush() {
		queue.trySend(Item.Call("diag.flush", DiagWire.none))
	}

	/** Returns once everything queued before this call has gone to the bridge, or after [timeoutMillis]. */
	suspend fun sync(timeoutMillis: Long = SYNC_MILLIS) {
		val done = CompletableDeferred<Unit>()
		queue.trySend(Item.Barrier(done))
		withTimeoutOrNull(timeoutMillis) { done.await() }
	}

	/** A call that wants its answer, made after what is already queued. */
	suspend fun request(method: String, payload: JsonObject = DiagWire.none): JsonElement {
		sync()
		check(ensureStarted()) { "The diagnostic log did not start." }
		return bridge.call(method, payload)
	}

	/**
	 * The process is dying: what is queued and the crash's own events go out now, and the bridge writes
	 * them to disk and sends them before this returns, or after [timeoutMillis].
	 */
	fun crash(events: List<JsonObject>, timeoutMillis: Long = CRASH_MILLIS) {
		val items = ArrayList<Item>()
		while (true) items += queue.tryReceive().getOrNull() ?: break
		events.mapTo(items) { Item.Op(it) }
		runBlocking {
			withTimeoutOrNull(timeoutMillis) {
				if (ensureStarted()) deliver(items, CRASH) else release(items)
			}
		}
	}

	private suspend fun drain() {
		val batch = ArrayList<Item>(BATCH)
		while (true) {
			val first = queue.receiveCatching().getOrNull() ?: return
			batch.clear()
			batch += first
			while (batch.size < BATCH) batch += queue.tryReceive().getOrNull() ?: break
			if (ensureStarted()) deliver(batch, EVENTS) else release(batch)
		}
	}

	/** Sends [items] in order: runs of events as one call, each call and barrier where it was queued. */
	private suspend fun deliver(items: List<Item>, last: String) {
		val ops = ArrayList<JsonObject>()
		suspend fun send(method: String) {
			if (ops.isEmpty()) return
			call(method, DiagWire.events(offsetSeconds(), ops.toList()))
			ops.clear()
		}
		for (item in items) {
			when (item) {
				is Item.Op -> ops += item.json
				is Item.Call -> {
					send(EVENTS)
					call(item.method, item.payload)
				}
				is Item.Barrier -> {
					send(EVENTS)
					item.done.complete(Unit)
				}
			}
		}
		send(last)
	}

	/** The bridge is not there: what was queued is dropped, and nobody waits for it. */
	private fun release(items: List<Item>) {
		for (item in items) if (item is Item.Barrier) item.done.complete(Unit)
	}

	private suspend fun call(method: String, payload: JsonObject) {
		try {
			bridge.call(method, payload)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			onFailure(method, e)
		}
	}

	private suspend fun ensureStarted(): Boolean =
		launchId != null || starting.withLock { launchId != null || open() }

	private suspend fun open(): Boolean {
		val info = start ?: return false
		return try {
			val id = DiagWire.decode<StartedDto>(bridge.call("diag.start", info)).launch
			launchId = id
			onStarted(id)
			true
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			onFailure("diag.start", e)
			false
		}
	}

	private companion object {
		const val QUEUED = 10_000
		const val BATCH = 200
		const val SYNC_MILLIS = 5_000L
		const val CRASH_MILLIS = 2_500L
		const val EVENTS = "diag.events"
		const val CRASH = "diag.crash"
	}
}
