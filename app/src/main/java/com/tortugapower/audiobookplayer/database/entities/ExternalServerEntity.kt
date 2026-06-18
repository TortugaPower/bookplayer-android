package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.tortugapower.audiobookplayer.database.MapConverter

enum class ExternalServiceType {
    JELLYFIN, AUDIOBOOKSHELF
}

@Entity(tableName = "external_servers")
data class ExternalServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ExternalServiceType,
    val url: String,
    val username: String? = null,
    val token: String? = null,
    val customHeaders: Map<String, String>? = null
)
