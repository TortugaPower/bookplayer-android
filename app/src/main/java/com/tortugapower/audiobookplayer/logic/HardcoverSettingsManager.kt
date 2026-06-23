package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object HardcoverSettingsManager {
    private val HARDCOVER_TOKEN = stringPreferencesKey("hardcover_token")
    private val AUTO_MATCH_BOOKS = booleanPreferencesKey("hardcover_auto_match")
    private val AUTO_ADD_TO_WANT_TO_READ = booleanPreferencesKey("hardcover_auto_add_want_to_read")
    private val READING_THRESHOLD = floatPreferencesKey("hardcover_reading_threshold")

    fun getToken(context: Context): Flow<String> = context.dataStore.data.map { it[HARDCOVER_TOKEN] ?: "" }
    suspend fun setToken(context: Context, token: String) {
        context.dataStore.edit { it[HARDCOVER_TOKEN] = token }
    }

    fun getAutoMatchBooks(context: Context): Flow<Boolean> = context.dataStore.data.map { it[AUTO_MATCH_BOOKS] ?: false }
    suspend fun setAutoMatchBooks(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[AUTO_MATCH_BOOKS] = enabled }
    }

    fun getAutoAddToWantToRead(context: Context): Flow<Boolean> = context.dataStore.data.map { it[AUTO_ADD_TO_WANT_TO_READ] ?: true }
    suspend fun setAutoAddToWantToRead(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[AUTO_ADD_TO_WANT_TO_READ] = enabled }
    }

    fun getReadingThreshold(context: Context): Flow<Float> = context.dataStore.data.map { it[READING_THRESHOLD] ?: 0.01f }
    suspend fun setReadingThreshold(context: Context, threshold: Float) {
        context.dataStore.edit { it[READING_THRESHOLD] = threshold }
    }
}
