package com.tortugapower.audiobookplayer

import android.app.Application
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository

class BookPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global Initialization
        val database = AppDatabase.getDatabase(this)
        val baseLibraryRepository = RoomLibraryRepository(database.libraryDao())
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
    }
}
