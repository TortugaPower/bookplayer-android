package com.tortugapower.audiobookplayer.wear

import android.app.Application
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.network.NetworkConstants
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository

/**
 * Wear OS Application. Configures the shared `:core` layer (context, network, RevenueCat) and builds
 * the repositories the watch needs — the same manual-DI startup as the phone's `BookPlayerApplication`,
 * minus the Media3/player/sync-service pieces that stay phone-only until a later phase.
 *
 * The account-repository is exposed for the Wear UI/ViewModels to observe (there is no signed-in
 * account yet — the phone→watch sign-in handoff lands in the next slice).
 */
class WearApp : Application() {
    lateinit var accountRepository: AccountRepository
        private set

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
        val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())

        // RevenueCat resolves the tier that gates standalone vs. remote mode. An empty key (dev builds)
        // no-ops gracefully; login happens once the watch has an account (next slice).
        SubscriptionManager.initialize(
            this,
            accountRepository,
            syncTaskRepository,
            BuildConfig.REVENUECAT_API_KEY,
        )
    }
}
