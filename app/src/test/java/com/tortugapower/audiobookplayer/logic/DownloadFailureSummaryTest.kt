package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.logic.SyncStatusManager.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureSummaryTest {

    /** A volume's books on a server whose sign-in expired name it once; the other books share one line */
    @Test fun eachRejectingServerOnce_thenTheRestTogether() {
        val summary = DownloadFailureSummary.of(
            listOf(
                DownloadFailure("1", "Disc 1", expiredServer = "abs"),
                DownloadFailure("2", "Alpha"),
                DownloadFailure("3", "Disc 2", expiredServer = "abs"),
                DownloadFailure("4", "Bravo"),
                DownloadFailure("5", "Jelly", expiredServer = "jf"),
            )
        )

        assertEquals(listOf("abs", "jf"), summary.expiredServers)
        assertEquals(listOf("Alpha", "Bravo"), summary.incompleteTitles)
    }
}
