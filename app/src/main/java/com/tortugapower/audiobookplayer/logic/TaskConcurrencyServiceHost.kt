package com.tortugapower.audiobookplayer.logic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.sentry.Breadcrumb
import io.sentry.Sentry
import androidx.core.app.NotificationCompat
import androidx.annotation.StringRes
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R

/**
 * The phone's sync keep-alive (SyncKeepAliveService): its notification says what it keeps alive, downloads or uploads.
 */
class TaskConcurrencyServiceHost : SyncKeepAliveService() {

    companion object {
        private const val CHANNEL_ID = "task_concurrency_channel"

        /**
         * The notification's text for the transfer lanes kept alive. With none it keeps the text [shown]: the app
         * back on screen needs no service, but its download may well still run, for the minute before it stops.
         */
        @StringRes
        internal fun noticeFor(transfers: Set<String>, @StringRes shown: Int? = null): Int {
            val downloading = SyncTaskFactory.QUEUE_FILE in transfers
            val uploading = SyncTaskFactory.QUEUE_UPLOAD in transfers
            return when {
                downloading && uploading -> R.string.sync_notification_transferring
                downloading -> R.string.sync_notification_downloading
                uploading -> R.string.sync_notification_uploading
                else -> shown ?: R.string.sync_notification_syncing
            }
        }

        fun start(context: Context) = SyncKeepAliveService.start(context, TaskConcurrencyServiceHost::class.java)
    }

    @StringRes
    private var shown: Int? = null

    override fun notification(transfers: Set<String>): Notification {
        val text = noticeFor(transfers, shown).also { shown = it }
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.sync_notification_channel), NotificationManager.IMPORTANCE_LOW,
            )
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Breadcrumbs, not logs: "Bad notification for startForeground" (Sentry ANDROID-BOOKPLAYER-1E) carries no cause on
    // Android 12+, so the crash has to say which service was promoting
    override fun onPromoting() {
        Sentry.addBreadcrumb(Breadcrumb.info("promote TaskConcurrencyServiceHost").apply { category = "fgs" })
    }

    override fun onPromotionDenied(e: IllegalStateException) {
        Sentry.addBreadcrumb(Breadcrumb.info("promotion denied: ${e.message}").apply { category = "fgs" })
    }
}
