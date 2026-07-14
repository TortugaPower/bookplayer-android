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
    // Armed when playback naturally reaches the end of a book; consumed by the phone app's
    // ReviewPromptManager to request an in-app review (mirrors iOS's "ask_review" UserDefaults flag).
    private val ASK_REVIEW = booleanPreferencesKey("ask_review")

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

    // Stable stored values for the player's list-button target (NOT user-facing — the UI localizes its
    // own labels). Named constants so the comparisons scattered across the player screens can't typo
    // their way into the silent else-branch.
    const val LIST_OPENS_CHAPTERS = "Chapters"
    const val LIST_OPENS_BOOKMARKS = "Bookmarks"

    fun getListButtonOpens(context: Context): Flow<String> = context.dataStore.data.map { it[LIST_BUTTON_OPENS] ?: LIST_OPENS_CHAPTERS }
    suspend fun setListButtonOpens(context: Context, value: String) {
        context.dataStore.edit { it[LIST_BUTTON_OPENS] = value }
    }

    fun getUseRemainingTime(context: Context): Flow<Boolean> = context.dataStore.data.map { it[USE_REMAINING_TIME] ?: true }
    suspend fun setUseRemainingTime(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[USE_REMAINING_TIME] = enabled }
    }

    // Default ON — iOS parity (prefersChapterContext defaults true): the player shows the chapter's
    // title and chapter-relative progress until the user opts out.
    fun getUseChapterContext(context: Context): Flow<Boolean> = context.dataStore.data.map { it[USE_CHAPTER_CONTEXT] ?: true }
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

    fun getAskReview(context: Context): Flow<Boolean> = context.dataStore.data.map { it[ASK_REVIEW] ?: false }
    suspend fun setAskReview(context: Context, ask: Boolean) {
        context.dataStore.edit { it[ASK_REVIEW] = ask }
    }
    
    private val AUTOPLAY_LIBRARY = booleanPreferencesKey("autoplay_library")
    private val AUTOPLAY_RESTART_FINISHED = booleanPreferencesKey("autoplay_restart_finished")
    private val PREVENT_AUTOLOCK = booleanPreferencesKey("prevent_autolock")
    private val PREVENT_AUTOLOCK_ONLY_ON_POWER = booleanPreferencesKey("prevent_autolock_only_on_power")

    fun getAutoplayLibrary(context: Context): Flow<Boolean> = context.dataStore.data.map { it[AUTOPLAY_LIBRARY] ?: true }
    suspend fun setAutoplayLibrary(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[AUTOPLAY_LIBRARY] = enabled }
    }

    fun getAutoplayRestartFinished(context: Context): Flow<Boolean> = context.dataStore.data.map { it[AUTOPLAY_RESTART_FINISHED] ?: true }
    suspend fun setAutoplayRestartFinished(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[AUTOPLAY_RESTART_FINISHED] = enabled }
    }

    fun getPreventAutolock(context: Context): Flow<Boolean> = context.dataStore.data.map { it[PREVENT_AUTOLOCK] ?: false }
    suspend fun setPreventAutolock(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[PREVENT_AUTOLOCK] = enabled }
    }

    fun getPreventAutolockOnlyOnPower(context: Context): Flow<Boolean> = context.dataStore.data.map { it[PREVENT_AUTOLOCK_ONLY_ON_POWER] ?: false }
    suspend fun setPreventAutolockOnlyOnPower(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[PREVENT_AUTOLOCK_ONLY_ON_POWER] = enabled }
    }

    // Library display prefs (iOS parity: the library Options sheet's toggles). Both default OFF.
    private val SHOW_PROGRESS_PERCENTAGE = booleanPreferencesKey("show_progress_percentage")
    private val SHOW_ORIGINAL_FILE_NAME = booleanPreferencesKey("show_original_file_name")

    fun getShowProgressAsPercentage(context: Context): Flow<Boolean> = context.dataStore.data.map { it[SHOW_PROGRESS_PERCENTAGE] ?: false }
    suspend fun setShowProgressAsPercentage(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[SHOW_PROGRESS_PERCENTAGE] = enabled }
    }

    fun getShowOriginalFileName(context: Context): Flow<Boolean> = context.dataStore.data.map { it[SHOW_ORIGINAL_FILE_NAME] ?: false }
    suspend fun setShowOriginalFileName(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[SHOW_ORIGINAL_FILE_NAME] = enabled }
    }
}
