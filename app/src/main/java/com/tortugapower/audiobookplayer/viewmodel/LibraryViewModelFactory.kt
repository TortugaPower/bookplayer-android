package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * Simple factory to provide LibraryViewModel with its repository dependencies.
 * In a larger app, this would be replaced by Dagger/Hilt or Koin.
 */
class LibraryViewModelFactory(
    private val repository: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(repository, syncTaskRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
