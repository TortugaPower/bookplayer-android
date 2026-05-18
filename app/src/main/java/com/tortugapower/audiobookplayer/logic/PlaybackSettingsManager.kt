package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_settings")

object PlaybackSettingsManager {
    private val SPEED = floatPreferencesKey("playback_speed")
    private val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
    private val REWIND_INTERVAL = intPreferencesKey("rewind_interval")
    private val FORWARD_INTERVAL = intPreferencesKey("forward_interval")
    private val SMART_REWIND = booleanPreferencesKey("smart_rewind")
    private val SMART_REWIND_LIMIT = intPreferencesKey("smart_rewind_limit")
    private val AUTO_SLEEP_TIMER = booleanPreferencesKey("auto_sleep_timer")
    private val QUICK_ACTION_1 = floatPreferencesKey("quick_action_1")
    private val QUICK_ACTION_2 = floatPreferencesKey("quick_action_2")
    private val QUICK_ACTION_3 = floatPreferencesKey("quick_action_3")
    private val GLOBAL_SPEED_CONTROL = booleanPreferencesKey("global_speed_control")
    private val PROGRESS_BAR_SEEKING = booleanPreferencesKey("progress_bar_seeking")
    private val LIST_BUTTON_OPENS = stringPreferencesKey("list_button_opens")
    private val USE_REMAINING_TIME = booleanPreferencesKey("use_remaining_time")
    private val USE_CHAPTER_CONTEXT = booleanPreferencesKey("use_chapter_context")
    private val VOLUME = floatPreferencesKey("playback_volume")
    private val THEME_TITLE = stringPreferencesKey("app_theme")
    private val THEME_USE_SYSTEM_MODE = booleanPreferencesKey("theme_use_system_mode")
    private val THEME_USE_DARK_VARIANT = booleanPreferencesKey("theme_use_dark_variant")
    private val LAST_ITEM_UUID = stringPreferencesKey("last_item_uuid")

    fun getSpeed(context: Context): Flow<Float> = context.dataStore.data.map { it[SPEED] ?: 1.0f }
    suspend fun setSpeed(context: Context, speed: Float) {
        context.dataStore.edit { it[SPEED] = speed }
    }

    fun getVolumeBoost(context: Context): Flow<Boolean> = context.dataStore.data.map { it[VOLUME_BOOST] ?: false }
    suspend fun setVolumeBoost(context: Context, boost: Boolean) {
        context.dataStore.edit { it[VOLUME_BOOST] = boost }
    }

    fun getRewindInterval(context: Context): Flow<Int> = context.dataStore.data.map { it[REWIND_INTERVAL] ?: 30 }
    suspend fun setRewindInterval(context: Context, interval: Int) {
        context.dataStore.edit { it[REWIND_INTERVAL] = interval }
    }

    fun getForwardInterval(context: Context): Flow<Int> = context.dataStore.data.map { it[FORWARD_INTERVAL] ?: 30 }
    suspend fun setForwardInterval(context: Context, interval: Int) {
        context.dataStore.edit { it[FORWARD_INTERVAL] = interval }
    }

    fun getSmartRewind(context: Context): Flow<Boolean> = context.dataStore.data.map { it[SMART_REWIND] ?: true }
    suspend fun setSmartRewind(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[SMART_REWIND] = enabled }
    }

    fun getSmartRewindLimit(context: Context): Flow<Int> = context.dataStore.data.map { it[SMART_REWIND_LIMIT] ?: 30 }
    suspend fun setSmartRewindLimit(context: Context, limit: Int) {
        context.dataStore.edit { it[SMART_REWIND_LIMIT] = limit }
    }

    fun getAutoSleepTimer(context: Context): Flow<Boolean> = context.dataStore.data.map { it[AUTO_SLEEP_TIMER] ?: false }
    suspend fun setAutoSleepTimer(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[AUTO_SLEEP_TIMER] = enabled }
    }

    fun getQuickAction1(context: Context): Flow<Float> = context.dataStore.data.map { it[QUICK_ACTION_1] ?: 1.0f }
    suspend fun setQuickAction1(context: Context, speed: Float) {
        context.dataStore.edit { it[QUICK_ACTION_1] = speed }
    }

    fun getQuickAction2(context: Context): Flow<Float> = context.dataStore.data.map { it[QUICK_ACTION_2] ?: 2.0f }
    suspend fun setQuickAction2(context: Context, speed: Float) {
        context.dataStore.edit { it[QUICK_ACTION_2] = speed }
    }

    fun getQuickAction3(context: Context): Flow<Float> = context.dataStore.data.map { it[QUICK_ACTION_3] ?: 3.0f }
    suspend fun setQuickAction3(context: Context, speed: Float) {
        context.dataStore.edit { it[QUICK_ACTION_3] = speed }
    }

    fun getGlobalSpeedControl(context: Context): Flow<Boolean> = context.dataStore.data.map { it[GLOBAL_SPEED_CONTROL] ?: false }
    suspend fun setGlobalSpeedControl(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[GLOBAL_SPEED_CONTROL] = enabled }
    }

    fun getProgressBarSeeking(context: Context): Flow<Boolean> = context.dataStore.data.map { it[PROGRESS_BAR_SEEKING] ?: true }
    suspend fun setProgressBarSeeking(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[PROGRESS_BAR_SEEKING] = enabled }
    }

    fun getListButtonOpens(context: Context): Flow<String> = context.dataStore.data.map { it[LIST_BUTTON_OPENS] ?: "Chapters" }
    suspend fun setListButtonOpens(context: Context, value: String) {
        context.dataStore.edit { it[LIST_BUTTON_OPENS] = value }
    }

    fun getUseRemainingTime(context: Context): Flow<Boolean> = context.dataStore.data.map { it[USE_REMAINING_TIME] ?: true }
    suspend fun setUseRemainingTime(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[USE_REMAINING_TIME] = enabled }
    }

    fun getUseChapterContext(context: Context): Flow<Boolean> = context.dataStore.data.map { it[USE_CHAPTER_CONTEXT] ?: false }
    suspend fun setUseChapterContext(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[USE_CHAPTER_CONTEXT] = enabled }
    }

    fun getVolume(context: Context): Flow<Float> = context.dataStore.data.map { it[VOLUME] ?: 1.0f }
    suspend fun setVolume(context: Context, volume: Float) {
        context.dataStore.edit { it[VOLUME] = volume }
    }

    fun getThemeTitle(context: Context): Flow<String> = context.dataStore.data.map { it[THEME_TITLE] ?: "Default / Dark" }
    suspend fun setThemeTitle(context: Context, title: String) {
        context.dataStore.edit { it[THEME_TITLE] = title }
    }

    fun getUseSystemMode(context: Context): Flow<Boolean> = context.dataStore.data.map { it[THEME_USE_SYSTEM_MODE] ?: true }
    suspend fun setUseSystemMode(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[THEME_USE_SYSTEM_MODE] = enabled }
    }

    fun getUseDarkVariant(context: Context): Flow<Boolean> = context.dataStore.data.map { it[THEME_USE_DARK_VARIANT] ?: true }
    suspend fun setUseDarkVariant(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[THEME_USE_DARK_VARIANT] = enabled }
    }

    fun getLastItemUuid(context: Context): Flow<String?> = context.dataStore.data.map { it[LAST_ITEM_UUID] }
    suspend fun setLastItemUuid(context: Context, uuid: String?) {
        context.dataStore.edit { 
            if (uuid == null) it.remove(LAST_ITEM_UUID) else it[LAST_ITEM_UUID] = uuid 
        }
    }
}
