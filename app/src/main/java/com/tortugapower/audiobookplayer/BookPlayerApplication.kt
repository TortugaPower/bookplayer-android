package com.tortugapower.audiobookplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.EmbeddedArtworkFetcher
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
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
import kotlinx.coroutines.flow.map
import com.tortugapower.audiobookplayer.logic.StorageMonitor

class BookPlayerApplication : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + StorageMonitor.exceptionHandler { this })

    companion object {
        lateinit var instance: BookPlayerApplication
            private set
    }

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

        // Global Initialization
        val database = AppDatabase.getDatabase(this)
        val baseLibraryRepository = RoomLibraryRepository(this, database.libraryDao())
        val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
        val accountRepository = RoomAccountRepository(database.accountDao())
        
        val syncingLibraryRepository = SyncingLibraryRepository(
            baseLibraryRepository,
            syncTaskRepository,
            accountRepository
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
        SubscriptionManager.initialize(this, accountRepository, syncTaskRepository, BuildConfig.REVENUECAT_API_KEY)

        // Start background services. The sync host stops itself when idle (Android 15+ dataSync
        // budget), so :core wakes it back up whenever a new sync task is enqueued.
        com.tortugapower.audiobookplayer.logic.SyncEngineWaker.onWorkEnqueued = {
            TaskConcurrencyServiceHost.start(this)
        }
        if (StorageMonitor.isCritical) {
            android.util.Log.w("BookPlayerApplication", "Storage critically full; not starting the sync host")
        } else {
            TaskConcurrencyServiceHost.start(this)
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
     * Attach the signed-in account's id + email to outgoing Sentry events. Updates when the
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
                        id = account.id
                        email = account.email
                    })
                } else {
                    Sentry.setUser(null)
                }
            }
        }
    }
}
