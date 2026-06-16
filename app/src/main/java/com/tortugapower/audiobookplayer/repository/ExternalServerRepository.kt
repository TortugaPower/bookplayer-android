package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.ExternalServerDao
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import kotlinx.coroutines.flow.Flow

class ExternalServerRepository(private val externalServerDao: ExternalServerDao) {
    val allServers: Flow<List<ExternalServerEntity>> = externalServerDao.getAllServers()

    suspend fun getServerById(id: Long): ExternalServerEntity? {
        return externalServerDao.getServerById(id)
    }

    suspend fun saveServer(server: ExternalServerEntity): Long {
        return externalServerDao.insertServer(server)
    }

    suspend fun updateServer(server: ExternalServerEntity) {
        externalServerDao.updateServer(server)
    }

    suspend fun deleteServer(server: ExternalServerEntity) {
        externalServerDao.deleteServer(server)
    }
}
