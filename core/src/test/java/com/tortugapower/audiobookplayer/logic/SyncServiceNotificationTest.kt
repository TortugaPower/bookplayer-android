package com.tortugapower.audiobookplayer.logic

import androidx.media3.session.DefaultMediaNotificationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncServiceNotificationTest {

    /** Playback's notification keeps media3's id on the phone and the watch: the sync service's must be another */
    @Test fun itsId_isNotPlaybacks() {
        assertNotEquals(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, SyncServiceNotification.ID)
    }
}
