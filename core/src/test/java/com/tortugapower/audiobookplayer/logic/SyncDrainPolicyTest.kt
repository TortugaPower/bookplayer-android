package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the stored queue needs a drain, and on which network (SyncDrainPolicy) */
class SyncDrainPolicyTest {

    private val now = 1_000_000L
    private val task = SyncTaskEntity(id = "t", taskID = "b", queueKey = "sync", jobType = SyncTaskFactory.JOB_UPDATE, position = 0, payload = "{}")

    @Test fun `a pick runs now, at the end of its wait, or never`() {
        assertEquals(now, SyncDrainPolicy.dueAt(LanePick.Run(task), now))
        assertEquals(now + 60_000, SyncDrainPolicy.dueAt(LanePick.Wait(now + 60_000), now))
        assertNull(SyncDrainPolicy.dueAt(LanePick.Idle, now))
    }

    @Test fun `nothing queued needs no drain`() {
        assertEquals(SyncDrainPolicy.Needs.NONE, SyncDrainPolicy.needs(emptyList(), cellularUploads = false))
        assertEquals(SyncDrainPolicy.Needs.NONE, SyncDrainPolicy.needs(listOf(SyncDrainPolicy.LaneDue(null, null)), cellularUploads = false))
    }

    /** The earliest lane decides */
    @Test fun `any network runs the work due first`() {
        val lanes = listOf(SyncDrainPolicy.LaneDue(now + 60_000, now + 60_000), SyncDrainPolicy.LaneDue(now + 5_000, now + 5_000))

        assertEquals(SyncDrainPolicy.Needs(now + 5_000, null), SyncDrainPolicy.needs(lanes, cellularUploads = false))
    }

    /** Uploads held for Wi-Fi are due now, the rest of the queue only later: a drain waits for Wi-Fi for them */
    @Test fun `uploads held for Wi-Fi get a drain that waits for it`() {
        val uploads = SyncDrainPolicy.LaneDue(any = now, withoutUploads = null)
        val sync = SyncDrainPolicy.LaneDue(any = now + 3_600_000, withoutUploads = now + 3_600_000)

        assertEquals(SyncDrainPolicy.Needs(now + 3_600_000, now), SyncDrainPolicy.needs(listOf(uploads, sync), cellularUploads = false))
        assertEquals("only uploads", SyncDrainPolicy.Needs(null, now), SyncDrainPolicy.needs(listOf(uploads), cellularUploads = false))
    }

    /** A drain on any network due first covers the uploads too, once it runs on Wi-Fi; it reschedules what it leaves */
    @Test fun `uploads due after the rest need no drain of their own`() {
        val lanes = listOf(SyncDrainPolicy.LaneDue(any = now + 60_000, withoutUploads = null), SyncDrainPolicy.LaneDue(now, now))

        assertEquals(SyncDrainPolicy.Needs(now, null), SyncDrainPolicy.needs(lanes, cellularUploads = false))
    }

    @Test fun `with cellular uploads allowed, any network runs them`() {
        val uploads = SyncDrainPolicy.LaneDue(any = now, withoutUploads = null)

        assertEquals(SyncDrainPolicy.Needs(now, null), SyncDrainPolicy.needs(listOf(uploads), cellularUploads = true))
    }
}
