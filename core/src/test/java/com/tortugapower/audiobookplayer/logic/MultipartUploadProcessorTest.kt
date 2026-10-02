package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.network.CompleteUploadRequest
import com.tortugapower.audiobookplayer.network.PartUrl
import com.tortugapower.audiobookplayer.network.PartUrlsRequest
import com.tortugapower.audiobookplayer.network.PartUrlsResponse
import com.tortugapower.audiobookplayer.network.StartUploadRequest
import com.tortugapower.audiobookplayer.network.StartUploadResponse
import com.tortugapower.audiobookplayer.network.UploadApi
import com.tortugapower.audiobookplayer.network.UploadedPart
import com.tortugapower.audiobookplayer.network.UploadedPartsResponse
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The multipart engine against a scripted API and S3: iOS `MultipartUploadEngineTests`' cases */
class MultipartUploadProcessorTest {

    @get:Rule val folder = TemporaryFolder()

    private val uuid = "0b5c7a62-1f0e-4c39-9d7b-2a6e3f4d5c6b"

    /** Stores the task's payload; saveUploadState merges like the Room transaction does */
    private class PayloadRepository(var payload: String) : SyncTaskRepository {
        var gone = false
        override suspend fun saveUploadState(id: String, state: MultipartUploadState): Boolean {
            if (gone) return false
            payload = UploadFilePayload.withState(payload, state)
            return true
        }
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = error("unused")
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getActiveQueueKeys(): List<String> = error("unused")
        override suspend fun saveTask(task: SyncTaskEntity) = error("unused")
        override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
        override suspend fun deleteTask(task: SyncTaskEntity) = error("unused")
        override suspend fun clearCompletedTasks() = error("unused")
        override suspend fun resetRunningTasks() = error("unused")
        override suspend fun deleteAllTasks() = error("unused")
        override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
        override suspend fun countActiveTasks(): Int = error("unused")
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
        override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = error("unused")
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
    }

    /** A scripted API: each route answers from its queue, the last answer repeating */
    private inner class ScriptedApi : UploadApi {
        val starts = mutableListOf<StartUploadRequest>()
        val urlRequests = mutableListOf<List<Int>>()
        val completes = mutableListOf<CompleteUploadRequest>()
        var startAnswers = ArrayDeque(listOf<Response<StartUploadResponse>>(Response.success(StartUploadResponse("started", "up-1", 4))))
        var uploadedAnswers = ArrayDeque(listOf<Response<UploadedPartsResponse>>(Response.success(UploadedPartsResponse(emptyList()))))
        var completeAnswers = ArrayDeque(listOf<Response<Unit>>(Response.success(Unit)))
        var urlsAnswer: (List<Int>) -> Response<PartUrlsResponse> = { parts ->
            Response.success(PartUrlsResponse(parts.map { PartUrl(it, "https://s3/p$it?try=${urlRequests.size}") }))
        }

        private fun <T> ArrayDeque<T>.next(): T = if (size > 1) removeFirst() else first()

        override suspend fun start(body: StartUploadRequest): Response<StartUploadResponse> {
            starts += body
            return startAnswers.next()
        }

        override suspend fun partUrls(body: PartUrlsRequest): Response<PartUrlsResponse> {
            urlRequests += body.partNumbers
            return urlsAnswer(body.partNumbers)
        }

        override suspend fun uploadedParts(uuid: String, uploadId: String): Response<UploadedPartsResponse> = uploadedAnswers.next()

        override suspend fun complete(body: CompleteUploadRequest): Response<Unit> {
            completes += body
            return completeAnswers.next()
        }
    }

    private fun <T> coded(status: Int, code: String): Response<T> =
        Response.error(status, """{"message":"m","error":"$code"}""".toResponseBody())

    private fun file(size: Int): File = folder.newFile().apply { writeBytes(ByteArray(size) { it.toByte() }) }

    private fun task(payload: String = """{"uuid":"$uuid","relativePath":"Dune.m4b","remotePath":"https://s3/legacy"}""") =
        SyncTaskEntity(id = "row-1", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE, jobType = SyncTaskFactory.JOB_UPLOAD_FILE, position = 0, payload = payload)

    private class Sent(val url: String, val offset: Long, val length: Long)

    private fun processor(
        api: ScriptedApi,
        repository: PayloadRepository,
        bookFile: File?,
        hold: () -> Boolean = { false },
        put: suspend (Sent) -> Int = { 200 },
        progress: MutableList<Double> = mutableListOf(),
        holdCheckIntervalMs: Long = 5_000,
    ) = MultipartUploadProcessor(
        repository = repository,
        bookFile = { bookFile },
        holdUploads = { hold() },
        api = api,
        putPart = { url, _, offset, length, onBytes -> put(Sent(url, offset, length)).also { if (it in 200..299) onBytes(length) } },
        onProgress = { _, fraction -> synchronized(progress) { progress += fraction } },
        holdCheckIntervalMs = holdCheckIntervalMs,
    )

    @Test fun aFreshUpload_startsSendsEveryRangeAndCompletes() = runBlocking {
        val api = ScriptedApi()
        val repository = PayloadRepository(task().payload)
        val sent = mutableListOf<Sent>()
        val progress = mutableListOf<Double>()

        val done = processor(api, repository, file(10), put = { synchronized(sent) { sent += it }; 200 }, progress = progress).process(task())

        assertTrue(done)
        assertEquals(listOf(StartUploadRequest(uuid, 10, MultipartUpload.REQUESTED_PART_SIZE)), api.starts)
        assertEquals(listOf(0L to 4L, 4L to 4L, 8L to 2L), sent.map { it.offset to it.length }.sortedBy { it.first })
        assertEquals(listOf(CompleteUploadRequest(uuid, "up-1", 3, 10)), api.completes)
        assertEquals(MultipartUploadState("up-1", 4, 10, 0), UploadFilePayload.state(repository.payload))
        assertFalse("an older build's presigned URL is dropped", repository.payload.contains("remotePath"))
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test fun aHeldUpload_resumesFromS3sList_withoutStarting() = runBlocking {
        val api = ScriptedApi().apply {
            uploadedAnswers = ArrayDeque(listOf(Response.success(UploadedPartsResponse(listOf(UploadedPart(1, 4), UploadedPart(3, 2))))))
        }
        val payload = UploadFilePayload.withState(task().payload, MultipartUploadState("up-9", 4, 10, 1))
        val repository = PayloadRepository(payload)
        val sent = mutableListOf<Sent>()

        assertTrue(processor(api, repository, file(10), put = { sent += it; 200 }).process(task(payload)))

        assertTrue(api.starts.isEmpty())
        assertEquals(listOf(4L), sent.map { it.offset })
        assertEquals(listOf(CompleteUploadRequest(uuid, "up-9", 3, 10)), api.completes)
    }

    @Test fun aBookS3AlreadyHolds_isDone_withNoParts() = runBlocking {
        val api = ScriptedApi().apply { startAnswers = ArrayDeque(listOf(Response.success(StartUploadResponse("exists", null, null)))) }
        assertTrue(processor(api, PayloadRepository(task().payload), file(10), put = { fail("no parts"); 0 }).process(task()))
        assertTrue(api.completes.isEmpty())
    }

    @Test fun noBook_noFile_orAnEmptyFile_isDropped_beforeAnyCall() = runBlocking {
        val api = ScriptedApi()
        val repository = PayloadRepository(task().payload)
        assertTrue(processor(api, repository, file(10)).process(task("""{"relativePath":"x"}""")))
        assertTrue(processor(api, repository, null).process(task()))
        assertTrue(processor(api, repository, file(0)).process(task()))
        assertTrue(api.starts.isEmpty())
    }

    @Test fun aBookOverTheLimit_failsWithFileTooLarge_beforeAnyCall() = runBlocking {
        val api = ScriptedApi()
        val huge = object : File(folder.root, "huge.m4b") {
            override fun length(): Long = MultipartUpload.MAX_FILE_SIZE + 1
        }
        val failure = runCatching { processor(api, PayloadRepository(task().payload), huge).process(task()) }.exceptionOrNull()
        assertEquals(SyncFailurePolicy.FILE_TOO_LARGE, (failure as CodedFailureException).failure.code)
        assertNull(failure.failure.httpStatus)
        assertTrue(api.starts.isEmpty())
    }

    @Test fun aFileThatChangedSize_startsOver_withoutCountingARestart() = runBlocking {
        val api = ScriptedApi()
        val payload = UploadFilePayload.withState(task().payload, MultipartUploadState("old", 4, 99, 2))
        val repository = PayloadRepository(payload)

        assertTrue(processor(api, repository, file(10)).process(task(payload)))

        assertEquals(1, api.starts.size)
        assertEquals(2, UploadFilePayload.state(repository.payload).restartCount)
    }

    @Test fun aRefusedPart_isResentWithAFreshUrl() = runBlocking {
        val api = ScriptedApi()
        val refusedOnce = AtomicInteger(0)
        val urls = mutableListOf<String>()
        val put: suspend (Sent) -> Int = { sent ->
            synchronized(urls) { urls += sent.url }
            if (sent.offset == 0L && refusedOnce.getAndIncrement() == 0) 403 else 200
        }

        assertTrue(processor(api, PayloadRepository(task().payload), file(10), put = put).process(task()))

        assertEquals(2, urls.count { it.startsWith("https://s3/p1?") })
        assertEquals("part 1 asked for again", 2, api.urlRequests.count { 1 in it })
    }

    @Test fun aPartRefusedFiveTimes_endsTheRunForAPlainRetry() = runBlocking {
        val api = ScriptedApi()
        val failure = runCatching {
            processor(api, PayloadRepository(task().payload), file(10), put = { if (it.offset == 0L) 403 else 200 }).process(task())
        }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        assertTrue(api.completes.isEmpty())
    }

    /** S3 no longer has the upload: start over, counted */
    @Test fun aPartsNotFound_restartsTheUpload() = runBlocking {
        val api = ScriptedApi().apply {
            startAnswers = ArrayDeque(listOf(Response.success(StartUploadResponse("started", "up-1", 4)), Response.success(StartUploadResponse("started", "up-2", 4))))
        }
        val repository = PayloadRepository(task().payload)
        val gone = AtomicInteger(0)

        val done = processor(api, repository, file(10), put = { if (gone.getAndIncrement() == 0) 404 else 200 }).process(task())

        assertTrue(done)
        assertEquals(2, api.starts.size)
        assertEquals(MultipartUploadState("up-2", 4, 10, 1), UploadFilePayload.state(repository.payload))
        assertEquals("up-2", api.completes.single().uploadId)
    }

    @Test fun aListingThatSaysTheUploadIsGone_restartsToo() = runBlocking {
        val api = ScriptedApi().apply { uploadedAnswers = ArrayDeque(listOf<Response<UploadedPartsResponse>>(coded(409, "upload_not_found"), Response.success(UploadedPartsResponse(emptyList())))) }
        val payload = UploadFilePayload.withState(task().payload, MultipartUploadState("stale", 4, 10, 0))
        val repository = PayloadRepository(payload)

        assertTrue(processor(api, repository, file(10)).process(task(payload)))
        assertEquals(1, api.starts.size)
        assertEquals(1, UploadFilePayload.state(repository.payload).restartCount)
    }

    /** parts_missing: another pass over S3's list, not a restart, until it keeps happening */
    @Test fun partsMissing_isAnotherPass_thenARestartAfterFiveRounds() = runBlocking {
        val oneMissing = ScriptedApi().apply { completeAnswers = ArrayDeque(listOf<Response<Unit>>(coded(409, "parts_missing"), Response.success(Unit))) }
        assertTrue(processor(oneMissing, PayloadRepository(task().payload), file(10)).process(task()))
        assertEquals(1, oneMissing.starts.size)
        assertEquals(2, oneMissing.completes.size)

        val alwaysMissing = ScriptedApi().apply {
            completeAnswers = ArrayDeque(List<Response<Unit>>(MultipartUpload.PARTS_MISSING_ROUNDS) { coded(409, "parts_missing") } + Response.success(Unit))
        }
        val repository = PayloadRepository(task().payload)
        assertTrue(processor(alwaysMissing, repository, file(10)).process(task()))
        assertEquals("restarted once", 2, alwaysMissing.starts.size)
        assertEquals(1, UploadFilePayload.state(repository.payload).restartCount)
    }

    /** Past the budget the upload is forgotten with a fresh count, and the server's code parks it */
    @Test fun anExhaustedRestartBudget_forgetsTheUpload_andFailsWithTheServersCode() = runBlocking {
        val api = ScriptedApi().apply { // Fresh answers: an error body can only be read once
            completeAnswers = ArrayDeque(List<Response<Unit>>(MultipartUpload.RESTART_BUDGET + 1) { coded(422, "invalid_parts") }) }
        val repository = PayloadRepository(task().payload)

        val failure = runCatching { processor(api, repository, file(10)).process(task()) }.exceptionOrNull()

        assertEquals("invalid_parts", (failure as CodedFailureException).failure.code)
        assertEquals("1 start + 3 restarts", MultipartUpload.RESTART_BUDGET + 1, api.starts.size)
        val state = UploadFilePayload.state(repository.payload)
        assertNull(state.uploadId)
        assertEquals(0, state.restartCount)
    }

    @Test fun anotherCodedAnswer_reachesTheEngine_andAnUncodedOneRetries() = runBlocking {
        val notFound = ScriptedApi().apply { startAnswers = ArrayDeque(listOf<Response<StartUploadResponse>>(coded(404, "item_not_found"))) }
        val failure = runCatching { processor(notFound, PayloadRepository(task().payload), file(10)).process(task()) }.exceptionOrNull()
        assertEquals("item_not_found", (failure as CodedFailureException).failure.code)

        val serverError = ScriptedApi().apply { urlsAnswer = { Response.error(500, "".toResponseBody()) } }
        val retry = runCatching { processor(serverError, PayloadRepository(task().payload), file(10)).process(task()) }.exceptionOrNull()
        assertTrue("$retry", retry is IOException)
    }

    /** Held to Wi-Fi: no new part starts, the in-flight ones are cancelled, and the task goes back waiting */
    @Test fun heldToWifi_givesTheTaskBack_andCancelsItsParts() = runBlocking {
        val api = ScriptedApi()
        var holds = false
        val cancelled = AtomicInteger(0)
        val started = AtomicInteger(0)
        val put: suspend (Sent) -> Int = { sent ->
            if (sent.offset == 0L) 200.also { holds = true } else {
                started.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
        }

        val held = runCatching { processor(api, PayloadRepository(task().payload), file(12), hold = { holds }, put = put).process(task()) }

        assertTrue("${held.exceptionOrNull()}", held.exceptionOrNull() is UploadsHeldException)
        assertEquals(started.get(), cancelled.get())
        assertTrue(api.completes.isEmpty())
    }

    /** The network can turn metered mid-part: the periodic check notices with every part still in flight */
    @Test fun aHoldDuringLongParts_isNoticedByTheTick() = runBlocking {
        val api = ScriptedApi()
        var holds = false
        val put: suspend (Sent) -> Int = {
            holds = true
            awaitCancellation()
        }

        val held = runCatching {
            processor(api, PayloadRepository(task().payload), file(10), hold = { holds }, put = put, holdCheckIntervalMs = 20).process(task())
        }

        assertTrue("${held.exceptionOrNull()}", held.exceptionOrNull() is UploadsHeldException)
    }

    @Test fun aTaskClearedMidUpload_stops() = runBlocking {
        val api = ScriptedApi()
        val repository = PayloadRepository(task().payload).apply { gone = true }
        assertTrue(processor(api, repository, file(10), put = { fail("no parts once the task is gone"); 0 }).process(task()))
    }

    @Test fun neverMoreThanTheWindowInFlight() = runBlocking {
        val api = ScriptedApi().apply { startAnswers = ArrayDeque(listOf(Response.success(StartUploadResponse("started", "up-1", 1)))) }
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val put: suspend (Sent) -> Int = {
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            kotlinx.coroutines.delay(5)
            active.decrementAndGet()
            200
        }

        assertTrue(processor(api, PayloadRepository(task().payload), file(20), put = put).process(task()))
        assertTrue("peak ${peak.get()}", peak.get() <= MultipartUpload.WINDOW)
        assertEquals(20, api.completes.single().partCount)
    }
}
