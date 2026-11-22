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
fun SemanticsNodeInteraction.saveScreenshot(name: String) = captureToImage().asAndroidBitmap().saveScreenshot(name)

/** Saves the bitmap as `<name>.png` among the review screenshots, and copies it to [ReviewFolder]. */
fun Bitmap.saveScreenshot(name: String) {
	val file = File(screenshotFolder, "$name.png")
	file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
	publish(file)
}

/** Saves [text] as `<name>.txt` among the review screenshots: the numbers behind a picture. */
fun saveNote(name: String, text: String) {
	val file = File(screenshotFolder, "$name.txt")
	file.writeText(text)
	publish(file)
}

/**
 * Copies [file] into [ReviewFolder] through the shell: scoped storage keeps an app out of Download, and the shell is
 * not kept out. UiAutomation starts a command without a shell, so `&&` and quotes would be passed on as arguments:
 * each step is one plain command, and reading its output to the end is the wait for it. A copy that fails is logged
 * and left for the script's pull to find missing; screenshots are no reason to fail a test run.
 */
private fun publish(file: File) {
	shell("mkdir -p $ReviewFolder")
	shell("cp ${file.path} $ReviewFolder/")
	val copy = "$ReviewFolder/${file.name}"
	val listed = shell("ls $copy")
	if (listed != copy) Log.w(Tag, "${file.name} was not copied to $ReviewFolder: '$listed'")
}

private fun shell(command: String): String {
	val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
	return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
		it.readBytes().decodeToString().trim()
	}
}
