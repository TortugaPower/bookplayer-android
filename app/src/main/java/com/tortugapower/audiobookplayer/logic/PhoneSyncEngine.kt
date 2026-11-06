package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.network.NetworkClient
import android.content.Context
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/** The phone's sync engine: every processor the phone runs. SyncRuntime builds it once, off the main thread. */
internal object PhoneSyncEngine {
    fun build(
        context: Context,
        repository: SyncTaskRepository,
        accountRepository: AccountRepository,
        firstSync: FirstSyncCoordinator,
        syncPauseReporter: SyncPauseReporter,
        onFailure: (Throwable) -> Unit,
    ): TaskConcurrencyManager {
        val processors = listOf(
            FetchContentsProcessor(
                context, repository, PlaybackManagerSyncCoordinator,
                canDeleteListings = { firstSync.hasRunFirstSync() },
            ),
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
            DownloadFileProcessor(context, syncTasks = repository, queuesUploads = true),
            MatchUuidsProcessor(context, repository),
            HardcoverProcessor(context),
            UploadExternalResourceProcessor(),
            DeleteExternalResourceProcessor(),
            ExternalUpdateProcessor(context),
            PreferenceUploadProcessor(),
            PreferenceFetchProcessor(context, repository),
        )
        return TaskConcurrencyManager(
            context, repository, accountRepository, processors,
            verifySyncEntitlement = { SubscriptionManager.refreshSyncEntitlement() },
            onTaskPaused = syncPauseReporter::report,
            onSyncLaneDrained = firstSync::onSyncLaneDrained,
            // And for the account's token: a task sent before it goes unauthenticated
            awaitTierReady = {
                NetworkClient.awaitTokenLoaded()
                SubscriptionManager.awaitTierReady()
            },
            onFailure = onFailure,
        )
    }
}
