package com.nphil.marquee.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.nphil.marquee.MainActivity

/** Instantiated by Google Play services via the manifest, not by the app's dependency container. */
class CastOptionsProvider : OptionsProvider {
	override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
		.setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
		.setResumeSavedSession(false)
		.setEnableReconnectionService(false)
		.setCastMediaOptions(
			CastMediaOptions.Builder().setNotificationOptions(
				NotificationOptions.Builder().setTargetActivityClassName(MainActivity::class.java.name).build(),
			).build(),
		)
		.build()

	override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
