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

    @TypeConverter
    @JvmStatic
    fun fromExternalServiceType(value: ExternalServiceType): String {
        return value.name
    }

    @TypeConverter
    @JvmStatic
    fun toExternalServiceType(value: String): ExternalServiceType {
        return ExternalServiceType.valueOf(value)
    }
}
