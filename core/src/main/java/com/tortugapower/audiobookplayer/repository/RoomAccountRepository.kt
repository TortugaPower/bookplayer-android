package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.AccountDao
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.logic.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The `apiToken` is the API bearer JWT; it's Keystore-encrypted at rest (via [CredentialCipher]) rather
 * than sitting in Room as plaintext — the Android analog of iOS's Keychain, consistent with how
 * external-server tokens are already protected. Encrypt on save, decrypt on read; shared by phone + Wear.
 */
class RoomAccountRepository(
    private val accountDao: AccountDao
) : AccountRepository {
    override fun getAccountFlow(): Flow<AccountEntity?> =
        accountDao.getAccountFlow().map { it?.withDecryptedToken() }

    override suspend fun getAccount(): AccountEntity? = withContext(Dispatchers.IO) {
        accountDao.getAccount()?.withDecryptedToken()
    }

    override suspend fun saveAccount(account: AccountEntity) = withContext(Dispatchers.IO) {
        accountDao.saveAccount(account.copy(apiToken = CredentialCipher.encrypt(account.apiToken)))
    }

    override suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        accountDao.deleteAccount()
    }

    private fun AccountEntity.withDecryptedToken(): AccountEntity =
        copy(apiToken = CredentialCipher.decrypt(apiToken))
}
