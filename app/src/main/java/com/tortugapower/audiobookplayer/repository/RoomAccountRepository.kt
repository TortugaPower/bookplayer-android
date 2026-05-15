package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.AccountDao
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RoomAccountRepository(
    private val accountDao: AccountDao
) : AccountRepository {
    override fun getAccountFlow(): Flow<AccountEntity?> = accountDao.getAccountFlow()

    override suspend fun getAccount(): AccountEntity? = withContext(Dispatchers.IO) {
        accountDao.getAccount()
    }

    override suspend fun saveAccount(account: AccountEntity) = withContext(Dispatchers.IO) {
        accountDao.saveAccount(account)
    }

    override suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        accountDao.deleteAccount()
    }
}
