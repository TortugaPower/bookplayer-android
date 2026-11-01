package com.tortugapower.audiobookplayer.wear

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.PlaybackForeground
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.StorageGuardedExecutor
import com.tortugapower.audiobookplayer.logic.StorageMonitor
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncDrain
import com.tortugapower.audiobookplayer.logic.SyncDrainScheduler
import com.tortugapower.audiobookplayer.logic.SyncEngineWaker
import com.tortugapower.audiobookplayer.logic.SyncKeepAlive
import com.tortugapower.audiobookplayer.logic.SyncKeepAliveService
import com.tortugapower.audiobookplayer.logic.SyncRuntime
import com.tortugapower.audiobookplayer.logic.SyncRuntimeHost
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortStore
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.network.NetworkConstants
import com.tortugapower.audiobookplayer.wear.complication.NowPlayingComplicationService
import com.tortugapower.audiobookplayer.wear.data.DataLayerRemoteContextRepository
import com.tortugapower.audiobookplayer.wear.tile.NowPlayingTileService
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository
import com.tortugapower.audiobookplayer.wear.service.WearPlaybackService
import com.tortugapower.audiobookplayer.wear.sync.WearSyncEngine
import com.tortugapower.audiobookplayer.wear.sync.WearSyncPolicy
import com.tortugapower.audiobookplayer.wear.sync.WearSyncServiceHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Wear OS Application. Configures the shared `:core` layer (context, network, RevenueCat), builds the
 * repositories the watch needs, and wires the on-watch player — the same manual-DI startup as the phone's
 * `BookPlayerApplication`, minus the home-screen widget.
 *
 * The library repository is a [SyncingLibraryRepository] so on-watch playback progress persists AND
 * enqueues sync tasks, matching the phone. `PlaybackManager` is a `MediaController` client pointed at the
 * watch's own [WearPlaybackService]. The sync runtime is the phone's (SyncRuntime, SyncKeepAlive, sync drains),
 * but the engine is built only when there's sync (WearSyncPolicy).
 */
class WearApp : Application(), Configuration.Provider, SyncRuntimeHost {
    lateinit var accountRepository: AccountRepository
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var syncTaskRepository: SyncTaskRepository
        private set

    /**
     * Pull-only on the watch: resolves the sticky library sort so the standalone list and playback
     * next/auto-advance match the phone. Nothing on the watch writes sort preferences — the values
     * arrive via [com.tortugapower.audiobookplayer.logic.PreferenceFetchProcessor] into the watch's
     * own DataStore.
     */
    lateinit var librarySortManager: LibrarySortManager
        private set

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + com.tortugapower.audiobookplayer.logic.StorageMonitor.exceptionHandler { com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull })

    /** The process's one sync engine, built when there's sync (WearSyncPolicy). Set in [onCreate]. */
    private lateinit var syncRuntime: SyncRuntime

    /** Starts the sync service when a download needs it to keep the process alive. Set in [onCreate]. */
    override lateinit var syncKeepAlive: SyncKeepAlive
        private set

    /** Runs the sync engine while nothing else keeps the process alive (SyncDrainWorker). Set in [onCreate]. */
    override lateinit var syncDrain: SyncDrain
        private set

    /**
     * WorkManager starts on demand, with this (the manifest drops its start-up initializer, which wrote its database
     * at every process start). A full disk under its database is reported, never a crash: at its start (the
     * handlers), and in its own bookkeeping (StorageGuardedExecutor), which a pending drain runs before our code.
     * :wear has no Sentry, so anything else is logged.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { e -> onWorkManagerFailed(e) }
            .setSchedulingExceptionHandler { e -> onWorkManagerFailed(e) }
            .setTaskExecutor(StorageGuardedExecutor(Dispatchers.IO.asExecutor()) { e -> StorageMonitor.reportFailure(this, e) })
            .build()

    private fun onWorkManagerFailed(e: Throwable) {
        if (!StorageMonitor.reportFailure(this, e)) android.util.Log.e("WearApp", "WorkManager failed", e)
    }

    override fun onCreate() {
        super.onCreate()

        // Give :core the app context + flavored config before anything touches Room/network/RevenueCat.
        CoreContext.init(this)
        // Playback is refused while storage is critically full (progress can't be saved); measure up front.
        com.tortugapower.audiobookplayer.logic.StorageMonitor.refresh(this)
        NetworkConstants.configure(
            baseUrl = BuildConfig.BASE_URL,
            // The watch authenticates by handing the token off from the phone (Data Layer), never via
            // Google Sign-In, so it has no Google client id. GOOGLE_CLIENT_ID is only read by the
            // Google-login path, which the watch never invokes.
            googleClientId = "",
        )
        com.tortugapower.audiobookplayer.network.ClientIdentity.configure(
            appName = "BookPlayer",
            appVersion = BuildConfig.VERSION_NAME,
        )

        val database = AppDatabase.getDatabase(this)
        accountRepository = RoomAccountRepository(database.accountDao())
        syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
        // Syncing wrapper so on-watch playback progress (and speed/boost) both persist and enqueue sync tasks.
        val baseLibraryRepository = RoomLibraryRepository(this, database.libraryDao())
        libraryRepository = SyncingLibraryRepository(
            baseLibraryRepository,
            syncTaskRepository,
            accountRepository,
        )

        librarySortManager = LibrarySortManager(
            libraryRepository,
            LibrarySortStore(DataStorePreferencesStore(this)),
            syncTaskRepository,
            accountRepository,
        )
        // Next/previous and end-of-book auto-advance follow the visible (effective) order.
        // Property-wired: the manager depends on the repository, so this can't be a constructor arg.
        baseLibraryRepository.effectiveSortResolver = librarySortManager::effectiveSort

        // RevenueCat resolves the tier that gates standalone vs. remote mode. An empty key (dev builds)
        // no-ops gracefully; login happens once the watch has an account (sign-in handoff).
        SubscriptionManager.initialize(
            this,
            accountRepository,
            syncTaskRepository,
            BuildConfig.REVENUECAT_API_KEY,
        )

        // On-watch player: a MediaController client bound to the watch's own MediaLibraryService. No widget
        // on the watch, so the state-changed callback is a no-op.
        PlaybackManager.initialize(
            this,
            libraryRepository,
            sessionService = ComponentName(this, WearPlaybackService::class.java),
            unknownAuthorLabel = getString(R.string.wear_unknown_author),
        )

        // Keep NetworkClient's auth token current with the signed-in account, at the app level, so both
        // playback (presigned-URL refresh) and sync see the token regardless of the sync service lifecycle.
        appScope.launch {
            accountRepository.getAccountFlow().collect { account ->
                NetworkClient.setToken(account?.apiToken)
            }
        }

        // The one automatic retry of parked tasks, each launch. The watch parks only on the account
        // (it drops other coded failures), so this is what resumes them.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Jobs this build no longer runs are held, so they'd never start the engine that cleans them up
            com.tortugapower.audiobookplayer.logic.SyncTaskRetirement.cleanUp(syncTaskRepository)
            syncTaskRepository.resumeAllPaused()
        }

        // The process's one sync engine (SyncRuntime): built off the main thread, then running for the life of the
        // process, but only when there's sync (WearSyncPolicy). A failure is logged by the runtime (:wear has no
        // Sentry), and the next app open builds it again.
        syncRuntime = SyncRuntime(
            this, appScope,
            buildEngine = { onFailure -> WearSyncEngine.build(this, syncTaskRepository, accountRepository, onFailure) },
        )
        val isPro = accountRepository.getAccountFlow().map { it?.tier == AccountTier.PRO }
            .stateIn(appScope, SharingStarted.Eagerly, false)
        val visible = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .stateIn(appScope, SharingStarted.Eagerly, false)
        // Tasks a killed process left are picked up too: the queue is read at every process start
        val queued = syncTaskRepository.getAllTasks().map { tasks ->
            tasks.any { it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING }
        }
        appScope.launch {
            combine(isPro, visible, queued, WearSyncPolicy::runsEngine)
                .distinctUntilChanged()
                .filter { it }
                .collect { syncRuntime.start() }
        }
        // A new task starts it at once (the queue's own update follows a moment later)
        SyncEngineWaker.onWorkEnqueued = { if (isPro.value) syncRuntime.start() }

        // Sync drains (WorkManager jobs) run the engine while nothing else keeps the process alive
        syncDrain = SyncDrain(
            keptAlive = combine(visible, PlaybackForeground.inForeground, SyncKeepAliveService.running) { visible, playing, service ->
                visible || playing || service
            }.stateIn(appScope, SharingStarted.Eagerly, false),
            scanned = syncRuntime.scanned,
            lanes = syncRuntime.laneStates,
            startEngine = { syncRuntime.start() },
            rescan = { syncRuntime.rescan() },
        )
        val drainScheduler = SyncDrainScheduler(
            needs = { syncRuntime.nextDrain() },
            workManager = { WorkManager.getInstance(this) },
            hasReturned = syncDrain::hasReturned,
        )
        // The sync service keeps the process alive while a download runs in the background, unless playback does;
        // drains take whatever else is left
        syncKeepAlive = SyncKeepAlive(
            appScope,
            visible = visible,
            playing = PlaybackForeground.inForeground,
            lanes = syncRuntime.laneStates,
            serviceRunning = SyncKeepAliveService.running,
            draining = syncDrain.draining,
            scanned = syncRuntime.scanned,
            startService = { WearSyncServiceHost.start(this) },
            scheduleDrains = { drainScheduler.schedule() },
        )
        PlaybackForeground.onLeaving = syncKeepAlive::onPlaybackLeavingForeground
        SyncKeepAliveService.onUnavailable = syncKeepAlive::onServiceUnavailable

        // Every time the app is opened, tasks waiting out a backoff retry now, their streak kept (as on the
        // phone). Not while storage is critical: the engine holds all work then.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                // An engine that failed is built again (only here, not for every new task)
                if (isPro.value) syncRuntime.onAppOpened()
                appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (!com.tortugapower.audiobookplayer.logic.StorageMonitor.isCritical) {
                        com.tortugapower.audiobookplayer.logic.SyncRetryWake.retryAllNow(syncTaskRepository)
                    }
                }
            }
        })

        // Refresh the glance surfaces (tile + complication) when the STANDALONE book changes (new book, or
        // sign-out clears it) OR the current chapter changes (chapter jump / boundary) — they only re-render
        // on their own cadence otherwise, so the progress gauge + "CHAP N" would stay stale. (Progress also
        // refreshes naturally whenever the watch face re-requests, e.g. on wrist-raise, via the live resolve.)
        appScope.launch {
            combine(
                PlaybackManager.currentItem.map { it?.uuid },
                PlaybackManager.currentChapterIndex,
            ) { uuid, chapter -> uuid to chapter }
                .distinctUntilChanged()
                .collect { refreshGlanceSurfaces() }
        }

        // Refresh them in REMOTE mode too: playback is on the phone, so the watch's own currentItem never
        // changes — the glance data comes from the phone's published now-playing, so re-request on its change.
        appScope.launch {
            DataLayerRemoteContextRepository(this@WearApp).libraryState
                .map { it?.currentItem?.id ?: it?.recentItems?.firstOrNull()?.id }
                .distinctUntilChanged()
                .collect { refreshGlanceSurfaces() }
        }
    }

    /** Re-request the now-playing tile + complication (they share [NowPlayingGlance]). */
    private fun refreshGlanceSurfaces() {
        // A glance refresh must never take the app down: the tiles SysUi requester talks to the
        // system (settings read + broadcast) and can throw RemoteException on images/ROMs that
        // restrict it — the tile just refreshes on its own cadence instead.
        try {
            TileService.getUpdater(this).requestUpdate(NowPlayingTileService::class.java)
            ComplicationDataSourceUpdateRequester
                .create(this, ComponentName(this, NowPlayingComplicationService::class.java))
                .requestUpdateAll()
        } catch (e: Exception) {
            android.util.Log.w("WearApp", "Glance surface refresh failed: ${e.message}")
        }
    }
}
