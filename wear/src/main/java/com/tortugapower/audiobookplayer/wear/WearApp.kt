package com.tortugapower.audiobookplayer.wear

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.network.NetworkConstants
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository
import com.tortugapower.audiobookplayer.wear.service.WearPlaybackService
import com.tortugapower.audiobookplayer.wear.sync.WearSyncServiceHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Wear OS Application. Configures the shared `:core` layer (context, network, RevenueCat), builds the
 * repositories the watch needs, and wires the on-watch player — the same manual-DI startup as the phone's
 * `BookPlayerApplication`, minus the home-screen widget.
 *
 * The library repository is a [SyncingLibraryRepository] so on-watch playback progress persists AND
 * enqueues sync tasks (uploaded by [WearSyncServiceHost]), matching the phone. `PlaybackManager` is a
 * `MediaController` client pointed at the watch's own [WearPlaybackService]. The sync foreground service
 * runs only while the account is PRO AND the app is in use (foreground OR actively playing) — never
 * started from a background Data Layer wake.
 */
class WearApp : Application() {
    lateinit var accountRepository: AccountRepository
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var syncTaskRepository: SyncTaskRepository
        private set

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Give :core the app context + flavored config before anything touches Room/network/RevenueCat.
        CoreContext.init(this)
        NetworkConstants.configure(
            baseUrl = BuildConfig.BASE_URL,
            // The watch authenticates by handing the token off from the phone (Data Layer), never via
            // Google Sign-In, so it has no Google client id. GOOGLE_CLIENT_ID is only read by the
            // Google-login path, which the watch never invokes.
            googleClientId = "",
        )

        val database = AppDatabase.getDatabase(this)
        accountRepository = RoomAccountRepository(database.accountDao())
        syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
        // Syncing wrapper so on-watch playback progress (and speed/boost) both persist and enqueue sync tasks.
        libraryRepository = SyncingLibraryRepository(
            RoomLibraryRepository(this, database.libraryDao()),
            syncTaskRepository,
            accountRepository,
        )

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

        // Run the on-watch sync foreground service only while PRO AND the app is in use (foreground, or
        // actively playing so progress keeps syncing in the background). Never started from a background
        // Data Layer wake — a background dataSync FGS start throws on API 31+. Started only on a
        // foreground->true or play->true transition, both of which happen while foreground.
        appScope.launch {
            val isPro = accountRepository.getAccountFlow().map { it?.tier == AccountTier.PRO }
            val isForeground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.STARTED) }
            combine(isPro, isForeground, PlaybackManager.isPlaying) { pro, foreground, playing ->
                pro && (foreground || playing)
            }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    if (shouldRun) WearSyncServiceHost.start(this@WearApp)
                    else WearSyncServiceHost.stop(this@WearApp)
                }
        }
    }
}
