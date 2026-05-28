package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val accountRepository: AccountRepository,
    private val syncTaskRepository: SyncTaskRepository
) : ViewModel() {

    val account: StateFlow<AccountEntity?> = accountRepository.getAccountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val syncTasks: StateFlow<List<SyncTaskEntity>> = syncTaskRepository.getAllTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val pendingTasksCount: StateFlow<Int> = syncTasks.map { tasks ->
        tasks.count { it.status != SyncTaskStatus.COMPLETED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncTimestamp: StateFlow<Long?> = SyncStatusManager.lastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val taskProgress: StateFlow<Map<String, Double>> = SyncStatusManager.taskProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun logout() {
        viewModelScope.launch {
            accountRepository.deleteAccount()
            syncTaskRepository.deleteAllTasks()
            SubscriptionManager.logout()
            SyncStatusManager.updateLastSyncTimestamp(0) // Reset to effectively "Never"
        }
    }

    fun deleteAllTasks() {
        viewModelScope.launch {
            syncTaskRepository.deleteAllTasks()
        }
    }
}
