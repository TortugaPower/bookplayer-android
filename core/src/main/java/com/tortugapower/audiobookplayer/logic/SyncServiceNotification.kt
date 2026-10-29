package com.tortugapower.audiobookplayer.logic

/** The sync service's notification: the phone's TaskConcurrencyServiceHost and the watch's WearSyncServiceHost */
object SyncServiceNotification {
    /**
     * Never playback's: media3 posts its notification as DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
     * (1001), and an app's notifications with one id are one notification. Sharing it, playback's notification covered
     * the sync notice, and the sync service stopping removed a paused book's notification along with its own.
     */
    const val ID = 1002
}
