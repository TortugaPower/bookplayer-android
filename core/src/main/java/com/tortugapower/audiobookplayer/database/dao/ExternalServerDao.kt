package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalServerDao {
    @Query("SELECT * FROM external_servers")
    fun getAllServers(): Flow<List<ExternalServerEntity>>

    @Query("SELECT * FROM external_servers WHERE id = :id")
    suspend fun getServerById(id: Long): ExternalServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ExternalServerEntity): Long

    @Update
    suspend fun updateServer(server: ExternalServerEntity)

    @Delete
    suspend fun deleteServer(server: ExternalServerEntity)
}
