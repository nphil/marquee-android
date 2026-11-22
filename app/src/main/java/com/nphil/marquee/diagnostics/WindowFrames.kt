package com.nphil.marquee.diagnostics

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/** Counts rendered UI frames, not video frames. The worker sleeps when the window isn't drawing. */
internal class WindowFrames(private val window: Window) {
	private val worker = HandlerThread("Marquee frame timings").apply { start() }
	private val handler = Handler(worker.looper)
	private val display = window.decorView.display
	private var frames = 0
	private var budgeted = 0
	private var late = 0
	private var missing = 0
	private var worstNs = 0L
	private val report = Runnable {
		if (frames > 0) {
			Diag.info(DiagCategory.Ui, "window.frames", "frames" to frames, "withDeadline" to budgeted,
				"late" to late, "worstMs" to worstNs / 1_000_000.0, "unobserved" to missing,
				"displayHzAtReport" to display?.refreshRate, "screenAtReport" to DiagScreen.summary)
		}
		frames = 0
		budgeted = 0
		late = 0
		missing = 0
		worstNs = 0L
	}
	private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
		val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
		if (duration >= 0) {
			if (frames++ == 0) handler.postDelayed(report, 2_000)
			missing += dropped
			worstNs = maxOf(worstNs, duration)
			if (Build.VERSION.SDK_INT >= 31) {
				val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
				if (deadline > 0) {
					budgeted++
					if (duration > deadline) late++
				}
			}
		}
	}

	init { window.addOnFrameMetricsAvailableListener(listener, handler) }

	fun close() {
		window.removeOnFrameMetricsAvailableListener(listener)
		handler.post { handler.removeCallbacks(report); report.run(); worker.quitSafely() }
	}
}
