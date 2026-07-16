package com.tortugapower.audiobookplayer.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType

object MapConverter {
    private val gson = Gson()

    @TypeConverter
    @JvmStatic
    fun fromString(value: String?): Map<String, String>? {
        if (value == null) return null
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, mapType)
    }

    @TypeConverter
    @JvmStatic
    fun fromMap(map: Map<String, String>?): String? {
        if (map == null) return null
        return gson.toJson(map)
    }

    // Explicit two-way mapping instead of .name/valueOf: the strings below are what existing
    // installs already have persisted in Room, and literals stay stable under R8 obfuscation
    // (reflective valueOf on a renamed constant would not).
    @TypeConverter
    @JvmStatic
    fun fromExternalServiceType(value: ExternalServiceType): String = when (value) {
        ExternalServiceType.JELLYFIN -> "JELLYFIN"
        ExternalServiceType.AUDIOBOOKSHELF -> "AUDIOBOOKSHELF"
    }

    @TypeConverter
    @JvmStatic
    fun toExternalServiceType(value: String): ExternalServiceType = when (value) {
        "JELLYFIN" -> ExternalServiceType.JELLYFIN
        "AUDIOBOOKSHELF" -> ExternalServiceType.AUDIOBOOKSHELF
        else -> throw IllegalArgumentException("Unknown ExternalServiceType: $value")
    }
}
