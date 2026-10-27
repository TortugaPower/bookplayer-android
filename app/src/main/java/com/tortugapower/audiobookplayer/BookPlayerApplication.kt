package com.tortugapower.audiobookplayer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.EmbeddedArtworkFetcher
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.ParkedTaskRetry
import com.tortugapower.audiobookplayer.logic.PreferencesPullTriggers
import com.tortugapower.audiobookplayer.logic.PhoneSyncEngine
import com.tortugapower.audiobookplayer.logic.SyncKeepAlive
import com.tortugapower.audiobookplayer.logic.SyncPauseReporter
import com.tortugapower.audiobookplayer.logic.SyncRuntime
import com.tortugapower.audiobookplayer.logic.SyncRuntimeOwner
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.TaskAccessPolicy
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncSessionHooks
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortStore
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.tortugapower.audiobookplayer.logic.StorageMonitor

class BookPlayerApplication : Application(), ImageLoaderFactory, SyncRuntimeOwner {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + StorageMonitor.exceptionHandler { this })

    companion object {
        lateinit var instance: BookPlayerApplication
            private set
    }

    /** Reports parked sync tasks to Sentry, once each. Set in [onCreate]. */
    lateinit var syncPauseReporter: SyncPauseReporter
        private set

    /** This device's first sync of the signed-in account. Set in [onCreate]. */
    lateinit var firstSync: com.tortugapower.audiobookplayer.logic.FirstSyncCoordinator
        private set

    /** Library sort brain: sort actions + preference push/pull. Set in [onCreate]. */
    lateinit var librarySortManager: LibrarySortManager
        private set

    /** The process's one sync engine. Set in [onCreate]. */
    override lateinit var syncRuntime: SyncRuntime
        private set

    /**
     * App-wide Coil loader with our [EmbeddedArtworkFetcher] registered, so the library list can resolve
     * embedded cover art (via the [com.tortugapower.audiobookplayer.logic.ItemArtwork] model) for items
     * with no stored artworkURL. Only appends the fetcher — Coil's default memory/disk caches and the
     * built-in http/file/uri fetchers are all retained. (The widget passes String artworkURLs, so it
     * uses the default fetchers, not this one.)
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(EmbeddedArtworkFetcher.Factory(applicationContext)) }
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Provide :core with the app context + flavored BuildConfig before anything touches the network.
        com.tortugapower.audiobookplayer.core.CoreContext.init(this)
        // Measure storage before anything can write: at zero bytes free the database can't open, and
        // MainActivity shows the storage screen instead of the app (Sentry ANDROID-BOOKPLAYER-10/-12).
        StorageMonitor.refresh(this)
        com.tortugapower.audiobookplayer.network.NetworkConstants.configure(
            baseUrl = BuildConfig.BASE_URL,
            googleClientId = BuildConfig.GOOGLE_CLIENT_ID
        )
        // How this install introduces itself to media servers (Jellyfin's MediaBrowser header).
        com.tortugapower.audiobookplayer.network.ClientIdentity.configure(
            appName = "BookPlayer",
            appVersion = BuildConfig.VERSION_NAME,
        )

        // Global Initialization
        val database = AppDatabase.getDatabase(this)
        val baseLibraryRepository = RoomLibraryRepository(this, database.libraryDao())
        val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
        val accountRepository = RoomAccountRepository(database.accountDao())
        syncPauseReporter = SyncPauseReporter(syncTaskRepository)
        
        val syncingLibraryRepository = SyncingLibraryRepository(
            baseLibraryRepository,
            syncTaskRepository,
            accountRepository
        )

        firstSync = com.tortugapower.audiobookplayer.logic.FirstSyncCoordinator(
            store = com.tortugapower.audiobookplayer.logic.DataStoreSyncStateStore(this),
            pass = com.tortugapower.audiobookplayer.logic.MissingItemsPass(
                itemsStatus = { com.tortugapower.audiobookplayer.network.NetworkClient.libraryApi.itemsStatus(mapOf("uuids" to it)) },
                matchUuids = { com.tortugapower.audiobookplayer.network.NetworkClient.libraryApi.matchUuids(mapOf("items" to it)) },
                libraryDao = { database.libraryDao() },
                repository = syncTaskRepository,
                bookFile = { com.tortugapower.audiobookplayer.logic.OfflineDownloadManager.processedFile(this, it) },
                canUploadFiles = {
                    TaskAccessPolicy.canExecuteTask(accountRepository.getAccount()?.tier, com.tortugapower.audiobookplayer.logic.SyncTaskFactory.JOB_UPLOAD_FILE)
                },
            ),
            syncTasks = syncTaskRepository,
            isSyncActive = { TaskAccessPolicy.canAccessSyncService(accountRepository.getAccount()?.tier) },
            fetchRoot = { com.tortugapower.audiobookplayer.network.NetworkClient.libraryApi.getContents("") },
            applyRootListing = { root ->
                com.tortugapower.audiobookplayer.logic.ContentsListing.apply(
                    this, database.libraryDao(), syncTaskRepository,
                    com.tortugapower.audiobookplayer.logic.PlaybackManagerSyncCoordinator, "", root, canDelete = false,
                )
            },
            // Its own scope, off the main thread: the pass reads the library and checks files on disk
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + StorageMonitor.exceptionHandler { this }),
            // Not on last session's tier: a lapse while the app was closed shows in this launch's reading
            awaitTierReady = SubscriptionManager::awaitTierReady,
        )

        val librarySortStore = LibrarySortStore(DataStorePreferencesStore(this))
        librarySortManager = LibrarySortManager(
            syncingLibraryRepository,
            librarySortStore,
            syncTaskRepository,
            accountRepository
        )
        // Next/previous and end-of-book auto-advance follow the visible (effective) order.
        // Property-wired: the manager depends on the repository, so this can't be a constructor arg.
        baseLibraryRepository.effectiveSortResolver = librarySortManager::effectiveSort

        // Initialize Managers
        PlaybackManager.initialize(
            this,
            syncingLibraryRepository,
            sessionService = android.content.ComponentName(
                this,
                com.tortugapower.audiobookplayer.service.AudioPlayerService::class.java,
            ),
            unknownAuthorLabel = getString(R.string.library_unknown_author),
            onPlaybackStateChanged = { itemChanged, isPlaying ->
                com.tortugapower.audiobookplayer.widget.WidgetPlaybackNotifier.notify(this, itemChanged, isPlaying)
            },
        )
        SubscriptionManager.initialize(
            this, accountRepository, syncTaskRepository, BuildConfig.REVENUECAT_API_KEY,
            syncHooks = object : SyncSessionHooks {
                override suspend fun syncEnded() = firstSync.endSession()

                // Every tier the account is read with (iOS noteProAccess on each account update): gaining PRO owes
                // a missing-items pass, which uploads the files LITE never sent
                override suspend fun tierRead(tier: AccountTier) = firstSync.onTierRead(tier == AccountTier.PRO)
            },
        )

        // Keep NetworkClient's auth token current with the signed-in account at the app level — as the
        // watch does — so playback (presigned-URL refresh), account calls and sync all see it.
        appScope.launch {
            accountRepository.getAccountFlow().collect { account ->
                NetworkClient.setToken(account?.apiToken)
            }
        }

        // iOS parity (PreferencesSyncService): pull the synced sort preferences past their cooldown
        // whenever the app comes to the foreground (launch included), and whenever the signed-in account
        // or its tier changes to one with cloud sync (login, free → LITE/PRO). The regular pull only runs
        // on a library visit, so another device's sort change otherwise waited for one.
        val forcePreferencesPull = {
            appScope.launch(Dispatchers.IO) {
                if (syncingLibraryRepository.isCloudSyncActive()) {
                    SyncTaskFactory.createFetchPreferencesTask(syncTaskRepository, force = true)
                }
            }
        }
        // The process's one sync engine (SyncRuntime): built off the main thread, then running for the life of the
        // process. A new task makes sure it's started, and that the sync service is up if lanes
        // work (SyncKeepAlive).
        syncRuntime = SyncRuntime(
            this, appScope,
            buildEngine = { onFailure ->
                PhoneSyncEngine.build(this, syncTaskRepository, accountRepository, firstSync, syncPauseReporter, onFailure)
            },
            onFailed = { io.sentry.Sentry.captureException(it) },
        )
        val startSyncService = { TaskConcurrencyServiceHost.start(this) }
        com.tortugapower.audiobookplayer.logic.SyncEngineWaker.onWorkEnqueued = {
            syncRuntime.start()
            SyncKeepAlive.startIfLanesWork(syncRuntime.activeQueues.value, startSyncService)
        }
        // The sync service keeps the process alive while lanes work; it stops itself once they've been idle a minute
        appScope.launch { SyncKeepAlive.startWhenLanesWork(syncRuntime.activeQueues, startSyncService) }

        // The one automatic retry of parked tasks, when the app is first opened in this process
        val parkedTaskRetry = ParkedTaskRetry(
            resumeAllPaused = syncTaskRepository::resumeAllPaused,
            wakeEngine = { com.tortugapower.audiobookplayer.logic.SyncEngineWaker.notifyWorkEnqueued() },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                forcePreferencesPull()
                appScope.launch(Dispatchers.IO) { parkedTaskRetry.onForeground(StorageMonitor.isCritical) }
                // An engine that failed is built again (only here, not for every new task); and in the foreground a
                // start of the sync service is always allowed, so one refused in the background gets its retry here
                syncRuntime.onAppOpened()
                SyncKeepAlive.startIfLanesWork(syncRuntime.activeQueues.value, startSyncService)
                // Every time the app is opened, tasks waiting out a backoff retry now (their streak kept).
                // Not while storage is critical: the engine holds all work then.
                appScope.launch(Dispatchers.IO) {
                    if (!StorageMonitor.isCritical) {
                        com.tortugapower.audiobookplayer.logic.SyncRetryWake.retryAllNow(syncTaskRepository)
                    }
                }
            }
        })
        appScope.launch {
            PreferencesPullTriggers.onSyncAccountChange(accountRepository.getAccountFlow())
                .collect {
                    forcePreferencesPull()
                    // A sign-in (or a subscription) starts this device's first sync right away, whatever
                    // screen is showing (iOS runs it when sync turns on)
                    firstSync.request()
                }
        }

        // Mirror playback state to a paired Wear watch (remote-controller mode).
        com.tortugapower.audiobookplayer.wear.WearRemotePublisher.initialize(this, database.libraryDao())
        com.tortugapower.audiobookplayer.wear.WearRemotePublisher.start()

        // Mirror the selected theme to the watch (device-local choice, so the Data Layer is the only channel).
        com.tortugapower.audiobookplayer.wear.WearThemePublisher.initialize(this)
        com.tortugapower.audiobookplayer.wear.WearThemePublisher.start()

        // Crash/error reporting
        initSentry(accountRepository)
        // After Sentry: an engine that fails right away is reported (SyncRuntime's onFailed)
        syncRuntime.start()
    }

    private fun initSentry(accountRepository: AccountRepository) {
        val dsn = BuildConfig.SENTRY_DSN
        // Builds without a DSN (OSS contributors, fresh checkouts) are a graceful no-op, and so are
        // dev-flavor builds unless the developer opted in (SENTRY_REPORTING, see app/build.gradle.kts):
        // emulator crash reproductions must not show up as production issues.
        if (dsn.isBlank() || !BuildConfig.SENTRY_REPORTING) return

        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            // Tag every event with the build flavor so dev vs prod traffic is separable
            // in the Sentry dashboard.
            options.environment = BuildConfig.FLAVOR
            // Capture 100% of transactions in debug to make local testing easy; sample
            // down in release to keep Sentry quota usage reasonable.
            options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.2
            // Performance tracing: track view/composable interactions and emit breadcrumbs
            // for them. Crashes always captured; ANRs captured automatically.
            options.isEnableUserInteractionTracing = true
            options.isEnableUserInteractionBreadcrumbs = true
        }

        bindUserToSentry(accountRepository)
    }

    /**
     * Attach the signed-in account to outgoing Sentry events as its RevenueCat app-user id — the
     * server's `external_id`, which is also the storage prefix and the RevenueCat customer id, so
     * support can reach the whole account (email, tier, library) from that one opaque key. The
     * email itself never leaves the device: it is personal data Sentry does not need. Accounts
     * persisted before the RevenueCat id was stored fall back to the local account id (the sign-in
     * provider's subject, which the server's auth methods table also resolves). Updates when the
     * user signs in or out so crash reports always reflect the current identity (or anonymous
     * when signed out).
     */
    private fun bindUserToSentry(accountRepository: AccountRepository) {
        // appScope carries the storage-aware handler: with the disk full the database may not open, and
        // that must not take the process down at startup (Sentry ANDROID-BOOKPLAYER-10).
        appScope.launch(Dispatchers.IO) {
            accountRepository.getAccountFlow().collect { account ->
                if (account != null) {
                    Sentry.setUser(User().apply {
                        id = account.revenuecatId ?: account.id
                    })
                } else {
                    Sentry.setUser(null)
                }
            }
        }
    }
}
