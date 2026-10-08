package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.logic.SyncStatusManager.DownloadFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Dropped downloads wait until the app has shown them: a rotation or a screen away loses none */
class DownloadFailuresTest {

    @Before @After fun clear() = SyncStatusManager.dismissDownloadFailures(SyncStatusManager.downloadFailures.value)

    @Test fun failuresWaitUntilShown_andOnlyTheShownOnesAreCleared() {
        SyncStatusManager.notifyDownloadFailed(DownloadFailure("a", "Alpha"))
        SyncStatusManager.notifyDownloadFailed(DownloadFailure("b", "Bravo", expiredServer = "abs"))
        val shown = SyncStatusManager.downloadFailures.value
        assertEquals(listOf("Alpha", "Bravo"), shown.map { it.title })

        // One more lands while the alert is open
        SyncStatusManager.notifyDownloadFailed(DownloadFailure("c", "Charlie"))
        SyncStatusManager.dismissDownloadFailures(shown)

        assertEquals(listOf("Charlie"), SyncStatusManager.downloadFailures.value.map { it.title })

        // A second dismiss of the same alert (a double tap, back plus OK) can't clear what it never showed
        SyncStatusManager.dismissDownloadFailures(shown)
        assertEquals(listOf("Charlie"), SyncStatusManager.downloadFailures.value.map { it.title })
    }
}
