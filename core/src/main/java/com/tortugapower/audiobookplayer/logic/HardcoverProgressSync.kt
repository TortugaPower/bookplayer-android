package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.network.HardcoverOAuthException
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.network.ReadInProgressData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Puts a book's listening percentage on its read in progress on Hardcover (iOS `HardcoverService.updateReadProgress`),
 * measured against that read's edition, only forward and never at the end.
 *
 * It rides the Hardcover lane like the media servers' progress rides theirs (iOS's `hardcover` lane):
 * `SyncingLibraryRepository` queues one `hardcover_update_progress` task per linked book holding the latest percentage,
 * and [HardcoverProcessor] runs it through [send]. A run past the reading threshold sets the book Currently Reading
 * first when the link doesn't say so yet, which starts the read the progress goes on. A book's progress is queued
 * whenever its whole percentage changes ([changes], iOS `HardcoverProgressChanges`): a 10-hour book moves every 6
 * minutes, about 20 requests an hour against Hardcover's per-user 5,000 a day, shared with the user's other apps.
 * Playback saves come at least every 10 seconds, with the last one on pause, so nothing is held back to send later. A
 * failure Hardcover may get over (offline, a timeout, rate limited, down) is retried by the engine, as on iOS; anything
 * else is dropped. Hardcover never touches playback or the library.
 */
object HardcoverProgressSync {
    private const val TAG = "HardcoverProgress"
    private const val READ_LIBRARY_SCOPE = "read:library"

    /** The whole percentage last queued per book, this process: the account's, cleared when it's unlinked. */
    val changes = HardcoverProgressChanges()

    /**
     * Unlinked: what was sent belongs to the account just left, so one signed in next gets each book's progress again
     * (iOS), and the progress still queued for it is dropped.
     */
    suspend fun forgetAccount() {
        changes.clear()
        RoomSyncTaskRepository(AppDatabase.getDatabase(CoreContext.appContext).syncTaskDao())
            .deletePendingTasksOfType(SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS)
    }

    private val statusSender = HardcoverStatusSender(
        HardcoverService::saveUserBookStatus, HardcoverService::libraryEntry, HardcoverAccount::fetchConnectedUserId,
    )

    private val updater = HardcoverProgressUpdate(
        lacksReadLibrary = { HardcoverAccount.lacksScope(READ_LIBRARY_SCOPE) },
        hardcoverLink = { uuid -> AppDatabase.getDatabase(CoreContext.appContext).libraryDao().getExternalResource(uuid, "hardcover") },
        readingThreshold = { HardcoverSettingsManager.getReadingThreshold(CoreContext.appContext).first() },
        startReading = { bookId -> statusSender.save(bookId, HardcoverProgressUpdate.STATUS_CURRENTLY_READING) },
        markReading = { uuid ->
            val dao = AppDatabase.getDatabase(CoreContext.appContext).libraryDao()
            // Read again: the book may have been finished (`read`) or unlinked while the status was on its way
            dao.getExternalResource(uuid, "hardcover")
                ?.takeIf { it.syncStatus != HardcoverProgressUpdate.LINK_READ }
                ?.let { dao.insertExternalResource(it.copy(syncStatus = HardcoverProgressUpdate.LINK_READING)) }
        },
        // A refused key throws its 401 here (dropped below), being offline its transport error (retried)
        userId = { HardcoverAccount.connectedUserIdOrThrow() },
        readInProgress = HardcoverService::readInProgress,
        updateReadProgress = HardcoverService::updateReadProgress,
    )

    /** A queued progress task's run. Throws for a failure worth retrying; anything else is done with. */
    suspend fun send(itemUuid: String, percent: Double) {
        send(itemUuid, percent, updater::update)
    }

    internal suspend fun send(
        itemUuid: String,
        percent: Double,
        update: suspend (String, Double) -> HardcoverProgressResult,
    ) {
        try {
            update(itemUuid, percent)
        } catch (e: HardcoverService.HardcoverRequestException) {
            if (isWorthRetrying(e.status)) throw e
            Log.e(TAG, "Hardcover refused the progress request: HTTP ${e.status}")
        } catch (e: HardcoverOAuthException) {
            // The expired token's refresh failed. Only a token endpoint that's down or busy may answer later: a
            // refusal (invalid_client, invalid_request, a bare 4xx, an unreadable answer) would retry forever.
            if (e.isTemporary) throw e
            Log.e(TAG, "Hardcover's sign-in couldn't be refreshed: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Offline, or the connection dropped: retried
            throw e
        } catch (e: Exception) {
            // Anything else won't go away on its own (an answer that doesn't parse, after Hardcover changes a field):
            // retried, it would retry forever
            Log.e(TAG, "Hardcover progress failed: ${e.javaClass.simpleName}")
        }
    }

    /** No answer (offline throws an IOException, retried as is), a timeout, rate limited or down. */
    internal fun isWorthRetrying(status: Int): Boolean = status == 0 || status == 408 || status == 429 || status >= 500

    /** A Hardcover failure that may go away on its own (see [send]): the queued task is retried, not dropped. */
    internal fun isTemporary(e: Throwable): Boolean = when (e) {
        is HardcoverService.HardcoverRequestException -> isWorthRetrying(e.status)
        is HardcoverOAuthException -> e.isTemporary
        is IOException -> true
        else -> false
    }

}

/**
 * When to queue a book's progress: whenever its whole percentage changes (iOS `HardcoverProgressChanges`, whose
 * percentage is already whole). Kept by the item's uuid, which stays the same when the book is moved or renamed.
 */
class HardcoverProgressChanges {
    private val lastQueued = ConcurrentHashMap<String, Int>()

    /** Whether [percent] is a new whole percentage for the item; it's then recorded as queued. */
    fun isNew(itemUuid: String, percent: Double): Boolean {
        val whole = floor(percent).toInt()
        return lastQueued.put(itemUuid, whole) != whole
    }

    /** The book went where nothing is sent (the start or the end): its next percentage is new again. */
    fun forget(itemUuid: String) {
        lastQueued.remove(itemUuid)
    }

    fun clear() = lastQueued.clear()
}

/** What happened to one progress update, for the log and the tests. */
internal sealed class HardcoverProgressResult {
    data object Sent : HardcoverProgressResult()
    data class Refused(val error: String) : HardcoverProgressResult()
    data object MissingScope : HardcoverProgressResult()
    /** Not linked to a Hardcover book, finished, or not past the reading threshold yet. */
    data object NotReading : HardcoverProgressResult()
    data object NoUserId : HardcoverProgressResult()
    data object NoReadInProgress : HardcoverProgressResult()
    data class Skipped(val reason: HardcoverReadProgress.Skip) : HardcoverProgressResult()
}

/** One progress update, from the book's link to Hardcover's read: everything it reads or sends is passed in. */
internal class HardcoverProgressUpdate(
    private val lacksReadLibrary: suspend () -> Boolean,
    private val hardcoverLink: suspend (itemUuid: String) -> ExternalResourceEntity?,
    /** Settings' reading threshold, 0–1: past it, a book not Currently Reading yet is set so first. */
    private val readingThreshold: suspend () -> Float,
    /** Sets the book Currently Reading on Hardcover, which starts its read: the entry's id, or null when none came back. */
    private val startReading: suspend (bookId: Int) -> Int?,
    /** Records the link as Currently Reading, once Hardcover took it. */
    private val markReading: suspend (itemUuid: String) -> Unit,
    private val userId: suspend () -> String?,
    private val readInProgress: suspend (userId: Int, bookId: Int) -> ReadInProgressData?,
    /** Null when Hardcover took it, else its error. */
    private val updateReadProgress: suspend (readId: Int, progress: HardcoverReadProgress) -> String?,
) {
    suspend fun update(itemUuid: String, percent: Double): HardcoverProgressResult {
        val resource = hardcoverLink(itemUuid)
        val bookId = resource?.providerId?.toIntOrNull()
        // A finished book's status is Read instead, through its own status task
        if (resource == null || bookId == null || resource.syncStatus == LINK_READ) return HardcoverProgressResult.NotReading
        if (resource.syncStatus != LINK_READING) {
            if (percent / 100 < readingThreshold()) return HardcoverProgressResult.NotReading
            // Currently Reading first, in the same run (iOS `syncProgress`): it starts the read the progress goes on, and
            // a failure worth retrying throws before any progress is sent. The link says `reading` only once Hardcover
            // took it, so a status change that failed is sent again by the book's next run.
            if (startReading(bookId) == null) {
                Log.e(TAG, "Hardcover returned no entry setting book $bookId Currently Reading")
                return HardcoverProgressResult.NoReadInProgress
            }
            markReading(itemUuid)
        }
        // Finding the read needs `read:library`, which a sign-in from before progress sync would lack: Hardcover answers
        // 403 until the user logs out and signs in again (iOS: no prompt until a scope is added).
        if (lacksReadLibrary()) {
            Log.i(TAG, "Hardcover progress skipped: the sign-in lacks $READ_LIBRARY_SCOPE")
            return HardcoverProgressResult.MissingScope
        }
        val userId = userId()?.toIntOrNull() ?: run {
            Log.i(TAG, "Hardcover progress skipped for book $bookId: Hardcover returned no account")
            return HardcoverProgressResult.NoUserId
        }

        val userBook = readInProgress(userId, bookId)?.userBooks?.firstOrNull()
        val read = userBook?.userBookReads?.firstOrNull()
        if (userBook?.statusId != STATUS_CURRENTLY_READING || read == null) {
            Log.i(TAG, "Hardcover progress skipped for book $bookId: no read in progress")
            return HardcoverProgressResult.NoReadInProgress
        }

        val progress = when (val outcome = HardcoverReadProgress.evaluate(percent, read)) {
            is HardcoverReadProgress.Outcome.Send -> outcome.progress
            is HardcoverReadProgress.Outcome.Skipped -> {
                when (val reason = outcome.reason) {
                    HardcoverReadProgress.Skip.NoLength -> Log.i(
                        TAG,
                        "Hardcover progress skipped for book $bookId: the read's edition has no length: " +
                            (read.edition?.describe() ?: "none")
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

    companion object {
        private const val TAG = "HardcoverProgress"
        private const val READ_LIBRARY_SCOPE = "read:library"
        const val STATUS_CURRENTLY_READING = 2

        /** The link's `syncStatus` once Hardcover took Currently Reading, and once the book is finished */
        const val LINK_READING = "reading"
        const val LINK_READ = "read"
    }
}
