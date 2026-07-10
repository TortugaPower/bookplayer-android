package com.tortugapower.audiobookplayer.repository

import android.util.Log
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
    private val accountDao: AccountDao,
    // Injected so the encrypt-on-save / decrypt-on-read wiring is unit-testable with a fake (the real
    // one uses the Android Keystore, which isn't available under JVM/Robolectric). Defaults to Keystore.
    private val cipher: TokenCipher = KeystoreTokenCipher
) : AccountRepository {
    override fun getAccountFlow(): Flow<AccountEntity?> =
        accountDao.getAccountFlow()
            .map { it?.decryptedOrNull() }
            .flowOn(Dispatchers.Default)

    override suspend fun getAccount(): AccountEntity? = withContext(Dispatchers.IO) {
        accountDao.getAccount()?.decryptedOrNull()
    }

    override suspend fun saveAccount(account: AccountEntity) = withContext(Dispatchers.IO) {
        accountDao.saveAccount(account.copy(apiToken = cipher.encrypt(account.apiToken)))
    }

    override suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        accountDao.deleteAccount()
    }

    // Treat an undecryptable token as signed-out rather than throwing: the Keystore key can go missing
    // (reinstall/restore, key invalidation), and an uncaught decrypt used to wedge the sync worker in an
    // infinite retry. Returning null makes the app recover to a clean signed-out state.
    private fun AccountEntity.decryptedOrNull(): AccountEntity? =
        try {
            copy(apiToken = cipher.decrypt(apiToken))
        } catch (e: Exception) {
            // Breadcrumb only — exception type, never the token/account payload.
            Log.w("RoomAccountRepository", "Account token decrypt failed (${e.javaClass.simpleName}); treating as signed out")
            null
        }
}

/** Encrypt/decrypt indirection for stored credentials — lets tests substitute a fake for the Keystore. */
interface TokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(stored: String): String
}

/** Shared by [RoomAccountRepository] and [ExternalServerRepository] (one key covers all credentials). */
internal object KeystoreTokenCipher : TokenCipher {
    override fun encrypt(plaintext: String): String = CredentialCipher.encrypt(plaintext)
    override fun decrypt(stored: String): String = CredentialCipher.decrypt(stored)
}
