package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAccountFlow(): Flow<AccountEntity?>
    suspend fun getAccount(): AccountEntity?
    suspend fun saveAccount(account: AccountEntity)

    /** Changes the signed-in account's tier, never bringing back one signed out meanwhile */
    suspend fun updateTier(tier: AccountTier) {
        getAccount()?.let { saveAccount(it.copy(tier = tier)) }
    }

    suspend fun deleteAccount()
}
