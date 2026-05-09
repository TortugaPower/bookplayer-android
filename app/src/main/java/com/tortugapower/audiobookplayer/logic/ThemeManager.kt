package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tortugapower.audiobookplayer.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object ThemeManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var currentTheme by mutableStateOf<AppTheme>(AppTheme.DefaultDark)
        private set

    var isReady by mutableStateOf(false)
        private set

    val allThemes = listOf(
        AppTheme.DefaultDark,
        AppTheme.PureBlack,
        AppTheme.BlueMarine,
        AppTheme.RoseBlush,
        AppTheme.Ayu,
        AppTheme.GreenForrest
    )

    fun initialize(context: Context) {
        scope.launch {
            val themeName = PlaybackSettingsManager.getTheme(context).first()
            val theme = allThemes.find { it.name == themeName } ?: AppTheme.DefaultDark
            currentTheme = theme
            isReady = true
        }
    }

    fun setTheme(context: Context, theme: AppTheme) {
        currentTheme = theme
        scope.launch {
            PlaybackSettingsManager.setTheme(context, theme.name)
        }
    }
}
