package com.tortugapower.audiobookplayer.wear.sync

import android.content.Context
import com.tortugapower.audiobookplayer.logic.ArtworkUploadProcessor
import com.tortugapower.audiobookplayer.logic.DeleteBookmarkProcessor
import com.tortugapower.audiobookplayer.logic.DeleteExternalResourceProcessor
import com.tortugapower.audiobookplayer.logic.DeleteProcessor
import com.tortugapower.audiobookplayer.logic.DownloadFileProcessor
import com.tortugapower.audiobookplayer.logic.ExternalUpdateProcessor
import com.tortugapower.audiobookplayer.logic.FetchContentsProcessor
import com.tortugapower.audiobookplayer.logic.HardcoverProcessor
import com.tortugapower.audiobookplayer.logic.MatchUuidsProcessor
import com.tortugapower.audiobookplayer.logic.MetadataUploadProcessor
import com.tortugapower.audiobookplayer.logic.MoveProcessor
import com.tortugapower.audiobookplayer.logic.MultipartUploadProcessor
import com.tortugapower.audiobookplayer.logic.PreferenceFetchProcessor
import com.tortugapower.audiobookplayer.logic.PreferenceUploadProcessor
import com.tortugapower.audiobookplayer.logic.QueueFileUploadProcessor
import com.tortugapower.audiobookplayer.logic.RenameFolderProcessor
import com.tortugapower.audiobookplayer.logic.SetBookmarkProcessor
import com.tortugapower.audiobookplayer.logic.ShallowDeleteProcessor
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyManager
import com.tortugapower.audiobookplayer.logic.UpdateProcessor
import com.tortugapower.audiobookplayer.logic.UploadExternalResourceProcessor
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * The watch's sync engine: the same `:core` processors as the phone (PhoneSyncEngine), as the watch runs them. SyncRuntime
 * builds it once per process, off the main thread. It omits the phone-only coordinators: the first sync and parking (the
 * watch parks nothing, and only mirrors the cloud).
 */
internal object WearSyncEngine {
    fun build(
        context: Context,
        repository: SyncTaskRepository,
        accountRepository: AccountRepository,
        onFailure: (Throwable) -> Unit,
    ): TaskConcurrencyManager {
        // FetchContentsProcessor gets the watch's playback coordinator so it re-arms the on-watch player to the server's
        // last-played item after a contents fetch (matching iOS's handleSyncedLastPlayed) instead of leaving the stale
        // locally-restored book.
        val processors = listOf(
            // The watch only mirrors the cloud: every row it has came from a listing
            FetchContentsProcessor(context, repository, WearPlaybackSyncCoordinator, keepsUnconfirmed = false),
            MetadataUploadProcessor(context, repository),
            MultipartUploadProcessor.create(context, repository),
            QueueFileUploadProcessor.create(context, repository),
            UpdateProcessor(),
            MoveProcessor(),
            DeleteProcessor(),
            ShallowDeleteProcessor(),
            RenameFolderProcessor(),
            ArtworkUploadProcessor(context),
            DeleteBookmarkProcessor(),
            SetBookmarkProcessor(),
            // The watch says nothing about a dropped download, as on iOS: its row goes back to not downloaded
            DownloadFileProcessor(context, onFailedForGood = {}),
            MatchUuidsProcessor(context, repository),
            HardcoverProcessor(context),
            UploadExternalResourceProcessor(),
            DeleteExternalResourceProcessor(),
            ExternalUpdateProcessor(context),
            // Sticky-sort preferences: the fetch is the watch's active path (pull-only — nothing on
            // the watch writes sort prefs); the upload processor is registered to keep processor-set
            // parity with the phone, so a preference task can never sit unhandled in this queue.
            PreferenceUploadProcessor(),
            PreferenceFetchProcessor(context, repository),
        )
        return TaskConcurrencyManager(
            context, repository, accountRepository, processors,
            parkingEnabled = false,
            verifySyncEntitlement = { SubscriptionManager.refreshSyncEntitlement() },
            awaitTierReady = SubscriptionManager::awaitTierReady,
            onFailure = onFailure,
        )
    }
}
