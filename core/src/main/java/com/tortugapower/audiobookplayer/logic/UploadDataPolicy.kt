package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.flow.first

/**
 * Data-usage gate for background uploads. When the user's "Upload using cellular data" setting is OFF
 * and the active connection is metered (cellular / metered hotspot), file-bearing upload tasks are
 * held until an un-metered connection is available.
 *
 * Only FILE uploads are gated — small metadata/identifier syncs still flow so cross-device state stays
 * consistent, and downloads are user-triggered so they are never gated here.
 */
object UploadDataPolicy {

    /** Upload jobs that move real bytes (audio files, artwork, stream-to-cloud transfers). */
    private val METERED_HELD_JOBS = setOf(
        SyncTaskFactory.JOB_UPLOAD_FILE,
        SyncTaskFactory.JOB_UPLOAD_ARTWORK,
        SyncTaskFactory.JOB_UPLOAD_STREAM_FILE,
    )

    fun isFileUploadJob(jobType: String): Boolean = jobType in METERED_HELD_JOBS

    /** True when file uploads must currently wait (setting off AND on a metered connection). */
    suspend fun shouldHoldUploads(context: Context): Boolean {
        if (PlaybackSettingsManager.getUploadUsingCellularData(context).first()) return false
        return isMeteredConnection(context)
    }

    private fun isMeteredConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.isActiveNetworkMetered
    }
}
