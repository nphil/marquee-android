package com.nphil.marquee.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Where the review screenshots go: the app's external files, which the CI script pulls before it uninstalls the app. */
private val screenshotFolder: File by lazy {
	val context = InstrumentationRegistry.getInstrumentation().targetContext
	File(checkNotNull(context.getExternalFilesDir(null)), "screenshots").also { it.mkdirs() }
}

/** Saves what the node shows as `<name>.png` among the review screenshots. */
fun SemanticsNodeInteraction.saveScreenshot(name: String) {
	val bitmap = captureToImage().asAndroidBitmap()
	File(screenshotFolder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
