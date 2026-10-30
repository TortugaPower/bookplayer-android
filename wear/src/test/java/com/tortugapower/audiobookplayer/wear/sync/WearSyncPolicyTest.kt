package com.tortugapower.audiobookplayer.wear.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the watch builds its sync engine: only when there's sync (WearSyncPolicy) */
class WearSyncPolicyTest {

    @Test fun aProAccount_withTheAppOnScreen_runsIt() {
        assertTrue(WearSyncPolicy.runsEngine(pro = true, onScreen = true, queued = false))
    }

    /** A new task, or one a killed process left: picked up without the app being opened */
    @Test fun aProAccount_withTasksQueued_runsIt() {
        assertTrue(WearSyncPolicy.runsEngine(pro = true, onScreen = false, queued = true))
    }

    /** A tile or a complication started the process: nothing to sync */
    @Test fun aProAccount_offScreen_withNothingQueued_doesnt() {
        assertFalse(WearSyncPolicy.runsEngine(pro = true, onScreen = false, queued = false))
    }

    /** Any other account plays the phone's library: the watch syncs nothing */
    @Test fun anotherAccount_never() {
        assertFalse(WearSyncPolicy.runsEngine(pro = false, onScreen = true, queued = true))
    }
}
