package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.network.ApiError
import com.tortugapower.audiobookplayer.network.CompleteUploadRequest
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.network.PartUrlsRequest
import com.tortugapower.audiobookplayer.network.S3Transfer
import com.tortugapower.audiobookplayer.network.StartUploadRequest
import com.tortugapower.audiobookplayer.network.StartUploadResponse
import com.tortugapower.audiobookplayer.network.UploadApi
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Uploads a book's file to S3 as a multipart upload through the BookPlayer API (iOS
 * `FileUploadOperation`, bookplayer-api `docs/multipart-uploads.md`). The task holds only the book's
 * uuid and the upload's [MultipartUploadState]; the file is found by uuid on every run, so a book moved
 * or renamed before its upload still goes up. S3's part list is the truth: a run resumes from it, never
 * from memory, and sends only the parts S3 doesn't hold, [MultipartUpload.WINDOW] at a time, each with
 * a URL asked for just before it goes (a URL can die with its signing credentials).
 *
 * Failures, as on iOS:
 * - S3 lost or refused the upload (a part's 404, `upload_not_found`, `invalid_parts`, or
 *   `parts_missing` [MultipartUpload.PARTS_MISSING_ROUNDS] times in a row): start over, up to
 *   [MultipartUpload.RESTART_BUDGET] times across retries and launches; past that the upload is
 *   forgotten and the server's code parks the task.
 * - A part refused or failing [MultipartUpload.PART_ATTEMPTS] times in one run, a 5xx, or no network:
 *   a plain retry, which resumes from S3's list.
 * - `item_not_found`: the server lost the book, so it's registered again ([UploadHandBack]), once per
 *   book per process, and this task ends; the registration queues a new upload.
 * - Any other coded answer reaches the engine, which parks the task (or verifies the account).
 * - Uploads held to Wi-Fi (checked after every part and every few seconds while parts are in flight,
 *   since the network can change mid-part): the in-flight parts are cancelled and [UploadsHeldException]
 *   gives the task back, waiting rather than failed; the picker then holds it.
 */
class MultipartUploadProcessor(
    private val repository: SyncTaskRepository,
    /** The book's file on this device, or null when it's gone or offloaded */
    private val bookFile: suspend (uuid: String) -> File?,
    /** True while file uploads must wait for Wi-Fi ([UploadDataPolicy]) */
    private val holdUploads: suspend () -> Boolean,
    private val api: UploadApi = NetworkClient.uploadApi,
    private val putPart: suspend (url: String, file: File, offset: Long, length: Long, onBytesSent: (Long) -> Unit) -> Int =
        S3Transfer::putPart,
    private val onProgress: (taskId: String, fraction: Double) -> Unit = SyncStatusManager::updateTaskProgress,
    /** How often the Wi-Fi hold is checked while waiting on parts in flight */
    private val holdCheckIntervalMs: Long = 5_000,
    /** Registers the book again after `item_not_found` ([UploadHandBack.reRegister]); false when it's gone */
    private val reRegister: suspend (uuid: String) -> Boolean = { false },
) : TaskProcessor {

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_UPLOAD_FILE

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val uuid = UploadFilePayload.uuid(task.payload) ?: return dropped(task, "it names no book")
        return try {
            upload(task, uuid)
        } catch (e: CodedFailureException) {
            if (e.failure.code != ITEM_NOT_FOUND || !UploadHandBack.claim(uuid)) throw e
            // Ends this task either way: the registration's answer queues a fresh upload, or the book is
            // gone on this device too and there's nothing left to upload
            if (reRegister(uuid)) {
                Log.w(TAG, "Upload ${task.id}: the server lost the book, registering it again")
            } else {
                Log.w(TAG, "Dropping upload ${task.id}: the book is gone on the server and on this device")
            }
            true
        }
    }

    private suspend fun upload(task: SyncTaskEntity, uuid: String): Boolean {
        val file = bookFile(uuid) ?: return dropped(task, "the book has no file on this device")
        val fileSize = file.length()
        // The API refuses an empty file, and there's nothing to back up
        if (fileSize == 0L) return dropped(task, "the file is empty")
        // Checked on every run, before any server call: the API's limit, and the app's own refusal
        if (fileSize > MultipartUpload.MAX_FILE_SIZE) {
            throw CodedFailureException(CodedFailure(SyncFailurePolicy.FILE_TOO_LARGE, TOO_LARGE_MESSAGE, null))
        }

        var state = UploadFilePayload.state(task.payload)
        if (state.isStaleFor(fileSize)) {
            Log.w(TAG, "Upload ${task.id}: the file changed since the upload started, starting over")
            state = state.forgotten(countsAsRestart = false)
            if (!repository.saveUploadState(task.id, state)) return gone(task)
        }

        var partsMissingRounds = 0
        while (true) {
            val uploadId = state.uploadId ?: when (val started = start(uuid, fileSize)) {
                // S3 already holds the book; the server marked it synced
                Started.Exists -> return true
                is Started.New -> {
                    state = state.copy(uploadId = started.uploadId, partSize = started.partSize, fileSize = fileSize)
                    if (!repository.saveUploadState(task.id, state)) return gone(task)
                    partsMissingRounds = 0
                    started.uploadId
                }
            }
            val plan = MultipartUploadPlan(state.fileSize, state.partSize)

            val restartCause: CodedFailure = when (val sent = sendParts(task.id, uuid, uploadId, plan, file)) {
                Sent.Held -> throw UploadsHeldException()
                is Sent.Restart -> sent.cause
                Sent.Done -> when (val completed = complete(uuid, uploadId, plan.partCount, state.fileSize)) {
                    Completed.Done -> return true
                    // A part S3 never kept: another pass resends the gaps, which isn't a restart until
                    // it keeps happening
                    is Completed.PartsMissing -> {
                        if (++partsMissingRounds < MultipartUpload.PARTS_MISSING_ROUNDS) continue
                        completed.cause
                    }
                    is Completed.Restart -> completed.cause
                }
            }
            state = restart(task, state, restartCause) ?: return gone(task)
            partsMissingRounds = 0
        }
    }

    /**
     * Forgets the upload so the next pass starts a new one, counting against the budget. Past the budget
     * the upload is forgotten with a fresh count and the server's code parks the task: a Retry then
     * starts clean. Null when the task is gone.
     */
    private suspend fun restart(task: SyncTaskEntity, state: MultipartUploadState, cause: CodedFailure): MultipartUploadState? {
        if (state.restartCount < MultipartUpload.RESTART_BUDGET) {
            Log.w(TAG, "Upload ${task.id}: starting over after ${cause.code} (restart ${state.restartCount + 1})")
            val restarted = state.forgotten(countsAsRestart = true)
            return restarted.takeIf { repository.saveUploadState(task.id, it) }
        }
        val fresh = state.copy(uploadId = null, restartCount = 0)
        if (!repository.saveUploadState(task.id, fresh)) return null
        throw CodedFailureException(cause)
    }

    /** Sends every part S3 doesn't hold, [MultipartUpload.WINDOW] at a time, until S3 holds them all */
    private suspend fun sendParts(taskId: String, uuid: String, uploadId: String, plan: MultipartUploadPlan, file: File): Sent =
        coroutineScope {
            val done = when (val uploaded = uploadedParts(uuid, uploadId)) {
                is Uploaded.Parts -> uploaded.parts.filterTo(mutableSetOf()) { it in 1..plan.partCount }
                is Uploaded.Gone -> return@coroutineScope Sent.Restart(uploaded.cause)
            }
            val progress = UploadProgress(taskId, plan, plan.bytes(done))
            val inFlight = HashMap<Int, Job>()
            val events = Channel<PartEvent>(Channel.UNLIMITED)
            // Wakes the loop to re-check the Wi-Fi hold while parts are in flight. A tick in the same
            // channel rather than a timeout on the receive: a cancelled receive can lose a part's result
            // that was being handed over, and that part would hold a window slot forever
            val ticker = launch {
                while (true) {
                    delay(holdCheckIntervalMs)
                    events.send(PartEvent.Tick)
                }
            }
            // Per part, for this run only
            val refusals = HashMap<Int, Int>()
            val failures = HashMap<Int, Int>()
            try {
                progress.report()
                while (done.size < plan.partCount) {
                    // After every part and every few seconds of waiting: the finally cancels the parts
                    // in flight, so none goes on over a metered network
                    if (holdUploads()) return@coroutineScope Sent.Held
                    val toStart = plan.pendingParts(done).filter { it !in inFlight }.take(MultipartUpload.WINDOW - inFlight.size)
                    if (toStart.isNotEmpty()) {
                        val urls = partUrls(uuid, uploadId, toStart)
                        for (part in toStart) {
                            val url = urls[part] ?: throw IOException("The API sent no URL for part $part")
                            inFlight[part] = launch {
                                val status = try {
                                    putPart(url, file, plan.offset(part), plan.length(part)) { sent -> progress.sending(part, sent) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                events.send(PartEvent.Finished(part, status))
                            }
                        }
                    }
                    val (part, status) = events.receive() as? PartEvent.Finished ?: continue
                    inFlight.remove(part)
                    progress.stopped(part)
                    when (PartOutcome.of(status)) {
                        PartOutcome.UPLOADED -> {
                            done += part
                            progress.uploaded(part)
                        }
                        PartOutcome.RESEND -> if (refusals.count(part) >= MultipartUpload.PART_ATTEMPTS) {
                            throw IOException("Part $part keeps being refused")
                        }
                        PartOutcome.UPLOAD_GONE -> return@coroutineScope Sent.Restart(
                            CodedFailure(UPLOAD_NOT_FOUND, "S3 no longer has the upload", 404)
                        )
                        PartOutcome.FAILED -> if (failures.count(part) >= MultipartUpload.PART_ATTEMPTS) {
                            throw IOException("Part $part keeps failing (HTTP ${status ?: "none"})")
                        }
                    }
                    progress.report()
                }
                Sent.Done
            } finally {
                ticker.cancel()
                inFlight.values.forEach { it.cancel() }
            }
        }

    private suspend fun start(uuid: String, fileSize: Long): Started {
        val response = api.start(StartUploadRequest(uuid, fileSize, MultipartUpload.REQUESTED_PART_SIZE))
        val body = successOrThrow(response, "start")
        if (body?.status == StartUploadResponse.EXISTS) return Started.Exists
        val uploadId = body?.uploadId?.takeIf { it.isNotBlank() }
        val partSize = body?.partSize ?: MultipartUpload.REQUESTED_PART_SIZE
        if (body?.status != StartUploadResponse.STARTED || uploadId == null || partSize <= 0) {
            throw IOException("Unexpected start answer: ${body?.status}")
        }
        return Started.New(uploadId, partSize)
    }

    private suspend fun uploadedParts(uuid: String, uploadId: String): Uploaded {
        val response = api.uploadedParts(uuid, uploadId)
        if (!response.isSuccessful) {
            val error = ApiError.parse(response)
            val failure = error.codedFailure()
            if (failure?.code == UPLOAD_NOT_FOUND) return Uploaded.Gone(failure)
            if (failure != null) throw CodedFailureException(failure)
            throw IOException("Listing the parts answered HTTP ${response.code()}")
        }
        return Uploaded.Parts(response.body()?.parts.orEmpty().mapNotNull { it.partNumber }.toSet())
    }

    private suspend fun partUrls(uuid: String, uploadId: String, parts: List<Int>): Map<Int, String> {
        val response = api.partUrls(PartUrlsRequest(uuid, uploadId, parts))
        val body = successOrThrow(response, "part URLs")
        return body?.parts.orEmpty().mapNotNull { part ->
            val number = part.partNumber ?: return@mapNotNull null
            val url = part.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            number to url
        }.toMap()
    }

    private suspend fun complete(uuid: String, uploadId: String, partCount: Int, fileSize: Long): Completed {
        val response = api.complete(CompleteUploadRequest(uuid, uploadId, partCount, fileSize))
        if (response.isSuccessful) return Completed.Done
        val error = ApiError.parse(response)
        val failure = error.codedFailure() ?: throw IOException("Complete answered HTTP ${response.code()}")
        return when (failure.code) {
            PARTS_MISSING -> Completed.PartsMissing(failure)
            UPLOAD_NOT_FOUND, INVALID_PARTS -> Completed.Restart(failure)
            else -> throw CodedFailureException(failure)
        }
    }

    /** The body of a successful answer; a coded failure is thrown for the engine, anything else retries */
    private fun <T> successOrThrow(response: Response<T>, what: String): T? {
        if (response.isSuccessful) return response.body()
        ApiError.parse(response).codedFailure()?.let { throw CodedFailureException(it) }
        throw IOException("$what answered HTTP ${response.code()}")
    }

    private fun HashMap<Int, Int>.count(part: Int): Int = merge(part, 1, Int::plus)!!

    private fun dropped(task: SyncTaskEntity, why: String): Boolean {
        Log.w(TAG, "Dropping upload ${task.id}: $why")
        return true
    }

    /** The task was removed while it ran (a sign-out or a lapse cleared the queue): stop */
    private fun gone(task: SyncTaskEntity): Boolean {
        Log.w(TAG, "Upload ${task.id} is gone from the queue, stopping")
        return true
    }

    /**
     * The upload's progress: the parts S3 holds plus the bytes of the parts in flight, published only
     * when the whole percentage changes. Part callbacks arrive on OkHttp's threads, several at once.
     */
    private inner class UploadProgress(private val taskId: String, private val plan: MultipartUploadPlan, uploadedBytes: Long) {
        private val uploaded = AtomicLong(uploadedBytes)
        private val inFlight = ConcurrentHashMap<Int, Long>()
        private val lastPercent = AtomicInteger(-1)

        fun sending(part: Int, sent: Long) {
            // Set, not added: a retried attempt starts its count again
            inFlight[part] = sent
            report()
        }

        fun stopped(part: Int) {
            inFlight.remove(part)
        }

        fun uploaded(part: Int) {
            uploaded.addAndGet(plan.length(part))
        }

        fun report() {
            val bytes = (uploaded.get() + inFlight.values.sum()).coerceAtMost(plan.fileSize)
            val fraction = bytes.toDouble() / plan.fileSize
            val percent = (fraction * 100).toInt()
            if (lastPercent.getAndSet(percent) != percent) onProgress(taskId, fraction)
        }
    }

    private sealed interface PartEvent {
        data class Finished(val part: Int, val status: Int?) : PartEvent
        data object Tick : PartEvent
    }

    private sealed interface Started {
        data object Exists : Started
        data class New(val uploadId: String, val partSize: Long) : Started
    }

    private sealed interface Uploaded {
        data class Parts(val parts: Set<Int>) : Uploaded
        data class Gone(val cause: CodedFailure) : Uploaded
    }

    private sealed interface Sent {
        /** Uploads must wait for Wi-Fi: the run stops and the task is given back */
        data object Done : Sent
        data object Held : Sent
        data class Restart(val cause: CodedFailure) : Sent
    }

    private sealed interface Completed {
        data object Done : Completed
        data class PartsMissing(val cause: CodedFailure) : Completed
        data class Restart(val cause: CodedFailure) : Completed
    }

    companion object {
        private const val TAG = "MultipartUpload"
        const val UPLOAD_NOT_FOUND = "upload_not_found"
        const val ITEM_NOT_FOUND = "item_not_found"
        const val INVALID_PARTS = "invalid_parts"
        const val PARTS_MISSING = "parts_missing"

        /** Stored with the park and sent with Report; the row shows the localized text */
        private const val TOO_LARGE_MESSAGE = "Larger than 10 GB, so it stays on this device and isn't backed up."

        /** The processor the sync hosts register: the book's file by uuid, held to Wi-Fi per the setting */
        fun create(context: Context, repository: SyncTaskRepository): MultipartUploadProcessor {
            val appContext = context.applicationContext
            return MultipartUploadProcessor(
                repository = repository,
                bookFile = { uuid ->
                    AppDatabase.getDatabase(appContext).libraryDao().getItemById(uuid)?.relativePath
                        ?.let { OfflineDownloadManager.processedFile(appContext, it) }
                        ?.takeIf { it.isFile }
                },
                holdUploads = { UploadDataPolicy.shouldHoldUploads(appContext) },
                reRegister = { uuid ->
                    UploadHandBack.reRegister(AppDatabase.getDatabase(appContext).libraryDao(), repository, uuid)
                },
            )
        }
    }
}
