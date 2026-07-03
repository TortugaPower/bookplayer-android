package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerThemeSpec
import com.tortugapower.audiobookplayer.widget.AudioWidgetLargeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * In-memory source of truth for the active theme and dark/light variant. Loads the 11 themes
 * from `assets/Themes.json` on startup, restores the user's last selection from DataStore,
 * and exposes Compose-observable state for [BookPlayerTheme] and the Themes settings screen.
 *
 * Theme selection is device-local — not synced to the backend.
 */
object ThemeManager {
    // Hardcoded fallback shown before JSON load completes. Matches the iOS "Default / Dark"
    // theme so the first frame is visually correct even before assets are read.
    private val FALLBACK = BookPlayerThemeSpec(
        title = "Default / Dark",
        locked = false,
        lightPrimaryHex = "242320",
        darkPrimaryHex = "FAFBFC",
        lightSecondaryHex = "8F8E95",
        darkSecondaryHex = "8F8E94",
        lightAccentHex = "3488D1",
        darkAccentHex = "459EEC",
        lightSeparatorHex = "DCDCDC",
        darkSeparatorHex = "434448",
        lightSystemBackgroundHex = "FAFAFA",
        darkSystemBackgroundHex = "202225",
        lightSecondarySystemBackgroundHex = "FCFBFC",
        darkSecondarySystemBackgroundHex = "111113",
        lightTertiarySystemBackgroundHex = "E8E7E9",
        darkTertiarySystemBackgroundHex = "333538",
        lightSystemGroupedBackgroundHex = "EFEEF0",
        darkSystemGroupedBackgroundHex = "2C2D30",
        lightSystemFillHex = "87A0BA",
        darkSystemFillHex = "647E98",
        lightSecondarySystemFillHex = "ACAAB1",
        darkSecondarySystemFillHex = "707176",
        lightTertiarySystemFillHex = "3488D1",
        darkTertiarySystemFillHex = "459EEC",
        lightQuaternarySystemFillHex = "3488D1",
        darkQuaternarySystemFillHex = "459EEC",
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var allThemes: List<BookPlayerThemeSpec> by mutableStateOf(emptyList())
        private set

    var currentTheme: BookPlayerThemeSpec by mutableStateOf(FALLBACK)
        private set

    var useSystemMode: Boolean by mutableStateOf(true)
        private set

    var useDarkVariant: Boolean by mutableStateOf(true)
        private set

    var isReady: Boolean by mutableStateOf(false)
        private set

    /** Load themes from assets and restore the saved selection. Call once from `MainActivity.onCreate`. */
    fun initialize(context: Context) {
        scope.launch(Dispatchers.IO) {
            val loaded = loadThemesFromAssets(context)
            val savedTitle = migrateLegacyTitle(PlaybackSettingsManager.getThemeTitle(context).first())
            val savedSystemMode = PlaybackSettingsManager.getUseSystemMode(context).first()
            val savedDark = PlaybackSettingsManager.getUseDarkVariant(context).first()
            val theme = loaded.firstOrNull { it.title == savedTitle } ?: loaded.firstOrNull() ?: FALLBACK
            withContext(Dispatchers.Main) {
                allThemes = loaded
                currentTheme = theme
                useSystemMode = savedSystemMode
                useDarkVariant = savedDark
                isReady = true
            }
        }
    }

    /** Switch the active theme and persist the choice asynchronously. */
    fun setTheme(context: Context, theme: BookPlayerThemeSpec) {
        currentTheme = theme
        scope.launch {
            PlaybackSettingsManager.setThemeTitle(context, theme.title)
            updateWidgets(context)
        }
    }

    /** When true, the variant follows the OS dark-mode setting; when false, [useDarkVariant] decides. */
    fun setUseSystemMode(context: Context, enabled: Boolean) {
        useSystemMode = enabled
        scope.launch {
            PlaybackSettingsManager.setUseSystemMode(context, enabled)
            updateWidgets(context)
        }
    }

    /** Manual dark/light override. Only consulted when [useSystemMode] is false. */
    fun setUseDarkVariant(context: Context, enabled: Boolean) {
        useDarkVariant = enabled
        scope.launch {
            PlaybackSettingsManager.setUseDarkVariant(context, enabled)
            updateWidgets(context)
        }
    }

    private fun updateWidgets(context: Context) {
        val intent = Intent(context, AudioWidgetLargeProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
            android.content.ComponentName(context, AudioWidgetLargeProvider::class.java)
        )
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }

    private fun loadThemesFromAssets(context: Context): List<BookPlayerThemeSpec> {
        return try {
            context.assets.open("Themes.json").use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val listType = object : TypeToken<List<BookPlayerThemeSpec>>() {}.type
                    Gson().fromJson<List<BookPlayerThemeSpec>>(reader, listType)
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(FALLBACK)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("ThemeManager", "Failed to load Themes.json; using FALLBACK only", t)
            listOf(FALLBACK)
        }
    }

    private fun migrateLegacyTitle(saved: String): String = when (saved) {
        "Green Forrest" -> "Forest"
        "DefaultDark" -> "Default / Dark"
        else -> saved
    }
}
