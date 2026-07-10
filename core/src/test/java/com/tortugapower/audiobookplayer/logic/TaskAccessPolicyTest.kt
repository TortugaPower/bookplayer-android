package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tier gating for S3-bound uploads: PRO-only for file, artwork, and the stream-to-cloud
 * pipe (LITE is DB-only sync — the engine DELETES a policy-restricted task, so a wrong `true` here
 * uploads a LITE user's file, and a wrong `false` silently discards a PRO pipe).
 */
class TaskAccessPolicyTest {

    private val uploadJobs = listOf(
        SyncTaskFactory.JOB_UPLOAD_FILE,
        SyncTaskFactory.JOB_UPLOAD_ARTWORK,
        SyncTaskFactory.JOB_UPLOAD_STREAM_FILE,
    )

    @Test fun `only PRO can run S3 upload jobs`() {
        uploadJobs.forEach { job ->
            assertTrue(job, TaskAccessPolicy.canExecuteTask(AccountTier.PRO, job))
            assertFalse(job, TaskAccessPolicy.canExecuteTask(AccountTier.LITE, job))
            assertFalse(job, TaskAccessPolicy.canExecuteTask(AccountTier.PLUS, job))
            assertFalse(job, TaskAccessPolicy.canExecuteTask(AccountTier.FREE, job))
            assertFalse(job, TaskAccessPolicy.canExecuteTask(null, job))
        }
    }

    @Test fun `remote streaming needs an active session (stale presigned URLs must not play)`() {
        assertTrue(TaskAccessPolicy.canStreamRemoteItems(AccountTier.PRO))
        assertTrue(TaskAccessPolicy.canStreamRemoteItems(AccountTier.LITE))
        assertFalse(TaskAccessPolicy.canStreamRemoteItems(AccountTier.PLUS))
        assertFalse(TaskAccessPolicy.canStreamRemoteItems(AccountTier.FREE))
        assertFalse("signed out must not stream remote items", TaskAccessPolicy.canStreamRemoteItems(null))
    }

    @Test fun `LITE keeps DB-only sync jobs`() {
        assertTrue(TaskAccessPolicy.canExecuteTask(AccountTier.LITE, SyncTaskFactory.JOB_UPLOAD_METADATA))
        assertTrue(TaskAccessPolicy.canExecuteTask(AccountTier.LITE, SyncTaskFactory.JOB_UPDATE))
        assertTrue(TaskAccessPolicy.canExecuteTask(AccountTier.LITE, SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE))
    }
}
