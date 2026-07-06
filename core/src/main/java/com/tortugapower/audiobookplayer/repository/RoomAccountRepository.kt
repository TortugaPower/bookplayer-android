package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.AccountDao
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.logic.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The `apiToken` is the API bearer JWT; it's Keystore-encrypted at rest (via [CredentialCipher]) rather
 * than sitting in Room as plaintext — the Android analog of iOS's Keychain, consistent with how
 * external-server tokens are already protected. Encrypt on save, decrypt on read; shared by phone + Wear.
 *
 * NO plaintext→ciphertext migration/fallback by design: **account sign-in is not in the production
 * version yet**, so no signed-in users (and no stored plaintext tokens) exist — nothing to migrate. A dev
 * build that was signed in before this change simply re-authenticates (acceptable for unshipped code; we
 * don't add compat for features that never shipped).
 *
 * Decryption is pushed off Main with `flowOn(Dispatchers.Default)` — Keystore access + AES/GCM would
 * otherwise run on the collector's thread (Main, for `collectAsState`). Mirrors `ExternalServerRepository`.
 */
class RoomAccountRepository(
    private val accountDao: AccountDao
) : AccountRepository {
    override fun getAccountFlow(): Flow<AccountEntity?> =
        accountDao.getAccountFlow()
            .map { it?.withDecryptedToken() }
            .flowOn(Dispatchers.Default)

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
