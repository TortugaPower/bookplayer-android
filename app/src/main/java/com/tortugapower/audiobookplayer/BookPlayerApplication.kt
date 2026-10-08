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
import com.tortugapower.audiobookplayer.logic.SyncHostLaunchGate
import com.tortugapower.audiobookplayer.logic.SyncPauseReporter
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.SyncTaskPicker
import com.tortugapower.audiobookplayer.logic.TaskAccessPolicy
import com.tortugapower.audiobookplayer.logic.UploadDataPolicy
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.tortugapower.audiobookplayer.logic.StorageMonitor

class BookPlayerApplication : Application(), ImageLoaderFactory {
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
        // watch does — so playback (presigned-URL refresh), account calls and sync all see it whether or
        // not the sync host is running. Until the launch gate below, the host's unconditional start was
        // what set the token for the whole process.
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
        // The one automatic retry of parked tasks, when the app is first opened in this process
        val parkedTaskRetry = ParkedTaskRetry(
            resumeAllPaused = syncTaskRepository::resumeAllPaused,
            wakeEngine = { com.tortugapower.audiobookplayer.logic.SyncEngineWaker.notifyWorkEnqueued() },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                forcePreferencesPull()
                appScope.launch(Dispatchers.IO) { parkedTaskRetry.onForeground(StorageMonitor.isCritical) }
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

        // The sync host stops itself when idle (Android 15+ dataSync budget), and :core wakes it back up
        // whenever a sync task is enqueued — so at launch it is started only for work left over from an
        // earlier session (see SyncHostLaunchGate), never just to sit idle.
        com.tortugapower.audiobookplayer.logic.SyncEngineWaker.onWorkEnqueued = {
            TaskConcurrencyServiceHost.start(this)
        }
        appScope.launch(Dispatchers.IO) {
            // Before the gate: jobs this build no longer runs are held, so they'd never start the engine
            // that cleans them up, and one left in the sync lane holds back every library refresh
            com.tortugapower.audiobookplayer.logic.SyncTaskRetirement.cleanUp(syncTaskRepository)
            // Parked tasks don't count: they're retried when the app is opened (ParkedTaskRetry)
            val hasStartableWork: suspend () -> Boolean = {
                // The count first: most launches have an empty queue and skip loading it
                syncTaskRepository.countActiveTasks() > 0 && run {
                    // This launch's tier, not last session's: a lapse while closed holds the queue
                    SubscriptionManager.awaitTierReady()
                    val tier = accountRepository.getAccount()?.tier
                    // Uploads held to Wi-Fi don't count either: the service would only sit idle
                    val holdUploads = UploadDataPolicy.shouldHoldUploads(this@BookPlayerApplication)
                    SyncTaskPicker.hasStartableWork(syncTaskRepository.getAllTasks().first()) {
                        TaskAccessPolicy.canExecuteTask(tier, it) && !(holdUploads && UploadDataPolicy.isFileUploadJob(it))
                    }
                }
            }
            if (SyncHostLaunchGate.shouldStart(StorageMonitor.isCritical, hasStartableWork)) {
                TaskConcurrencyServiceHost.start(this@BookPlayerApplication)
            }
        }
        // The engine holds all work while storage is critical; restart it when space is back.
        appScope.launch {
            StorageMonitor.state
                .map { it.isCritical }
                .distinctUntilChanged()
                .drop(1)
                .filter { critical -> !critical }
                .collect { TaskConcurrencyServiceHost.start(this@BookPlayerApplication) }
        }

        // Mirror playback state to a paired Wear watch (remote-controller mode).
        com.tortugapower.audiobookplayer.wear.WearRemotePublisher.initialize(this, database.libraryDao())
        com.tortugapower.audiobookplayer.wear.WearRemotePublisher.start()

        // Mirror the selected theme to the watch (device-local choice, so the Data Layer is the only channel).
        com.tortugapower.audiobookplayer.wear.WearThemePublisher.initialize(this)
        com.tortugapower.audiobookplayer.wear.WearThemePublisher.start()

        // Crash/error reporting
        initSentry(accountRepository)
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
