package com.tortugapower.audiobookplayer.wear

import android.app.Application
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.network.NetworkConstants
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.tortugapower.audiobookplayer.wear.sync.WearSyncServiceHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Wear OS Application. Configures the shared `:core` layer (context, network, RevenueCat) and builds the
 * repositories the watch needs — the same manual-DI startup as the phone's `BookPlayerApplication`, minus
 * the Media3/player pieces (those land with the Wear playback service in the next slice).
 *
 * The account/library/sync-task repositories are exposed for the Wear UI/ViewModels. The Wear sync
 * foreground service ([WearSyncServiceHost]) runs only while the account is on the standalone (PRO) tier —
 * so a remote-controller or signed-out watch never runs a background sync service.
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
        libraryRepository = RoomLibraryRepository(this, database.libraryDao())
        syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())

        // RevenueCat resolves the tier that gates standalone vs. remote mode. An empty key (dev builds)
        // no-ops gracefully; login happens once the watch has an account (sign-in handoff).
        SubscriptionManager.initialize(
            this,
            accountRepository,
            syncTaskRepository,
            BuildConfig.REVENUECAT_API_KEY,
        )

        // Run the on-watch sync service only while the account is PRO (standalone) AND the process is
        // foreground. The foreground gate is essential on Wear: the process is frequently woken in the
        // background by Data Layer listener services, and starting a dataSync foreground service from the
        // background throws ForegroundServiceStartNotAllowedException on API 31+. Starting it while
        // foreground is safe, and a started FGS is allowed to keep running once the app backgrounds — so
        // we only stop it when the account leaves PRO (sign-out / downgrade), not on every background.
        // (Background-initiated sync would need an expedited WorkManager job — a later enhancement.)
        appScope.launch {
            val isPro = accountRepository.getAccountFlow().map { it?.tier == AccountTier.PRO }
            val isForeground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.STARTED) }
            combine(isPro, isForeground) { pro, foreground -> pro to foreground }
                .distinctUntilChanged()
                .collect { (pro, foreground) ->
                    when {
                        pro && foreground -> WearSyncServiceHost.start(this@WearApp)
                        !pro -> WearSyncServiceHost.stop(this@WearApp)
                        // pro && !foreground: leave an already-running FGS running; never start from bg.
                    }
                }
        }
    }
}
