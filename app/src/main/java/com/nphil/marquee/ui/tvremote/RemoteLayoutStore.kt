package com.nphil.marquee.ui.tvremote

import android.content.SharedPreferences
import androidx.core.content.edit

/** How the pad's thumb area behaves. */
internal enum class PadMode(val id: String) {
	Dpad("dpad"),
	Joystick("joystick"),
	Touchpad("touchpad"),
	;

	companion object {
		fun decoded(id: String?) = entries.firstOrNull { it.id == id } ?: Dpad
	}
}

/**
 * Where the user left the remote: the panel's rectangle, the modules kept and the pad mode. It shares the remote's own
 * preferences file, so clearing the remote's data clears these too. The orb's dock of earlier builds is dropped on read.
 */
internal class RemoteLayoutStore(private val prefs: SharedPreferences) {
	init {
		if (prefs.contains(DOCK)) prefs.edit { remove(DOCK) }
	}

	var panel: PanelFractions?
		get() = decodedPanel(prefs.getString(PANEL, null))
		set(value) = prefs.edit { if (value == null) remove(PANEL) else putString(PANEL, value.encoded()) }

	var modules: Set<RemoteModule>
		get() = decodeModules(prefs.getString(MODULES, null))
		set(value) = prefs.edit { putString(MODULES, encodeModules(value)) }

	var mode: PadMode
		get() = PadMode.decoded(prefs.getString(MODE, null))
		set(value) = prefs.edit { putString(MODE, value.id) }

	/** Back to the defaults: every element shown, the panel where it first opens, the D-pad. */
	fun reset() = prefs.edit {
		remove(PANEL)
		remove(MODULES)
		remove(MODE)
	}

	private companion object {
		const val DOCK = "layout.dock"
		const val PANEL = "layout.panel"
		const val MODULES = "layout.modules"
		const val MODE = "layout.mode"
	}
}
