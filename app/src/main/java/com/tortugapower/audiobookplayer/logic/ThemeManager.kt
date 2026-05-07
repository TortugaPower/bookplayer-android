package com.tortugapower.audiobookplayer.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tortugapower.audiobookplayer.ui.theme.AppTheme

object ThemeManager {
    var currentTheme by mutableStateOf<AppTheme>(AppTheme.DefaultDark)
        private set

    val allThemes = listOf(
        AppTheme.DefaultDark,
        AppTheme.PureBlack,
        AppTheme.BlueMarine,
        AppTheme.RoseBlush,
        AppTheme.Ayu,
        AppTheme.GreenForrest
    )

    fun setTheme(theme: AppTheme) {
        currentTheme = theme
    }
}
