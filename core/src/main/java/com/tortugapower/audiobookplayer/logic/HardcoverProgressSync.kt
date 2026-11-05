package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.network.HardcoverProgressSchedule
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.network.ReadInProgressData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Puts a book's listening percentage on its read in progress on Hardcover (iOS `HardcoverService.updateReadProgress`):
 * measured against that read's edition, the first update right away, then at most every 2 minutes per book plus on
 * pause, only forward and never at the end. Hardcover failures are logged and dropped: an outage must never touch
 * playback or the library, and the next update sends it again.
 */
object HardcoverProgressSync {
    private const val TAG = "HardcoverProgress"
    /** A request pair every two minutes per book while listening: far below Hardcover's rate limit. */
    private const val INTERVAL_MS = 120_000L
    private const val READ_LIBRARY_SCOPE = "read:library"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val schedule = HardcoverProgressSchedule(INTERVAL_MS)

    /** A Currently Reading book's progress, [percent] 0–100: sent now, or held for the next slot. */
    fun onProgress(itemUuid: String, percent: Double) {
        // 0% (back to the start, marked unfinished) and the end are never sent: they mustn't use up the book's slot,
        // and what was held from before the move is stale now.
        if (!HardcoverReadProgress.isInRange(percent)) {
            synchronized(schedule) { schedule.drop(itemUuid) }
            return
        }
        val toSend = synchronized(schedule) { schedule.record(percent, itemUuid, System.currentTimeMillis()) } ?: return
        scope.launch { send(itemUuid, toSend) }
    }

    /** Playback paused: everything held is sent now, so stopping mid-interval loses nothing. */
    fun onPause() {
        val held = synchronized(schedule) { schedule.flush(System.currentTimeMillis()) }
        if (held.isEmpty()) return
        scope.launch { held.forEach { (uuid, percent) -> send(uuid, percent) } }
    }

    private suspend fun send(itemUuid: String, percent: Double) {
        try {
            update(itemUuid, percent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Hardcover progress for $itemUuid: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private val updater = HardcoverProgressUpdate(
        lacksReadLibrary = { HardcoverAccount.lacksScope(READ_LIBRARY_SCOPE) },
        hardcoverLink = { uuid -> AppDatabase.getDatabase(CoreContext.appContext).libraryDao().getExternalResource(uuid, "hardcover") },
        userId = { HardcoverAccount.fetchConnectedUserId() },
        readInProgress = HardcoverService::readInProgress,
        updateReadProgress = HardcoverService::updateReadProgress,
    )

    private suspend fun update(itemUuid: String, percent: Double) {
        updater.update(itemUuid, percent)
    }
}

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
