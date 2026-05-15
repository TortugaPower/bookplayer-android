package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAccountFlow(): Flow<AccountEntity?>
    suspend fun getAccount(): AccountEntity?
    suspend fun saveAccount(account: AccountEntity)
    suspend fun deleteAccount()
}
