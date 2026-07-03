package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.ExternalServerDao
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.logic.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The crypto boundary for external servers: credentials (token, custom header values) are
 * Keystore-encrypted before they reach Room and decrypted on the way out, so entities handled
 * above this layer always carry plaintext. Always go through this repository — reading the DAO
 * directly returns ciphertext.
 */
class ExternalServerRepository(private val externalServerDao: ExternalServerDao) {
    val allServers: Flow<List<ExternalServerEntity>> =
        externalServerDao.getAllServers().map { servers -> servers.map { it.decrypted() } }

    suspend fun getServerById(id: Long): ExternalServerEntity? {
        return externalServerDao.getServerById(id)?.decrypted()
    }

    suspend fun saveServer(server: ExternalServerEntity): Long {
        return externalServerDao.insertServer(server.encrypted())
    }

    suspend fun updateServer(server: ExternalServerEntity) {
        externalServerDao.updateServer(server.encrypted())
    }

    suspend fun deleteServer(server: ExternalServerEntity) {
        // Room deletes by primary key; no credential transform needed.
        externalServerDao.deleteServer(server)
    }

    private fun ExternalServerEntity.encrypted() = copy(
        token = token?.let(CredentialCipher::encrypt),
        customHeaders = customHeaders?.mapValues { CredentialCipher.encrypt(it.value) }
    )

    private fun ExternalServerEntity.decrypted() = copy(
        token = token?.let(CredentialCipher::decrypt),
        customHeaders = customHeaders?.mapValues { CredentialCipher.decrypt(it.value) }
    )
}
