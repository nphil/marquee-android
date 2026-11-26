package com.nphil.marquee.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.CoreException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal class RecordingAlerts(private val context: Context) {
    private val prefs = context.getSharedPreferences("recording_history", Context.MODE_PRIVATE)
    private val lock = Mutex()
    suspend fun <T> locked(action: suspend () -> T): T = lock.withLock { action() }
    fun journal(key: String): String? = prefs.getString("journal:$key", null)
    fun enabled(key: String) = prefs.getBoolean("alerts:$key", false)
    fun enable(key: String, on: Boolean) {
        prefs.edit().putBoolean("alerts:$key", on).apply()
        val work = WorkManager.getInstance(context)
        if (on) {
            val request = PeriodicWorkRequestBuilder<RecordingCheck>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("key" to key)).build()
            work.enqueueUniquePeriodicWork("recordings:$key", ExistingPeriodicWorkPolicy.KEEP, request)
        } else work.cancelUniqueWork("recordings:$key")
    }
    fun accept(key: String, journal: String?, events: List<AlertEvent>) {
        if (journal != null) prefs.edit().putString("journal:$key", journal).apply()
        if (events.isEmpty() || !enabled(key)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val channel = "recordings"
        manager.createNotificationChannel(NotificationChannel(channel, "Recording updates", NotificationManager.IMPORTANCE_DEFAULT))
        events.forEach { event -> manager.notify("$key:${event.id}", 0, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tv).setContentTitle(event.title).setContentText(event.body).setAutoCancel(true).build()) }
    }
}

class RecordingCheck(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("key") ?: return Result.failure()
        val app = applicationContext.container
        if (!app.recordingAlerts.enabled(key)) return Result.success()
        val guide = withTimeoutOrNull(12_000) { app.live.guide.filterNotNull().first() } ?: return Result.retry()
        if (guide.key != key) return Result.success()
        return try { guide.dvr.recordings.observe(false); Result.success() } catch (_: CoreException) { Result.retry() }
    }
}
