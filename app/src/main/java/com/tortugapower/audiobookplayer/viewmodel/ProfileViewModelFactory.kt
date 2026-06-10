package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

class ProfileViewModelFactory(
    private val accountRepository: AccountRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val statisticsDao: com.tortugapower.audiobookplayer.database.dao.StatisticsDao,
    private val libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(accountRepository, syncTaskRepository, statisticsDao, libraryDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
