package com.nphil.marquee.bigscreen

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nphil.marquee.AppContainer

/**
 * Marquee's own screen on the connected display: a presentation window over the external display that hosts the
 * big-screen composition. The window is not an activity, so it carries the lifecycle, saved-state and view-model
 * owners Compose needs, and it keeps the display awake while it shows.
 */
internal class GlassesPresentation(
	host: Context,
	display: Display,
	private val container: AppContainer,
	private val center: GlassesCenter,
) : Presentation(host, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
	private val registry = LifecycleRegistry(this)
	private val saved = SavedStateRegistryController.create(this)
	override val viewModelStore = ViewModelStore()
	override val lifecycle: Lifecycle get() = registry
	override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

	override fun onCreate(savedInstanceState: Bundle?) {
		saved.performRestore(null)
		registry.currentState = Lifecycle.State.CREATED
		super.onCreate(savedInstanceState)
		window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		val view = ComposeView(context).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent { GlassesRoot(container, center) }
		}
		val decor = window?.decorView
		decor?.setViewTreeLifecycleOwner(this)
		decor?.setViewTreeSavedStateRegistryOwner(this)
		decor?.setViewTreeViewModelStoreOwner(this)
		setContentView(view)
	}

	override fun onStart() {
		super.onStart()
		registry.currentState = Lifecycle.State.RESUMED
	}

	override fun onStop() {
		registry.currentState = Lifecycle.State.CREATED
		super.onStop()
	}

	override fun dismiss() {
		registry.currentState = Lifecycle.State.DESTROYED
		viewModelStore.clear()
		super.dismiss()
	}
}
