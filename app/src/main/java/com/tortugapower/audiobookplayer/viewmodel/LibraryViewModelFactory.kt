package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository

/**
 * Simple factory to provide LibraryViewModel with its repository dependencies.
 * In a larger app, this would be replaced by Dagger/Hilt or Koin.
 */
class LibraryViewModelFactory(
    private val application: Application,
    private val repository: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(application, repository, syncTaskRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

    companion object {
        /**
         * The canonical production wiring (Syncing wrapper over the Room repository) — the single source
         * shared by MainActivity (which creates the activity-scoped ViewModel up front to hold the OS
         * splash) and MainScreen's `viewModel()` call. Whichever runs first wins the ViewModelStore, so
         * the two MUST be identical; sharing this constructor makes drift impossible.
         */
        fun default(application: Application): LibraryViewModelFactory {
            val database = AppDatabase.getDatabase(application)
            val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
            return LibraryViewModelFactory(
                application,
                SyncingLibraryRepository(
                    RoomLibraryRepository(application, database.libraryDao()),
                    syncTaskRepository,
                    RoomAccountRepository(database.accountDao()),
                ),
                syncTaskRepository,
            )
        }
    }
}
