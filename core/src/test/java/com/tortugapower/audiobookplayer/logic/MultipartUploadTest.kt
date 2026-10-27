package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces of the multipart upload: the part plan, the saved state, what a part's answer means */
class MultipartUploadTest {

    private val mib = 1024L * 1024

    @Test fun thePlan_coversTheFileInPartSizedRanges_theLastOneShorter() {
        val plan = MultipartUploadPlan(fileSize = 150 * mib, partSize = 64 * mib)

        assertEquals(3, plan.partCount)
        assertEquals(listOf(0L, 64 * mib, 128 * mib), (1..3).map { plan.offset(it) })
        assertEquals(listOf(64 * mib, 64 * mib, 22 * mib), (1..3).map { plan.length(it) })
        assertEquals(150 * mib, plan.bytes(listOf(1, 2, 3)))
    }

    @Test fun anExactMultiple_hasNoEmptyTrailingPart() {
        val plan = MultipartUploadPlan(fileSize = 128 * mib, partSize = 64 * mib)
        assertEquals(2, plan.partCount)
        assertEquals(64 * mib, plan.length(2))
    }

    @Test fun aFileSmallerThanAPart_isOnePart() {
        val plan = MultipartUploadPlan(fileSize = 10, partSize = 64 * mib)
        assertEquals(1, plan.partCount)
        assertEquals(10L, plan.length(1))
    }

    @Test fun pendingParts_areTheOnesS3DoesntHold_inOrder() {
        val plan = MultipartUploadPlan(fileSize = 5 * mib, partSize = mib)
        assertEquals(listOf(2, 4, 5), plan.pendingParts(setOf(1, 3)))
    }

    @Test fun theLargestBook_fitsTheApisPartLimit() {
        assertEquals(160, MultipartUploadPlan(MultipartUpload.MAX_FILE_SIZE, MultipartUpload.REQUESTED_PART_SIZE).partCount)
    }

    @Test fun theState_roundTripsThroughThePayload_keepingTheRest_andDroppingAnOldUrl() {
        val original = """{"uuid":"book","title":"Dune","relativePath":"Dune.m4b","remotePath":"https://s3/old"}"""
        val state = MultipartUploadState(uploadId = "u1", partSize = 64 * mib, fileSize = 150 * mib, restartCount = 2)

        val written = UploadFilePayload.withState(original, state)
        val json = Gson().fromJson(written, Map::class.java)

        assertEquals(state, UploadFilePayload.state(written))
        assertEquals("book", UploadFilePayload.uuid(written))
        assertEquals("Dune", json["title"])
        assertFalse(json.containsKey("remotePath"))
    }

    @Test fun anOlderTasksPayload_readsAsNoUploadYet() {
        val legacy = """{"uuid":"book","relativePath":"Dune.m4b","remotePath":"https://s3/old"}"""
        assertEquals(MultipartUploadState(), UploadFilePayload.state(legacy))
        assertNull(UploadFilePayload.state(legacy).uploadId)
        assertNull(UploadFilePayload.uuid("not json"))
    }

    @Test fun forgettingTheUpload_clearsTheId_andCountsARestartOnlyWhenAsked() {
        val state = MultipartUploadState(uploadId = "u1", partSize = 1, fileSize = 1, restartCount = 1)
        assertEquals(MultipartUploadState(null, 1, 1, 2), state.forgotten(countsAsRestart = true))
        assertEquals(MultipartUploadState(null, 1, 1, 1), state.forgotten(countsAsRestart = false))
        assertNull(UploadFilePayload.state(UploadFilePayload.withState("{}", state.forgotten(true))).uploadId)
    }

    @Test fun aHeldUpload_isStaleWhenTheFileChangedSize_orItsPartSizeIsUnusable() {
        val held = MultipartUploadState(uploadId = "u1", partSize = 64 * mib, fileSize = 100)
        assertFalse(held.isStaleFor(100))
        assertTrue(held.isStaleFor(101))
        assertTrue(held.copy(partSize = 0).isStaleFor(100))
        assertFalse("nothing held, nothing stale", MultipartUploadState().isStaleFor(5))
    }

    @Test fun aPartsAnswer_mapsLikeIos() {
        assertEquals(PartOutcome.UPLOADED, PartOutcome.of(200))
        assertEquals(PartOutcome.RESEND, PartOutcome.of(403))
        assertEquals(PartOutcome.UPLOAD_GONE, PartOutcome.of(404))
        listOf(400, 500, 503, null).forEach { assertEquals("$it", PartOutcome.FAILED, PartOutcome.of(it)) }
    }
}
