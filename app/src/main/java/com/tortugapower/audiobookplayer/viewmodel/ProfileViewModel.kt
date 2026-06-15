package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
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
            clearLocalSession()
        }
    }

    /**
     * Permanently delete the account on the server (DELETE /v1/user/delete, authenticated via
     * the Bearer token), then clear the local session. Returns the server's confirmation message
     * on success, or a failure the caller can surface. The local data is wiped only after the
     * server confirms deletion.
     */
    suspend fun deleteAccount(): Result<String?> {
        return try {
            val response = NetworkClient.authApi.deleteAccount()
            if (response.isSuccessful) {
                // The server's confirmation message, or null so the UI shows its localized default.
                val message = response.body()?.message
                clearLocalSession()
                Result.success(message)
            } else {
                Result.failure(Exception("Failed to delete account (${response.code()})"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun clearLocalSession() {
        // Drop the in-memory Bearer token first, before any suspension point, so it's reliably
        // cleared even if a later step throws or the coroutine is cancelled. Done here (not just in
        // the delete path) so logout clears it too. None of the steps below need the token.
        NetworkClient.setToken(null)
        accountRepository.deleteAccount()
        syncTaskRepository.deleteAllTasks()
        SubscriptionManager.logout()
        SyncStatusManager.updateLastSyncTimestamp(0) // Reset to effectively "Never"
    }

    fun deleteAllTasks() {
        viewModelScope.launch {
            syncTaskRepository.deleteAllTasks()
        }
    }
}
