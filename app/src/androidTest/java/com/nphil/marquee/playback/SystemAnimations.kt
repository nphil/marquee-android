package com.nphil.marquee.playback

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * The system's animations on for a test, and back as they were after it. CI's emulator runs with them off, which the
 * app reads as reduce motion and answers by never starting a trailer. The composition's test clock does not depend on
 * this setting.
 */
class SystemAnimations : ExternalResource() {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private var before = 1f

	override fun before() {
		before = Settings.Global.getFloat(instrumentation.targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
		scale(1f)
	}

	override fun after() = scale(before)

	/** Through the shell, as an app may not write the setting. The command is done when its output has been read to the end. */
	private fun scale(value: Float) {
		val output = instrumentation.uiAutomation.executeShellCommand("settings put global ${Settings.Global.ANIMATOR_DURATION_SCALE} $value")
		ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
	}
}
