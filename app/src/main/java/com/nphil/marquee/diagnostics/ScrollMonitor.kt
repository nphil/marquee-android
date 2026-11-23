package com.nphil.marquee.diagnostics

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * With Diagnostic Logs on Detailed, how evenly a surface draws while it scrolls, one report per gesture (after the iOS
 * app's `ScrollMonitor`): `scroll.start`, a `scroll.segment` per phase when the gesture had more than one, and
 * `scroll.end`. The frame listener is attached to the window only from the moment a tracked surface moves until it
 * rests. While attached, a frame appends to primitive arrays on the worker thread; nothing is sorted or logged until
 * the gesture ends, so the measuring stays out of the frames it measures. [moving] is for the main thread.
 */
internal object ScrollMonitor {
	private val worker by lazy { HandlerThread("Marquee scroll timings").apply { start() } }
	private val handler by lazy { Handler(worker.looper) }

	// Main thread.
	private var surface = ""
	private var attached: Window? = null

	// Worker thread: frames, listener callbacks and the reports all run there, so none of this needs a lock.
	private val log = FrameLog()
	private val phases = ArrayList<String>()
	private var phase = 0
	private var maxHz = 60
	private var last = 0L

	private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
		val start = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
		// The display's own aim for the frame, which follows a variable refresh rate down and up. Before Android 12 only the top rate is known.
		val aim = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
		if (last != 0L) log.add((start - last) / NANOS, max(1.0 / maxHz, aim / NANOS), phase)
		last = start
	}

	/** [surface] began or stopped moving; [phase] is what moves it now ("interacting" under a finger, "decelerating" on its own). */
	fun moving(window: Window, moving: Boolean, surface: String, phase: String) {
		if (!moving) {
			// A surface that rests late must not end the gesture of the one that began after it.
			if (surface != this.surface) return
			this.surface = ""
			stop(surface, "idle")
			return
		}
		if (attached != null && !Diag.detailed) stop(this.surface, "logging-off")
		if (attached != null && this.surface != surface) stop(this.surface, "surface-change")
		this.surface = surface
		if (!Diag.detailed) return
		if (attached == null) start(window, surface, phase) else handler.post { enter(phase) }
	}

	private fun start(window: Window, surface: String, phase: String) {
		val top = topRefreshRate(window)
		attached = window
		handler.post { begin(phase, top) }
		window.addOnFrameMetricsAvailableListener(listener, handler)
		// iOS asks for its top rate; Android asks for none, so this is only what the panel could do.
		Diag.debug(DiagCategory.Ui, "scroll.start", "surface" to surface, "phase" to phase, "requested_hz" to top)
	}

	private fun stop(surface: String, reason: String) {
		val window = attached ?: return
		attached = null
		window.removeOnFrameMetricsAvailableListener(listener)
		handler.post { finish(surface, reason) }
	}

	private fun begin(first: String, top: Int) {
		log.clear()
		phases.clear()
		phases.add(first)
		phase = 0
		maxHz = top
		last = 0L
	}

	private fun enter(name: String) {
		val known = phases.indexOf(name)
		if (known >= 0) {
			phase = known
		} else {
			phases.add(name)
			phase = phases.lastIndex
		}
	}

	private fun finish(surface: String, reason: String) {
		if (phases.size > 1) {
			for ((index, name) in phases.withIndex()) {
				log.summary(maxHz, index)?.let { Diag.debug(DiagCategory.Ui, "scroll.segment", *it.fields(surface, name, null)) }
			}
		}
		log.summary(maxHz)?.let { Diag.debug(DiagCategory.Ui, "scroll.end", *it.fields(surface, "all", reason)) }
		log.clear()
	}

	/** What a gesture could draw at, whatever rate the display happens to be running when it starts. */
	private fun topRefreshRate(window: Window): Int {
		val display = window.decorView.display
		val top = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: display?.refreshRate ?: 60f
		return max(60, top.roundToInt())
	}

	private const val NANOS = 1_000_000_000.0
}
