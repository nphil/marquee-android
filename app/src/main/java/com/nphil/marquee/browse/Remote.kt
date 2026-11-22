package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One screen's server content (DESIGN §11): nothing until the first answer, then the last good answer, kept through
 * later failures together with when it arrived.
 */
@Immutable
data class Remote<out T>(
	val value: T? = null,
	/** When [value] arrived, or was saved if it is still the saved copy; epoch milliseconds. */
	val loadedAt: Long? = null,
	/** Why the last load failed; cleared by the next that works. */
	val failure: Throwable? = null,
	val isLoading: Boolean = false,
)

/** A saved answer and when it was saved, epoch milliseconds. */
class Saved<out T>(val value: T, val at: Long)

/** Loads one screen's [Remote]; [screen] names it in the diagnostic log. Not thread-safe: call it from the main thread. */
class RemoteLoader<T : Any>(private val screen: String, private val now: () -> Long = System::currentTimeMillis) {
	private val mutable = MutableStateFlow(Remote<T>())
	val state: StateFlow<Remote<T>> = mutable.asStateFlow()

	private var generation = 0

	/**
	 * Loads afresh. A load that a newer one overtook, or that was cancelled, changes nothing.
	 *
	 * With [cached], a screen that has nothing yet shows what the last run saved while [fetch] is already on its way: the
	 * saved content counts as loaded when it was saved, with no banner until a refresh fails, and the answer replaces it
	 * in one step. A saved copy still being read when the answer arrives is dropped.
	 */
	suspend fun load(cached: (suspend () -> Saved<T>?)? = null, fetch: suspend () -> T) = coroutineScope {
		val current = ++generation
		val started = System.nanoTime()
		mutable.update { it.copy(isLoading = true) }
		Diag.debug(DiagCategory.Net, "load.start", "screen" to screen, "kept" to (mutable.value.value != null))
		val seeding = if (cached != null && mutable.value.value == null) {
			launch {
				// A copy that cannot be read is no copy; the fetch below carries on.
				val saved = try {
					cached()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					null
				} ?: return@launch
				if (current != generation || mutable.value.value != null) return@launch
				mutable.update { it.copy(value = saved.value, loadedAt = saved.at) }
				Diag.debug(DiagCategory.Net, "load.cached", "screen" to screen, "ms" to millis(started), "ageS" to (now() - saved.at) / 1000)
			}
		} else {
			null
		}
		try {
			val fresh = fetch()
			if (current != generation) return@coroutineScope
			mutable.update { it.copy(value = fresh, loadedAt = now(), failure = null, isLoading = false) }
			val ms = millis(started)
			if (ms > 2000) {
				Diag.info(DiagCategory.Net, "load.ok", "screen" to screen, "ms" to ms)
			} else {
				Diag.debug(DiagCategory.Net, "load.ok", "screen" to screen, "ms" to ms)
			}
		} catch (e: CancellationException) {
			// A cancelled load is not a failure; the screen loads again when it returns.
			if (current == generation) mutable.update { it.copy(isLoading = false) }
			throw e
		} catch (e: Exception) {
			if (current != generation) return@coroutineScope
			mutable.update { it.copy(failure = e, isLoading = false) }
			Diag.notice(
				DiagCategory.Net, "load.fail",
				"screen" to screen, "ms" to millis(started), "error" to e.javaClass.simpleName,
				"shown" to if (mutable.value.value == null) "page" else "banner",
			)
		} finally {
			seeding?.cancel()
		}
	}

	/** Edits the content in place, e.g. to show a change before the server confirms it. */
	fun update(change: (T) -> T) {
		mutable.update { current -> current.value?.let { current.copy(value = change(it)) } ?: current }
	}

	/** Drops everything, for content that belongs to what the screen is no longer showing. */
	fun clear() {
		generation++
		mutable.value = Remote()
	}

	private fun millis(since: Long) = (System.nanoTime() - since) / 1_000_000
}
