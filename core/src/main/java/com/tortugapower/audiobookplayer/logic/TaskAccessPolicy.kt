package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity

/**
 * Policy defining which tasks can be created/processed based on account tier.
 */
object TaskAccessPolicy {
    
    /**
     * Checks if the given account tier can access the sync service at all.
     */
    fun canAccessSyncService(tier: AccountTier?): Boolean {
        return when (tier) {
            AccountTier.LITE, AccountTier.PRO -> true
            else -> false
        }
    }

    /**
     * Whether the tier can STREAM items from an external media server (Jellyfin/AudiobookShelf) —
     * the single source of truth for the integration streaming gate. Streaming is the paid feature
     * (LITE/PRO): no local file on device, and progress syncs through our DB so playback continues on
     * any device with access to the same server. DOWNLOADING a file from the user's own server is
     * deliberately FREE (a one-off; the file lands on-device) — do not gate downloads.
     */
    fun canStreamExternalLibraries(tier: AccountTier?): Boolean = canAccessSyncService(tier)

    /**
     * Checks if the given account tier can execute a specific task type.
     */
    fun canExecuteTask(tier: AccountTier?, jobType: String): Boolean {
        if (jobType == SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH || 
            jobType == SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS ||
            jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE) {
            return true
        }

        if (!canAccessSyncService(tier)) return false

        // specific restriction: only PRO can upload files or artwork
        if (jobType == SyncTaskFactory.JOB_UPLOAD_FILE || jobType == SyncTaskFactory.JOB_UPLOAD_ARTWORK) {
            return tier == AccountTier.PRO
        }

        return true
    }
}
