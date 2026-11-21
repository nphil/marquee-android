package com.nphil.marquee.ui.diagnostics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What the floating button wears, as ARGB: the theme's raised glass over its canvas, the edge and the ink. */
class ReportButtonColors(val fill: Int, val edge: Int, val ink: Int)

/** Hands the theme's colours to the floating button, which lives in a window outside the composition. */
@Composable
fun ReportButtonTheme(host: ReportButtonHost) {
	val theme = lucent
	SideEffect {
		host.colors = ReportButtonColors(
			fill = theme.glassRaised.compositeOver(theme.canvas).toArgb(),
			edge = theme.edgeRaised.compositeOver(theme.canvas).toArgb(),
			ink = theme.ink.toArgb(),
		)
	}
}

/**
 * The floating Report a Problem button (DESIGN §8.12): a small window of its own, attached to the activity's,
 * so it stays above every screen and the video and needs no overlay permission. It shows while Marquee is in
 * front, a server and topic are saved and Floating Report Button is on; it can be dragged anywhere, snaps to
 * the nearer side edge and is remembered where it was left. A tap opens the report sheet.
 */
class ReportButtonHost(private val activity: ComponentActivity) {
	private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private var view: ReportButtonView? = null

	var colors: ReportButtonColors? = null
		set(value) {
			field = value
			view?.colors = value
		}

	fun attach() {
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				Diag.settings.config.filterNotNull()
					.map { it.button && it.configured }
					.distinctUntilChanged()
					.collectLatest { visible -> if (visible) show() }
			}
		}
	}

	private suspend fun show(): Unit = coroutineScope {
		val decor = activity.window.decorView
		decor.awaitAttached()
		val density = activity.resources.displayMetrics.density
		val size = (SIZE_DP * density).roundToInt()
		val margin = (MARGIN_DP * density).roundToInt()
		val windowManager = activity.windowManager
		val params = WindowManager.LayoutParams(
			size, size,
			WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or Gravity.START
			token = decor.windowToken
			title = "Report a Problem button"
		}
		var fractionX = prefs.getFloat(FRACTION_X, 1f)
		var fractionY = prefs.getFloat(FRACTION_Y, DEFAULT_FRACTION_Y)
		lateinit var button: ReportButtonView

		// Where the button's top left may be: inside the system bars and the cutout, with a margin.
		fun range(): Rect {
			val bars = ViewCompat.getRootWindowInsets(decor)
				?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
				?: Insets.NONE
			val left = bars.left + margin
			val top = bars.top + margin
			return Rect(
				left, top,
				maxOf(left, decor.width - bars.right - margin - size),
				maxOf(top, decor.height - bars.bottom - margin - size),
			)
		}

		fun apply() = windowManager.updateViewLayout(button, params)

		fun place() {
			val range = range()
			params.x = range.left + ((range.right - range.left) * fractionX).roundToInt()
			params.y = range.top + ((range.bottom - range.top) * fractionY).roundToInt()
			if (button.isAttachedToWindow) apply()
		}

		fun save() {
			val range = range()
			fractionX = if (range.right > range.left) (params.x - range.left).toFloat() / (range.right - range.left) else 1f
			fractionY = if (range.bottom > range.top) (params.y - range.top).toFloat() / (range.bottom - range.top) else DEFAULT_FRACTION_Y
			prefs.edit {
				putFloat(FRACTION_X, fractionX)
				putFloat(FRACTION_Y, fractionY)
			}
		}

		var originX = 0
		var originY = 0
		var settling: ValueAnimator? = null
		button = ReportButtonView(
			activity,
			onTap = { ReportDesk.open(activity, "button") },
			onDown = {
				settling?.cancel()
				originX = params.x
				originY = params.y
			},
			onDrag = { dx, dy ->
				val range = range()
				params.x = (originX + dx).roundToInt().coerceIn(range.left, range.right)
				params.y = (originY + dy).roundToInt().coerceIn(range.top, range.bottom)
				apply()
			},
			onRelease = {
				val range = range()
				val target = if (params.x + size / 2 < decor.width / 2) range.left else range.right
				settling = ValueAnimator.ofInt(params.x, target).apply {
					duration = SETTLE_MILLIS
					interpolator = DecelerateInterpolator()
					addUpdateListener {
						params.x = it.animatedValue as Int
						apply()
					}
					doOnEnd { save() }
					start()
				}
			},
		)
		button.colors = colors
		val resized = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) place()
		}
		decor.addOnLayoutChangeListener(resized)
		place()
		windowManager.addView(button, params)
		view = button
		button.alpha = 0f
		button.scaleX = ENTER_SCALE
		button.scaleY = ENTER_SCALE
		button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ENTER_MILLIS).start()
		launch {
			ReportDesk.sheetOpen.collect { open -> button.visibility = if (open) View.INVISIBLE else View.VISIBLE }
		}
		try {
			awaitCancellation()
		} finally {
			settling?.cancel()
			decor.removeOnLayoutChangeListener(resized)
			view = null
			try {
				windowManager.removeViewImmediate(button)
			} catch (_: IllegalArgumentException) {
				// The window went with its activity.
			}
		}
	}

	private companion object {
		const val PREFS = "diagnostics-button"
		const val FRACTION_X = "x"
		const val FRACTION_Y = "y"
		const val DEFAULT_FRACTION_Y = 0.65f
		const val SIZE_DP = 52
		const val MARGIN_DP = 12
		const val SETTLE_MILLIS = 220L
		const val ENTER_MILLIS = 200L
		const val ENTER_SCALE = 0.6f
	}
}

private suspend fun View.awaitAttached() {
	if (isAttachedToWindow) return
	suspendCancellableCoroutine { continuation ->
		val listener = object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(view: View) {
				removeOnAttachStateChangeListener(this)
				continuation.resume(Unit)
			}

			override fun onViewDetachedFromWindow(view: View) = Unit
		}
		addOnAttachStateChangeListener(listener)
		continuation.invokeOnCancellation { removeOnAttachStateChangeListener(listener) }
	}
}

/** The button itself: a solid disc with the report glyph, pressed down a little under a finger, draggable. */
@SuppressLint("ViewConstructor")
internal class ReportButtonView(
	context: Context,
	private val onTap: () -> Unit,
	private val onDown: () -> Unit,
	private val onDrag: (dx: Float, dy: Float) -> Unit,
	private val onRelease: () -> Unit,
) : View(context) {
	private val slop = ViewConfiguration.get(context).scaledTouchSlop
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
	private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = context.resources.displayMetrics.density
	}
	private val glyph = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_bug_report)).mutate()
	private var downX = 0f
	private var downY = 0f
	private var dragging = false

	var colors: ReportButtonColors? = null
		set(value) {
			field = value
			value?.let { glyph.setTint(it.ink) }
			invalidate()
		}

	init {
		contentDescription = "Report a Problem"
		isClickable = true
	}

	override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

	override fun onDraw(canvas: Canvas) {
		val colors = colors ?: return
		fill.color = colors.fill
		ring.color = colors.edge
		val radius = width / 2f
		canvas.drawCircle(radius, radius, radius - ring.strokeWidth, fill)
		canvas.drawCircle(radius, radius, radius - ring.strokeWidth / 2, ring)
		val glyphSize = (width * GLYPH_FRACTION).roundToInt()
		val inset = (width - glyphSize) / 2
		glyph.setBounds(inset, inset, inset + glyphSize, inset + glyphSize)
		glyph.draw(canvas)
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = event.rawX
				downY = event.rawY
				dragging = false
				onDown()
				animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(PRESS_MILLIS).start()
			}
			MotionEvent.ACTION_MOVE -> {
				val dx = event.rawX - downX
				val dy = event.rawY - downY
				if (!dragging && hypot(dx, dy) > slop) dragging = true
				if (dragging) onDrag(dx, dy)
			}
			MotionEvent.ACTION_UP -> {
				animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MILLIS).start()
				if (dragging) onRelease() else performClick()
			}
			MotionEvent.ACTION_CANCEL -> {
				animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MILLIS).start()
				if (dragging) onRelease()
			}
		}
		return true
	}

	override fun performClick(): Boolean {
		super.performClick()
		onTap()
		return true
	}

	private companion object {
		const val GLYPH_FRACTION = 0.46f
		const val PRESSED = 0.92f
		const val PRESS_MILLIS = 100L
	}
}
