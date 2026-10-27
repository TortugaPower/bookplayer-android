package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts LIMIT 1")
    fun getAccountFlow(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts LIMIT 1")
    suspend fun getAccount(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAccount(account: AccountEntity)

    /** Changes the signed-in account's tier only: none signed in (a sign-out meanwhile), nothing is written */
    @Query("UPDATE accounts SET tier = :tier")
    suspend fun updateTier(tier: AccountTier): Int

    @Query("DELETE FROM accounts")
    suspend fun deleteAccount()
}
