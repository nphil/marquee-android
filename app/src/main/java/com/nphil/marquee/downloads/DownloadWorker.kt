package com.nphil.marquee.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nphil.marquee.MainActivity
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException

/** The OS may stop the job at any time; its next run reopens the last durable prefix and Plex queue item. */
class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		var denied = false
		val retry = applicationContext.container.downloads.transferWork { entry ->
			if (!denied) {
				try {
					setForeground(foreground(entry))
				} catch (e: CancellationException) {
					throw e
				} catch (e: IllegalStateException) {
					// A background start can be refused. Keep the ordinary scheduled job and its checkpoints.
					denied = true
					Diag.warning(DiagCategory.Download, "foreground.refused", "error" to e.javaClass.simpleName)
				}
			}
		}
		return if (retry) Result.retry() else Result.success()
	}

	private fun foreground(entry: DownloadEntry): ForegroundInfo {
		val context = applicationContext
		context.getSystemService(NotificationManager::class.java)
			.createNotificationChannel(NotificationChannel(Channel, "Downloads", NotificationManager.IMPORTANCE_LOW))
		val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
		val notification = NotificationCompat.Builder(context, Channel)
			.setSmallIcon(R.drawable.ic_download)
			.setContentTitle("Downloading ${entry.title.title}")
			.setContentText(entry.choice.label)
			.setContentIntent(open)
			.setOnlyAlertOnce(true)
			.setOngoing(true)
			.build()
		return ForegroundInfo(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	}

	private companion object {
		const val Channel = "marquee.downloads"
		const val NotificationId = 8311
	}
}

/** A missed network or server never discards a local resume point. */
class DownloadSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result =
		if (applicationContext.container.downloads.syncWork()) Result.retry() else Result.success()
}
