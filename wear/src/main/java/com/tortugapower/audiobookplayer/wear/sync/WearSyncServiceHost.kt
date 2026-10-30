package com.tortugapower.audiobookplayer.wear.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tortugapower.audiobookplayer.logic.SyncKeepAliveService
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity

/**
 * The watch's sync keep-alive (SyncKeepAliveService): its downloads keep running once the app leaves the screen. Its
 * notification is deliberately minimal (`IMPORTANCE_MIN`, static text): the watch doesn't surface what it transfers.
 */
class WearSyncServiceHost : SyncKeepAliveService() {

    override fun notification(transfers: Set<String>): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wear_sync_notification_title))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN: the FGS's required notification stays as unobtrusive as possible on the watch.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wear_sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = getString(R.string.wear_sync_channel_desc) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wear_task_concurrency_channel"

        fun start(context: Context) = SyncKeepAliveService.start(context, WearSyncServiceHost::class.java)
    }
}
