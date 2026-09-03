package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * One process-wide answer to "is the device out of storage?", fed from two directions:
 *
 *  - **Measured** ([refresh]): free bytes on the app's data volume, checked at launch, on resume and
 *    before any transfer of known size ([hasRoomFor]).
 *  - **Observed** ([reportFailure] / [exceptionHandler]): a write that just failed for lack of space.
 *    Storage most often fills while the app is running — our own downloads and imports are a likely
 *    cause — and the first symptom is a `SQLiteFullException` or `ENOSPC` from whatever wrote next.
 *
 * Both flip [State.isCritical]; the UI shows the storage state, transfers hold, and nothing crashes.
 * A reported failure stays sticky until a refresh sees space back above [RECOVERED_BYTES], so the
 * state doesn't flap on the critical line. (Sentry ANDROID-BOOKPLAYER-12 / -10 / -1D / -1G / -S / -V.)
 */
object StorageMonitor {
    private const val TAG = "StorageMonitor"

    /**
     * Below this the database itself is at risk: SQLite needs headroom for its WAL shared-memory and
     * journal even for a one-row update (`SQLITE_IOERR_SHMSIZE` at open is the zero-byte case), and
     * DataStore writes a temp file before renaming.
     */
    const val CRITICAL_BYTES = 32L * 1024 * 1024
    private const val RECOVERED_BYTES = 2 * CRITICAL_BYTES
    /** Headroom kept free when accepting a download or import of a known size. */
    const val TRANSFER_RESERVE_BYTES = 64L * 1024 * 1024

    data class State(
        val availableBytes: Long,
        /** The database itself is at risk: playback, sync and imports stop; the UI shows the storage screen/banner. */
        val isCritical: Boolean,
        /** A download or import of known size did not fit; file transfers wait for space, everything else runs. */
        val transfersHeld: Boolean = false,
        /** When a write last failed for lack of space (epoch ms), or null. */
        val lastFailureAt: Long?,
    )

    private val _state = MutableStateFlow(State(availableBytes = Long.MAX_VALUE, isCritical = false, lastFailureAt = null))
    val state: StateFlow<State> = _state.asStateFlow()
    val isCritical: Boolean get() = _state.value.isCritical

    /** Set while a reported failure is unresolved; cleared by a refresh that finds space again. */
    private var failureReported = false
    /** Size of the largest transfer that didn't fit, while one is waiting; 0 when none. */
    private var heldTransferBytes = 0L

    @VisibleForTesting
    internal var availableBytesProvider: (Context) -> Long = { ctx -> StatFs(ctx.filesDir.path).availableBytes }

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Re-measure free space and recompute the state. A reported failure is cleared only here, and only
     * once space is back above [RECOVERED_BYTES] — an explicit re-check (launch, resume, "Check again"),
     * never the measurement taken at the moment of the failure. Safe to call from any thread.
     */
    fun refresh(context: Context): State {
        val available = measure(context)
        synchronized(this) {
            if (available >= RECOVERED_BYTES) failureReported = false
            if (heldTransferBytes > 0 && available - heldTransferBytes >= TRANSFER_RESERVE_BYTES) heldTransferBytes = 0
            val next = State(
                availableBytes = available,
                isCritical = failureReported || available < CRITICAL_BYTES,
                transfersHeld = heldTransferBytes > 0,
                lastFailureAt = _state.value.lastFailureAt,
            )
            _state.value = next
            return next
        }
    }

    /**
     * A download or import of [bytes] was refused for lack of space: file transfers wait until a
     * refresh sees room for it again, instead of retrying into the same wall every few seconds.
     */
    fun noteTransferDoesNotFit(context: Context, bytes: Long) {
        synchronized(this) {
            heldTransferBytes = maxOf(heldTransferBytes, bytes)
        }
        Log.w(TAG, "Not enough storage for a $bytes-byte transfer; holding file transfers")
        refresh(context)
    }

    val transfersHeld: Boolean get() = _state.value.transfersHeld

    private fun measure(context: Context): Long = try {
        availableBytesProvider(context.applicationContext)
    } catch (e: Exception) {
        Log.w(TAG, "Could not measure free space", e)
        Long.MAX_VALUE
    }

    /** Whether a transfer of [bytes] fits with [TRANSFER_RESERVE_BYTES] to spare. Refreshes the state. */
    fun hasRoomFor(context: Context, bytes: Long): Boolean =
        refresh(context).availableBytes - bytes >= TRANSFER_RESERVE_BYTES

    /** True for the exceptions a full disk produces, anywhere in the cause chain. */
    fun isStorageFailure(t: Throwable?): Boolean {
        var cause = t
        var depth = 0
        while (cause != null && depth < 8) {
            when {
                cause is SQLiteFullException -> return true
                // SQLITE_IOERR_SHMSIZE (4874) is SQLite failing to size its WAL shared memory at open; the
                // message carries "OS error - 28:No space left on device" on most builds.
                cause is SQLiteDiskIOException && mentionsNoSpace(cause.message) -> return true
                cause is ErrnoException && cause.errno == OsConstants.ENOSPC -> return true
                cause is IOException && mentionsNoSpace(cause.message) -> return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    private fun mentionsNoSpace(message: String?): Boolean =
        message != null && (message.contains("ENOSPC") || message.contains("No space left") ||
            message.contains("SHMSIZE") || message.contains("SQLITE_FULL") || message.contains("disk is full"))

    /**
     * Record a write that failed for lack of space and flip the state to critical. Returns false, and
     * changes nothing, for unrelated errors — so callers can use it as a filter.
     */
    fun reportFailure(context: Context?, t: Throwable): Boolean {
        if (!isStorageFailure(t)) return false
        // The write is the ground truth: a free-space figure taken now must not talk it down (the
        // failing volume may differ from the one measured, or the figure may be stale).
        val available = context?.let { measure(it) } ?: _state.value.availableBytes
        synchronized(this) {
            failureReported = true
            _state.value = _state.value.copy(availableBytes = available, isCritical = true, lastFailureAt = clock())
        }
        Log.w(TAG, "A write failed because storage is full; holding transfers", t)
        return true
    }

    /**
     * A [CoroutineExceptionHandler] for the app's long-lived scopes: a full-disk failure is recorded
     * (the UI shows the storage state) instead of killing the process; anything else is handed to the
     * default uncaught handler exactly as before, so real bugs still crash and still reach Sentry.
     */
    fun exceptionHandler(context: () -> Context? = { null }): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        if (!reportFailure(context(), e)) {
            val fallback = Thread.getDefaultUncaughtExceptionHandler()
            if (fallback != null) fallback.uncaughtException(Thread.currentThread(), e) else throw e
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(this) {
            failureReported = false
            heldTransferBytes = 0L
            _state.value = State(Long.MAX_VALUE, isCritical = false, lastFailureAt = null)
        }
    }
}
