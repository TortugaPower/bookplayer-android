package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.network.HardcoverProgressSchedule
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.HardcoverService
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
    private const val STATUS_CURRENTLY_READING = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val schedule = HardcoverProgressSchedule(INTERVAL_MS)

    /** A Currently Reading book's progress, [percent] 0–100: sent now, or held for the next slot. */
    fun onProgress(itemUuid: String, percent: Double) {
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

    private suspend fun update(itemUuid: String, percent: Double) {
        // Finding the read needs `read:library`, which a sign-in from before progress sync lacks: Hardcover answers
        // 403 until the user signs in again, which Settings offers.
        if (HardcoverAccount.lacksScope(READ_LIBRARY_SCOPE)) {
            Log.i(TAG, "Hardcover progress skipped: the sign-in lacks $READ_LIBRARY_SCOPE")
            return
        }
        val dao = AppDatabase.getDatabase(CoreContext.appContext).libraryDao()
        val resource = dao.getExternalResource(itemUuid, "hardcover") ?: return
        if (resource.syncStatus != "reading") return
        val bookId = resource.providerId.toIntOrNull() ?: return
        val userId = HardcoverAccount.fetchConnectedUserId()?.toIntOrNull() ?: return

        val userBook = HardcoverService.readInProgress(userId, bookId)?.userBooks?.firstOrNull()
        val read = userBook?.userBookReads?.firstOrNull()
        if (userBook?.statusId != STATUS_CURRENTLY_READING || read == null) {
            Log.i(TAG, "Hardcover progress skipped for book $bookId: no read in progress")
            return
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
                return
            }
        }

        val error = HardcoverService.updateReadProgress(read.id, progress)
        if (error != null) Log.e(TAG, "Hardcover refused the progress of book $bookId: $error")
    }
}
