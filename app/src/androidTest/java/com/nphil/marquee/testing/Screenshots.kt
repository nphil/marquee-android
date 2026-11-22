package com.nphil.marquee.testing

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

private const val Tag = "MarqueeReview"

/**
 * Where the CI script pulls the review screenshots from. It lies outside the app's own storage, so the pictures
 * outlive the uninstall that ends a Gradle test run.
 */
private const val ReviewFolder = "/sdcard/Download/marquee-review"

/** Where each screenshot is written first: the app's external files on the device. */
private val screenshotFolder: File by lazy {
	val context = InstrumentationRegistry.getInstrumentation().targetContext
	File(checkNotNull(context.getExternalFilesDir(null)), "screenshots").also { it.mkdirs() }
}

/** Saves what the node shows as `<name>.png` among the review screenshots, and copies it to [ReviewFolder]. */
fun SemanticsNodeInteraction.saveScreenshot(name: String) {
	val bitmap = captureToImage().asAndroidBitmap()
	val file = File(screenshotFolder, "$name.png")
	file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
	publish(file)
}

/**
 * Copies [file] into [ReviewFolder] through the shell: scoped storage keeps an app out of Download, and the shell is
 * not kept out. The command's output ends only when the copy has, so reading it to the end is the wait. A copy that
 * fails is logged and left for the script's pull to find missing; screenshots are no reason to fail a test run.
 */
private fun publish(file: File) {
	val command = "mkdir -p $ReviewFolder && cp '${file.path}' '$ReviewFolder/' && echo copied"
	val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
	val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
		it.readBytes().decodeToString().trim()
	}
	if (output != "copied") Log.w(Tag, "${file.name} was not copied to $ReviewFolder")
}
