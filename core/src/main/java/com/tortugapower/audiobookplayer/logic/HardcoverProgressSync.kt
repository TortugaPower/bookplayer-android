package com.tortugapower.audiobookplayer.logic

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.network.ReadInProgressData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Puts a book's listening percentage on its read in progress on Hardcover (iOS `HardcoverService.updateReadProgress`),
 * measured against that read's edition, only forward and never at the end.
 *
 * It rides the Hardcover lane like the media servers' progress rides theirs: `SyncingLibraryRepository` queues one
 * `hardcover_update_progress` task per book holding the latest percentage, behind the status change that starts the
 * read, and [HardcoverProcessor] runs it through [send]. A book's first send goes right away, the next ones at least
 * 2 minutes apart ([notBefore], the task's `nextAttemptAt`), and a pause sends what's waiting ([onPause]). A failure
 * Hardcover may get over (offline, a timeout, rate limited, down) is retried by the engine; anything else is dropped,
 * and the next save queues the latest again. Hardcover never touches playback or the library.
 */
object HardcoverProgressSync {
    private const val TAG = "HardcoverProgress"
    /** A request pair every two minutes per book while listening: far below Hardcover's rate limit. */
    const val INTERVAL_MS = 120_000L
    private const val READ_LIBRARY_SCOPE = "read:library"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** When each book's progress last went out, this process: a new process's first goes right away. */
    private val lastSent = ConcurrentHashMap<String, Long>()

    /** When a new progress task for [itemUuid] may run: null for now, else 2 minutes after its last send. */
    fun notBefore(itemUuid: String, now: Long): Long? = lastSent[itemUuid]?.plus(INTERVAL_MS)?.takeIf { it > now }

    /** Playback paused: a progress task waiting for its 2 minutes goes now, so stopping mid-interval loses nothing. */
    fun onPause() {
        scope.launch {
            try {
                flush(RoomSyncTaskRepository(AppDatabase.getDatabase(CoreContext.appContext).syncTaskDao()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't send the held Hardcover progress: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Runs every progress task waiting only for its slot now (not one waiting out a failure's backoff). */
    internal suspend fun flush(repository: SyncTaskRepository) {
        repository.getTasksByStatus(SyncTaskStatus.PENDING)
            .filter { it.jobType == SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS && it.isWaitingForSlot }
            .forEach { SyncRetryWake.retryNow(repository, it.id) }
    }

    private val updater = HardcoverProgressUpdate(
        lacksReadLibrary = { HardcoverAccount.lacksScope(READ_LIBRARY_SCOPE) },
        hardcoverLink = { uuid -> AppDatabase.getDatabase(CoreContext.appContext).libraryDao().getExternalResource(uuid, "hardcover") },
        userId = { HardcoverAccount.fetchConnectedUserId() },
        readInProgress = HardcoverService::readInProgress,
        updateReadProgress = HardcoverService::updateReadProgress,
    )

    /** A queued progress task's run. Throws for a failure worth retrying; anything else is done with. */
    suspend fun send(itemUuid: String, percent: Double) {
        send(itemUuid, percent, System.currentTimeMillis(), updater::update)
    }

    internal suspend fun send(
        itemUuid: String,
        percent: Double,
        now: Long,
        update: suspend (String, Double) -> HardcoverProgressResult,
    ) {
        lastSent[itemUuid] = now
        val result = try {
            update(itemUuid, percent)
        } catch (e: HardcoverService.HardcoverRequestException) {
            if (isWorthRetrying(e.status)) throw e
            Log.e(TAG, "Hardcover refused the progress request: HTTP ${e.status}")
            return
        }
        // `me` failed: nearly always offline (a refused sign-in has already ended it, and the task was dropped)
        if (result == HardcoverProgressResult.NoUserId) throw IOException("Hardcover account id unavailable")
    }

    /** No answer (offline throws an IOException, retried as is), a timeout, rate limited or down. */
    internal fun isWorthRetrying(status: Int): Boolean = status == 0 || status == 408 || status == 429 || status >= 500

    @VisibleForTesting
    internal fun resetForTests() = lastSent.clear()
}

/** Waiting for its 2-minute slot, not for a retry: no failure behind it, nor a park. */
private val SyncTaskEntity.isWaitingForSlot: Boolean
    get() = nextAttemptAt != null && failureStreak == 0 && pauseScope == null

/** What happened to one progress update, for the log and the tests. */
internal sealed class HardcoverProgressResult {
    data object Sent : HardcoverProgressResult()
    data class Refused(val error: String) : HardcoverProgressResult()
    data object MissingScope : HardcoverProgressResult()
    /** Not linked to a Hardcover book, or not Currently Reading in the library. */
    data object NotReading : HardcoverProgressResult()
    data object NoUserId : HardcoverProgressResult()
    data object NoReadInProgress : HardcoverProgressResult()
    data class Skipped(val reason: HardcoverReadProgress.Skip) : HardcoverProgressResult()
}

/** One progress update, from the book's link to Hardcover's read: everything it reads or sends is passed in. */
internal class HardcoverProgressUpdate(
    private val lacksReadLibrary: suspend () -> Boolean,
    private val hardcoverLink: suspend (itemUuid: String) -> ExternalResourceEntity?,
    private val userId: suspend () -> String?,
    private val readInProgress: suspend (userId: Int, bookId: Int) -> ReadInProgressData?,
    /** Null when Hardcover took it, else its error. */
    private val updateReadProgress: suspend (readId: Int, progress: HardcoverReadProgress) -> String?,
) {
    suspend fun update(itemUuid: String, percent: Double): HardcoverProgressResult {
        // Finding the read needs `read:library`, which a sign-in from before progress sync lacks: Hardcover answers
        // 403 until the user signs in again, which Settings offers.
        if (lacksReadLibrary()) {
            Log.i(TAG, "Hardcover progress skipped: the sign-in lacks $READ_LIBRARY_SCOPE")
            return HardcoverProgressResult.MissingScope
        }
        val resource = hardcoverLink(itemUuid)
        val bookId = resource?.providerId?.toIntOrNull()
        if (resource?.syncStatus != "reading" || bookId == null) return HardcoverProgressResult.NotReading
        val userId = userId()?.toIntOrNull() ?: run {
            Log.i(TAG, "Hardcover progress skipped for book $bookId: the account's id couldn't be read")
            return HardcoverProgressResult.NoUserId
        }

        val userBook = readInProgress(userId, bookId)?.userBooks?.firstOrNull()
        val read = userBook?.userBookReads?.firstOrNull()
        if (userBook?.statusId != STATUS_CURRENTLY_READING || read == null) {
            Log.i(TAG, "Hardcover progress skipped for book $bookId: no read in progress")
            return HardcoverProgressResult.NoReadInProgress
        }

        val fallbacks = listOf(userBook.edition) + (userBook.book?.defaultEditions ?: emptyList())
        val progress = when (val outcome = HardcoverReadProgress.evaluate(percent, read, fallbacks)) {
            is HardcoverReadProgress.Outcome.Send -> outcome.progress
            is HardcoverReadProgress.Outcome.Skipped -> {
                when (val reason = outcome.reason) {
                    HardcoverReadProgress.Skip.NoLength -> Log.i(
                        TAG,
                        "Hardcover progress skipped for book $bookId: no edition with a length (read, entry, default " +
                            "audio, ebook, print, most read audio, paged): " +
                            (listOf(read.edition) + fallbacks).joinToString { it?.describe() ?: "none" }
                    )
                    is HardcoverReadProgress.Skip.NotAhead -> Log.i(
                        TAG, "Hardcover progress skipped for book $bookId: ${reason.value} isn't ahead of ${reason.current}"
                    )
                    HardcoverReadProgress.Skip.OutOfRange -> Unit
                }
                return HardcoverProgressResult.Skipped(outcome.reason)
            }
        }

        val error = updateReadProgress(read.id, progress) ?: return HardcoverProgressResult.Sent
        Log.e(TAG, "Hardcover refused the progress of book $bookId: $error")
        return HardcoverProgressResult.Refused(error)
    }

    private companion object {
        const val TAG = "HardcoverProgress"
        const val READ_LIBRARY_SCOPE = "read:library"
        const val STATUS_CURRENTLY_READING = 2
    }
}
