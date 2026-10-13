package com.tortugapower.audiobookplayer.logic

/**
 * What the alert for a run of dropped downloads says: each media server that rejected the sign-in once
 * (trying again won't help until the user signs in), then the other books as one line.
 */
data class DownloadFailureSummary(val expiredServers: List<String>, val incompleteTitles: List<String>) {
    companion object {
        fun of(failures: List<SyncStatusManager.DownloadFailure>) = DownloadFailureSummary(
            expiredServers = failures.mapNotNull { it.expiredServer }.distinct(),
            incompleteTitles = failures.filter { it.expiredServer == null }.map { it.title },
        )
    }
}
