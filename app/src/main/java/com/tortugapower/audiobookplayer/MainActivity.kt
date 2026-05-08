package com.tortugapower.audiobookplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.ui.screens.MainScreen
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.initialize(this)
        PlaybackManager.initialize(this)
        setContent {
            BookPlayerTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // We don't necessarily want to release the player here if it should play in background,
        // but for now let's keep it simple. Actually, the service handles the background.
    }
}