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
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BookPlayerApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: BookPlayerApplication
            private set
    }

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

        // Initialize Managers
        PlaybackManager.initialize(this, syncingLibraryRepository)
        SubscriptionManager.initialize(this, accountRepository, syncTaskRepository)

        // Start background services
        TaskConcurrencyServiceHost.start(this)

        // Crash/error reporting
        initSentry(accountRepository)
    }

    private fun initSentry(accountRepository: AccountRepository) {
        val dsn = BuildConfig.SENTRY_DSN
        // Builds without a DSN (OSS contributors, fresh checkouts) are a graceful no-op.
        if (dsn.isBlank()) return

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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
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
