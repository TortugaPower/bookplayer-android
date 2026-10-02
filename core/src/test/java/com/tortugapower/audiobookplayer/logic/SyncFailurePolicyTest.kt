package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SyncFailurePolicyTest {

    private fun coded(code: String) = CodedFailureException(CodedFailure(code, "message", 404))

    @Test fun anUncodedFailure_retries() {
        assertEquals(SyncFailureAction.Retry, SyncFailurePolicy.action(IOException("timeout"), SyncTaskFactory.JOB_MOVE, parkingEnabled = true))
        assertEquals(SyncFailureAction.Retry, SyncFailurePolicy.action(null, SyncTaskFactory.JOB_MOVE, parkingEnabled = true))
    }

    @Test fun aCodedFailure_parksAStructuralTaskWithItsLane() {
        listOf(
            SyncTaskFactory.JOB_UPLOAD_METADATA, SyncTaskFactory.JOB_MOVE, SyncTaskFactory.JOB_RENAME_FOLDER,
            SyncTaskFactory.JOB_DELETE, SyncTaskFactory.JOB_DELETE_SHALLOW, SyncTaskFactory.JOB_SET_BOOKMARK,
            SyncTaskFactory.JOB_DELETE_BOOKMARK, SyncTaskFactory.JOB_MATCH_UUIDS,
            SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE, SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE,
            SyncTaskFactory.JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD,
        ).forEach {
            assertEquals(it, SyncFailureAction.Park(TaskPauseScope.LANE), SyncFailurePolicy.action(coded("item_not_found"), it, parkingEnabled = true))
        }
    }

    /** Leaf tasks park alone, Android-only jobs and unknown job types included (pulls are dropped, below) */
    @Test fun aCodedFailure_parksALeafTaskAlone() {
        listOf(
            SyncTaskFactory.JOB_UPDATE, SyncTaskFactory.JOB_UPLOAD_ARTWORK, SyncTaskFactory.JOB_UPLOAD_FILE,
            SyncTaskFactory.JOB_DOWNLOAD_FILE, SyncTaskFactory.JOB_SYNC_IDENTIFIERS,
            SyncTaskFactory.JOB_UPLOAD_PREFERENCE,
            SyncTaskFactory.JOB_UPLOAD_STREAM_FILE, "some_future_job",
        ).forEach {
            assertEquals(it, SyncFailureAction.Park(TaskPauseScope.TASK), SyncFailurePolicy.action(coded("invalid_request"), it, parkingEnabled = true))
        }
    }

    @Test fun anAccountCode_verifiesTheAccount_onEveryServerJob_parkingOrNot() {
        listOf("not_subscribed", "tier_required").forEach { code ->
            assertEquals(SyncFailureAction.VerifyAccount, SyncFailurePolicy.action(coded(code), SyncTaskFactory.JOB_MOVE, parkingEnabled = true))
            assertEquals(SyncFailureAction.VerifyAccount, SyncFailurePolicy.action(coded(code), SyncTaskFactory.JOB_UPLOAD_FILE, parkingEnabled = false))
        }
    }

    /** Media-server and Hardcover jobs never call the BookPlayer API: no park, no account pause */
    @Test fun aJobThatNeverCallsTheApi_keepsItsOwnHandling() {
        listOf(
            SyncTaskFactory.JOB_EXTERNAL_UPDATE, SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH,
            SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS,
        ).forEach {
            assertEquals(it, SyncFailureAction.Retry, SyncFailurePolicy.action(coded("not_subscribed"), it, parkingEnabled = true))
        }
    }

    /** The watch has no Queued Tasks screen to show or retry a parked task */
    @Test fun withParkingDisabled_aCodedFailureIsDropped() {
        assertEquals(SyncFailureAction.Drop, SyncFailurePolicy.action(coded("item_not_found"), SyncTaskFactory.JOB_MOVE, parkingEnabled = false))
    }

    /** The API's message embeds file names: it stays on the failure, out of the exception's message */
    @Test fun theExceptionMessage_carriesOnlyTheCodeAndStatus() {
        val e = CodedFailureException(CodedFailure("item_not_found", "Item not found: \"My Book.m4b\"", 404))
        assertEquals("Coded failure item_not_found (HTTP 404)", e.message)
        assertEquals("Item not found: \"My Book.m4b\"", e.failure.message)
    }

    /** A rejected pull keeps nothing and would hold every later refresh: dropped, like a failed iOS listing */
    @Test fun aRejectedPull_isDropped_butAnAccountRejectionStillVerifies() {
        listOf(SyncTaskFactory.JOB_FETCH_CONTENTS, SyncTaskFactory.JOB_FETCH_PREFERENCES).forEach { job ->
            assertEquals(job, SyncFailureAction.Drop, SyncFailurePolicy.action(coded("invalid_request"), job, parkingEnabled = true))
            assertEquals(job, SyncFailureAction.VerifyAccount, SyncFailurePolicy.action(coded("not_subscribed"), job, parkingEnabled = true))
        }
    }
}
