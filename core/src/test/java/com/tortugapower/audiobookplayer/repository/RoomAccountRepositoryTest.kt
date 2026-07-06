package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.AccountDao
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the account token is encrypted on save and decrypted on read. Uses a fake [TokenCipher] so the
 * contract is testable without the Android Keystore (the real cipher needs a device).
 */
class RoomAccountRepositoryTest {

    // Reversible stand-in for CredentialCipher.
    private val fakeCipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    private class FakeAccountDao : AccountDao {
        var stored: AccountEntity? = null
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(stored)
        override suspend fun getAccount(): AccountEntity? = stored
        override suspend fun saveAccount(account: AccountEntity) { stored = account }
        override suspend fun deleteAccount() { stored = null }
    }

    private val dao = FakeAccountDao()
    private val repo = RoomAccountRepository(dao, fakeCipher)

    private fun account(token: String) =
        AccountEntity(id = "u1", email = "e@x.com", apiToken = token, tier = AccountTier.PRO)

    @Test fun saveEncryptsTokenAtRest() = runBlocking {
        repo.saveAccount(account("jwt-123"))
        // The row Room actually stores is ciphertext, never the plaintext JWT.
        assertEquals("ENC(jwt-123)", dao.stored?.apiToken)
    }

    @Test fun getAccount_decryptsToken() = runBlocking {
        repo.saveAccount(account("jwt-123"))
        assertEquals("jwt-123", repo.getAccount()?.apiToken)
    }

    @Test fun getAccountFlow_decryptsToken() = runBlocking {
        repo.saveAccount(account("jwt-123"))
        assertEquals("jwt-123", repo.getAccountFlow().first()?.apiToken)
    }

    @Test fun getAccount_nullWhenEmpty() = runBlocking {
        assertNull(repo.getAccount())
    }
}
